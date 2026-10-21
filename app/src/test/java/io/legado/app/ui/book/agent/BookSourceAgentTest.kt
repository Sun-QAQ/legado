package io.legado.app.ui.book.agent

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.legado.app.data.entities.BookSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookSourceAgentTest {
    private fun calls(vararg names: String) = JsonObject().apply {
        addProperty("content", "正在处理")
        add("tool_calls", JsonArray().apply {
            names.forEachIndexed { index, name ->
                add(JsonObject().apply {
                    addProperty("id", "call-$index")
                    addProperty("type", "function")
                    add("function", JsonObject().apply {
                        addProperty("name", name)
                        addProperty("arguments", "{}")
                    })
                })
            }
        })
    }

    @Test
    fun `主助手只暴露委派入口而子代理只能使用书源工具并尊重禁用配置`() {
        fun names(disabled: Set<String>) = AgentTools.toJsonArray(disabled).map {
            it.asJsonObject.getAsJsonObject("function").get("name").asString
        }.toSet()
        val main = names(BookSourceAgent.mainDisabledTools(emptySet()))
        assertTrue(main.containsAll(setOf("create_book_source", "edit_book_source", "list_book_sources")))
        assertTrue(main.intersect(BookSourceAgent.privateTools).isEmpty())
        val childDisabled = BookSourceAgent.disabledTools(setOf("fetch_page"))
        assertEquals(BookSourceAgent.tools - "fetch_page", names(childDisabled))
        assertNull(AgentTools.find("fetch_page", childDisabled))
        assertNull(AgentTools.find("create_book_source", childDisabled))
        assertNull(AgentTools.find("edit_book_source", childDisabled))
    }

    @Test
    fun `子代理隔离历史保留工具配对并只返回真实保存结果`() = runBlocking {
        val parent = listOf(AgentChatTurn("user", "修改指定书源"))
        var saved: String? = null
        val result = BookSourceAgent.run(
            initialTurn = AgentChatTurn("user", "原书源JSON与页面资料"),
            request = { turns, round ->
                if (round == 0) {
                    assertEquals(1, turns.size)
                    calls("fetch_page")
                } else {
                    assertEquals(3, turns.size)
                    assertEquals("call-0", turns[2].toolCallId)
                    assertEquals("页面HTML", turns[2].content)
                    calls("save_book_source")
                }
            },
            execute = { name, _ ->
                if (name == "save_book_source") {
                    saved = "书源编辑成功：测试书源"
                    saved!!
                } else "页面HTML"
            },
            savedResult = { saved }
        )
        assertTrue(result.saved)
        assertEquals(saved, result.text)
        assertEquals(listOf(AgentChatTurn("user", "修改指定书源")), parent)
        assertFalse(result.text.contains("页面HTML"))
    }

    @Test
    fun `保存失败后能修正并重试而保存后不执行剩余修改`() = runBlocking {
        var attempts = 0
        var updates = 0
        var saved: String? = null
        val result = BookSourceAgent.run(
            AgentChatTurn("user", "修复正文规则"),
            request = { _, round ->
                if (round == 0) calls("save_book_source")
                else calls("update_book_source", "save_book_source", "update_book_source")
            },
            execute = { name, _ ->
                if (name == "save_book_source") {
                    attempts++
                    if (attempts == 2) saved = "书源编辑成功"
                    saved ?: "书源保存失败：正文为空"
                } else {
                    updates++
                    "草稿已更新"
                }
            },
            savedResult = { saved }
        )
        assertTrue(result.saved)
        assertEquals(2, attempts)
        assertEquals(1, updates)
    }

    @Test
    fun `文字宣称保存不能替代保存成功信号`() = runBlocking {
        var requests = 0
        val result = BookSourceAgent.run(
            AgentChatTurn("user", "创建书源"),
            request = { _, _ ->
                requests++
                JsonObject().apply { addProperty("content", "已保存") }
            },
            execute = { _, _ -> error("不应调用工具") },
            savedResult = { null }
        )
        assertFalse(result.saved)
        assertTrue(result.text.startsWith("书源子代理未保存书源"))
        assertEquals(2, requests)
    }

    @Test
    fun `拒绝嵌套代理和无关工具且达到上限后报告未保存`() = runBlocking {
        val result = BookSourceAgent.run(
            AgentChatTurn("user", "创建书源"),
            request = { _, _ -> calls("create_book_source", "edit_book_source", "add_book_to_shelf") },
            execute = { _, _ -> error("不得执行越界工具") },
            savedResult = { null },
            maxRounds = 3
        )
        assertFalse(result.saved)
        assertTrue(result.text.contains("3轮上限，未保存书源"))
    }

    @Test
    fun `取消子代理立即传播且不会继续保存`() = runBlocking {
        var executed = false
        try {
            BookSourceAgent.run(
                AgentChatTurn("user", "创建书源"),
                request = { _, _ -> throw CancellationException("用户停止") },
                execute = { _, _ -> executed = true; "不应执行" },
                savedResult = { null }
            )
            error("应传播取消")
        } catch (_: CancellationException) {
            assertFalse(executed)
        }
    }

    @Test
    fun `编辑保留原分组禁用状态及排序且不允许更换主键`() {
        val original = BookSource(
            bookSourceUrl = "https://example.com", bookSourceGroup = "收藏", enabled = false,
            enabledExplore = false, customOrder = 12, weight = 8
        )
        val draft = BookSource(bookSourceUrl = original.bookSourceUrl, bookSourceGroup = "AI生成")
        BookSourceAgent.preserveSourceSettings(draft, original)
        assertEquals("收藏", draft.bookSourceGroup)
        assertFalse(draft.enabled)
        assertFalse(draft.enabledExplore)
        assertEquals(12, draft.customOrder)
        assertEquals(8, draft.weight)
        original.bookSourceGroup = null
        BookSourceAgent.preserveSourceSettings(draft, original)
        assertNull(draft.bookSourceGroup)
        draft.bookSourceUrl = "https://other.example.com"
        assertTrue(runCatching { BookSourceAgent.preserveSourceSettings(draft, original) }.isFailure)
    }

    @Test
    fun `新建书源保存到AI生成分组并启用`() {
        val draft = BookSource(bookSourceGroup = "任意分组", enabled = false)
        BookSourceAgent.preserveSourceSettings(draft, null)
        assertEquals("AI生成", draft.bookSourceGroup)
        assertTrue(draft.enabled)
    }
}
