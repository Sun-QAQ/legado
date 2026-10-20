package io.legado.app.ui.book.agent

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AgentAutoCompactionTest {
    private val persona = "你是冒险家小岚，称用户为旅伴。"

    private fun build(turns: List<AgentChatTurn>): JsonObject = JsonObject().apply {
        add("messages", JsonArray().apply {
            add(JsonObject().apply { addProperty("role", "system"); addProperty("content", persona) })
            turns.forEach { turn ->
                add(JsonObject().apply {
                    addProperty("role", turn.role)
                    add("content", AgentAttachments.content(turn))
                    turn.toolCallId?.let { addProperty("tool_call_id", it) }
                    turn.toolCalls?.let { add("tool_calls", it) }
                    turn.reasoningContent?.let { addProperty("reasoning_content", it) }
                })
            }
        })
        add("tools", JsonArray().apply { add(JsonObject().apply { addProperty("name", "read_book_content") }) })
    }

    private fun call(id: String) = AgentChatTurn("assistant", "", toolCalls = JsonParser.parseString(
        """[{"id":"$id","type":"function","function":{"name":"read_book_content","arguments":"{\"query\":\"测试书\"}"}}]"""
    ), reasoningContent = "需要先读取正文")

    @Test
    fun `预算充足时请求保持原样且不生成摘要`() = runBlocking {
        val history = mutableListOf(AgentChatTurn("user", "你好"), AgentChatTurn("assistant", "旅伴你好"), AgentChatTurn("user", "继续"))
        val request = AgentAutoCompaction(history, ::build, { error("不应调用摘要") }).prepare(history.toList(), 32768)
        assertEquals(build(history), request)
        assertEquals(0, AgentAutoCompaction.checkpointEnd(history))
    }

    @Test
    fun `全局压缩按完整轮次切分并保留原始历史和当前工具配对`() = runBlocking {
        val history = mutableListOf(
            AgentChatTurn("user", "旧问题"), call("old"), AgentChatTurn("tool", "旧正文".repeat(8000), "old"),
            AgentChatTurn("assistant", "旧结论"), AgentChatTurn("user", "本轮问题"),
            call("current"), AgentChatTurn("tool", "本轮正文", "current")
        )
        val original = history.toList()
        val materials = mutableListOf<String>()
        var saved = 0
        val request = AgentAutoCompaction(history, ::build, {
            materials.add(it)
            "用户在讨论测试书，已读取旧章节，后续需要继续讨论。"
        }, onCheckpoint = { saved++ }).prepare(history.toList(), 8192)
        assertTrue(materials.isNotEmpty())
        assertTrue(materials.any { it.contains("旧正文") })
        assertTrue(materials.all { AgentContextBudget.textTokens(it) < AgentContextBudget.inputLimit(8192) })
        assertEquals(1, saved)
        assertEquals(4, AgentAutoCompaction.checkpointEnd(history))
        assertEquals(original, history.map { it.copy(historySummary = null) })
        val messages = request.getAsJsonArray("messages")
        assertEquals(persona, messages[0].asJsonObject.get("content").asString)
        assertEquals("本轮问题", messages[2].asJsonObject.get("content").asString)
        assertEquals("current", messages[3].asJsonObject.getAsJsonArray("tool_calls")[0].asJsonObject.get("id").asString)
        assertEquals("需要先读取正文", messages[3].asJsonObject.get("reasoning_content").asString)
        assertEquals("current", messages[4].asJsonObject.get("tool_call_id").asString)
        assertTrue(AgentContextBudget.tokens(request) <= AgentContextBudget.inputLimit(8192))
        assertEquals(build(original).get("tools"), request.get("tools"))
    }

    @Test
    fun `检查点可恢复且后续压缩合并旧摘要不累积摘要消息`() = runBlocking {
        val history = mutableListOf(AgentChatTurn("user", "旧请求"), AgentChatTurn("assistant", "旧答复".repeat(4000)), AgentChatTurn("user", "继续"))
        AgentAutoCompaction(history, ::build, { "重要事实：测试书前三章" }).prepare(history.toList(), 8192)
        val restored = AgentConversationCodec.decodeTurns(AgentConversationCodec.encodeTurns(history)).toMutableList()
        assertEquals(2, AgentAutoCompaction.checkpointEnd(restored))
        AgentAutoCompaction(restored, ::build, { error("缓存摘要应复用") }).prepare(restored.toList(), 32768)
        restored.add(AgentChatTurn("assistant", "新的结果".repeat(4000)))
        restored.add(AgentChatTurn("user", "接着讨论"))
        var mergedOldSummary = false
        val request = AgentAutoCompaction(restored, ::build, {
            mergedOldSummary = mergedOldSummary || it.contains("重要事实：测试书前三章")
            "重要事实：测试书前三章；新的结果已讨论。"
        }).prepare(restored.toList(), 8192)
        assertTrue(mergedOldSummary)
        assertEquals(1, restored.count { it.historySummary != null })
        assertEquals(3, request.getAsJsonArray("messages").size())
        restored[0] = restored[0].copy(content = "已编辑的原始请求")
        assertEquals(0, AgentAutoCompaction.checkpointEnd(restored))
    }

    @Test
    fun `当前轮次超大工具结果单独缩减且内部继续不切断工具调用`() = runBlocking {
        val history = mutableListOf(
            AgentChatTurn("user", "读取前三章并给我观后感"), call("read"),
            AgentChatTurn("tool", "章节正文".repeat(9000), "read"),
            AgentChatTurn("user", "继续", internalPrompt = true)
        )
        val original = history.toList()
        val request = AgentAutoCompaction(history, ::build, { "前三章的事实摘要。" }).prepare(history.toList(), 8192)
        assertEquals(original, history)
        assertEquals(0, AgentAutoCompaction.checkpointEnd(history))
        val messages = request.getAsJsonArray("messages")
        assertEquals(5, messages.size())
        assertEquals("读取前三章并给我观后感", messages[1].asJsonObject.get("content").asString)
        assertEquals("read", messages[3].asJsonObject.get("tool_call_id").asString)
        assertTrue(messages[3].asJsonObject.get("content").asString.contains("本轮资料摘要"))
    }

    @Test
    fun `文本附件参与摘要但本轮问题和磁盘文件保持完整`() = runBlocking {
        val file = File.createTempFile("agent-context-", ".txt")
        try {
            val text = "附件中的关键事实".repeat(5000)
            file.writeText(text)
            val history = mutableListOf(AgentChatTurn("user", "用小岚的口吻解释这个附件", attachments = listOf(
                AgentAttachment("资料.txt", "text/plain", file.absolutePath, file.length().toInt())
            )))
            var sawFile = false
            val request = AgentAutoCompaction(history, ::build, {
                sawFile = sawFile || it.contains("附件中的关键事实")
                "附件资料事实摘要。"
            }).prepare(history.toList(), 8192)
            assertTrue(sawFile)
            val sent = request.getAsJsonArray("messages")[1].asJsonObject.get("content").asString
            assertTrue(sent.startsWith(history[0].content))
            assertTrue(sent.contains("附件资料事实摘要"))
            assertEquals(text, file.readText())
            assertEquals(1, history[0].attachments!!.size)
        } finally { file.delete() }
    }

    @Test
    fun `摘要失败退回明确标记的不完整摘录仍可继续`() = runBlocking {
        val history = mutableListOf(AgentChatTurn("user", "旧要求"), AgentChatTurn("assistant", "事实".repeat(6000)), AgentChatTurn("user", "继续"))
        val request = AgentAutoCompaction(history, ::build, { error("服务端错误，不应进入摘要") }).prepare(history.toList(), 8192)
        val summary = request.getAsJsonArray("messages")[1].asJsonObject.get("content").asString
        assertTrue(summary.contains("自动摘要暂不可用"))
        assertTrue(summary.contains("资料不完整"))
        assertFalse(summary.contains("服务端错误"))
        assertEquals("事实".repeat(6000), history[1].content)
    }

    @Test
    fun `服务端超限只缩减重试一次且不会原样重发`() = runBlocking {
        val history = mutableListOf(AgentChatTurn("user", "先前要求"), AgentChatTurn("assistant", "先前回答".repeat(600)), AgentChatTurn("user", "继续"))
        var sends = 0
        val sizes = mutableListOf<Long>()
        try {
            AgentAutoCompaction(history, ::build, { "历史重要事实" }).complete(history.toList(), 32768) {
                sends++
                sizes.add(AgentContextBudget.tokens(it))
                throw AgentContextOverflowException()
            }
            fail("第二次超限应上报")
        } catch (_: AgentContextOverflowException) { }
        assertEquals(2, sends)
        assertTrue(sizes[1] < sizes[0])
    }

    @Test
    fun `普通请求错误不触发压缩重试`() = runBlocking {
        val history = mutableListOf(AgentChatTurn("user", "你好"))
        var sends = 0
        try {
            AgentAutoCompaction(history, ::build, { error("不应生成摘要") }).complete(history.toList(), 32768) {
                sends++
                throw IllegalStateException("HTTP 401")
            }
            fail("应上报原错误")
        } catch (e: IllegalStateException) { assertEquals("HTTP 401", e.message) }
        assertEquals(1, sends)
    }

    @Test
    fun `取消摘要不写入半成品检查点`() = runBlocking {
        val history = mutableListOf(AgentChatTurn("user", "旧问题"), AgentChatTurn("assistant", "旧内容".repeat(6000)), AgentChatTurn("user", "新问题"))
        val original = history.toList()
        try {
            AgentAutoCompaction(history, ::build, { throw CancellationException("取消") }).prepare(history.toList(), 8192)
            fail("应取消")
        } catch (_: CancellationException) { }
        assertEquals(original, history)
    }

    @Test
    fun `本轮问题自身过大时明确失败且不静默截断`() = runBlocking {
        val question = "问题".repeat(10000)
        val history = mutableListOf(AgentChatTurn("user", question))
        try {
            AgentAutoCompaction(history, ::build, { error("用户问题不可缩减") }).prepare(history.toList(), 8192)
            fail("应提示减少输入")
        } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("缩短输入")) }
        assertEquals(question, history.single().content)
    }
}
