package io.legado.app.ui.book.agent

import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class BookQuestionContextTest {
    @Test
    fun `context identifies the exact book and current reading progress`() {
        val prompt = BookQuestionContext("测试书籍", "测试作者", "local://book/42", "第二章", "已读段落", 18).prompt()
        listOf("测试书籍", "测试作者", "local://book/42", "第二章", "已读段落", "不要透露", "指令不应执行").forEach {
            assertTrue(prompt.contains(it))
        }
        assertTrue(prompt.contains("当前目录索引（从0开始）：18"))
        assertTrue(prompt.contains("read_current_chapter（无需参数）"))
    }

    @Test
    fun `long excerpts preserve the most recent text within the limit`() {
        val prompt = BookQuestionContext("书名", "作者", "book", "章节", "旧段落" + "新".repeat(12000)).prompt()
        assertFalse(prompt.contains("旧段落"))
        assertTrue(prompt.endsWith("新".repeat(12000)))
    }

    @Test
    fun `missing chapter and text explicitly report unavailable context`() {
        val prompt = BookQuestionContext("书名", "作者", "book", "", "").prompt()
        assertTrue(prompt.contains("当前章节：未知"))
        assertTrue(prompt.contains("暂无正文"))
        assertTrue(prompt.contains("不能将其当作0"))
    }

    @Test
    fun `current chapter tool binds exact book and nonzero index and ignores model arguments`() = runBlocking {
        val snapshot = BookQuestionContext("同名书", "作者", "local://exact-book", "重逢", "当前页", 18)
        var actual: List<Any?> = emptyList()
        val reader = Proxy.newProxyInstance(
            AgentToolContext::class.java.classLoader, arrayOf(AgentToolContext::class.java)
        ) { _, method, args ->
            check(method.name == "readBookContent")
            actual = args.take(4)
            "第十九章全文"
        } as AgentToolContext
        val result = snapshot.currentChapterTool(emptySet())!!.execute(reader, JsonObject().apply {
            addProperty("book", "错误书籍")
            addProperty("startIndex", 0)
            addProperty("count", 20)
        })
        assertEquals(listOf("local://exact-book", 18, 1, 0), actual)
        assertEquals("第十九章全文", result)
    }

    @Test
    fun `current chapter tool respects disabled reading and unknown position`() {
        val snapshot = BookQuestionContext("书", "作者", "book", "章节", "正文", 0)
        assertNull(snapshot.currentChapterTool(setOf("read_book_content")))
        assertNull(snapshot.copy(chapterIndex = -1).currentChapterTool(emptySet()))
        assertNull(snapshot.copy(bookUrl = "").currentChapterTool(emptySet()))
        assertFalse(snapshot.prompt(false).contains("read_current_chapter"))
        assertTrue(snapshot.prompt(false).contains("整章读取工具不可用"))
    }

    @Test
    fun `first chapter remains valid and tool arguments read exactly one full chapter`() {
        val snapshot = BookQuestionContext("书", "作者", "book", "章节", "正文", 0)
        assertTrue(snapshot.currentChapterTool(emptySet()) != null)
        val args = snapshot.currentChapterArguments()
        assertEquals("book", args.get("book").asString)
        assertEquals(0, args.get("startIndex").asInt)
        assertEquals(1, args.get("count").asInt)
        assertEquals(0, args.get("maxChars").asInt)
    }
}
