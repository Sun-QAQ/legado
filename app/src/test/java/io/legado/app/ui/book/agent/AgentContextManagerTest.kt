package io.legado.app.ui.book.agent

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class AgentContextManagerTest {
    private fun reading(text: String, id: String = "read"): AgentChatTurn = AgentChatTurn(
        "tool", text, toolCallId = id,
        readingContext = ChapterContext(
            "book-url", "测试书", "作者", listOf(ChapterReference(11, "旧信", "chapter-url")),
            false, AgentContextManager.hash(text)
        )
    )

    @Test
    fun `本轮全部正文和最近两轮保留更早正文压缩`() {
        val turns = (0..3).flatMap { listOf(AgentChatTurn("user", "问题$it"), reading("正文$it")) }
        assertEquals(setOf(1), AgentContextManager.compactIndices(turns))
        assertEquals("正文3", AgentContextManager.project(turns, setOf(1))[7].content)
    }

    @Test
    fun `历史预算从最旧正文扣除本轮超过预算仍完整保留`() {
        val turns = listOf(
            AgentChatTurn("user", "旧问题"), reading("a".repeat(20)),
            AgentChatTurn("user", "上个问题"), reading("b".repeat(10)),
            AgentChatTurn("user", "当前问题"), reading("c".repeat(100))
        )
        val compact = AgentContextManager.compactIndices(turns, historyChars = 15)
        assertEquals(setOf(1), compact)
        assertEquals("c".repeat(100), AgentContextManager.project(turns, compact).last().content)
    }

    @Test
    fun `内部继续提示不额外占用用户轮次`() {
        val turns = listOf(
            AgentChatTurn("user", "问题"), reading("正文"),
            AgentChatTurn("user", "继续", internalPrompt = true), reading("新正文")
        )
        assertTrue(AgentContextManager.compactIndices(turns, historyChars = 0).isEmpty())
    }

    @Test
    fun `投影保留多工具配对原文与非章节工具`() {
        val call = AgentChatTurn("assistant", "", toolCalls = JsonParser.parseString(
            """[{"id":"read"},{"id":"search"}]"""
        ))
        val original = reading("完整正文")
        val turns = listOf(call, original, AgentChatTurn("tool", "搜索结果", toolCallId = "search"))
        val projected = AgentContextManager.project(turns, setOf(1))
        assertEquals(call, projected[0])
        assertEquals("tool", projected[1].role)
        assertEquals("read", projected[1].toolCallId)
        assertTrue(projected[1].content.contains("startIndex=11"))
        assertTrue(projected[1].content.contains("摘要暂不可用"))
        assertFalse(projected[1].content.contains("完整正文"))
        assertEquals("完整正文", original.content)
        assertEquals(turns[2], projected[2])
    }

    @Test
    fun `摘要缓存只对相同正文和版本有效且标明截断`() {
        val turn = reading("部分正文").let {
            it.copy(readingContext = it.readingContext!!.copy(
                truncated = true, summary = "事实摘要", summaryVersion = AgentContextManager.SUMMARY_VERSION
            ))
        }
        assertEquals("事实摘要", AgentContextManager.cachedSummary(turn))
        assertNull(AgentContextManager.cachedSummary(turn.copy(content = "新正文")))
        assertNull(AgentContextManager.cachedSummary(turn.copy(
            readingContext = turn.readingContext!!.copy(summaryVersion = 0)
        )))
        val content = AgentContextManager.project(listOf(turn), setOf(0))[0].content
        assertTrue(content.contains("资料不完整"))
        assertTrue(content.contains("事实摘要"))
    }

    @Test
    fun `新字段完整恢复旧会话缺失字段兼容`() {
        val turn = reading("正文").let {
            it.copy(readingContext = it.readingContext!!.copy(
                summary = "摘要", summaryVersion = AgentContextManager.SUMMARY_VERSION
            ))
        }
        assertEquals(listOf(turn), AgentConversationCodec.decodeTurns(AgentConversationCodec.encodeTurns(listOf(turn))))
        val old = AgentConversationCodec.decodeTurns("""[{"role":"tool","content":"旧正文","toolCallId":"old"}]""").single()
        assertNull(old.readingContext)
        assertEquals(old, AgentContextManager.project(listOf(old), emptySet()).single())
    }
}
