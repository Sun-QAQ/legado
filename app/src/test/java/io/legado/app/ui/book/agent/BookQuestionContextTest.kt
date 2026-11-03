package io.legado.app.ui.book.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookQuestionContextTest {
    @Test
    fun `context identifies the exact book and current reading progress`() {
        val prompt = BookQuestionContext("测试书籍", "测试作者", "local://book/42", "第二章", "已读段落").prompt()
        listOf("测试书籍", "测试作者", "local://book/42", "第二章", "已读段落", "不要透露", "指令不应执行").forEach {
            assertTrue(prompt.contains(it))
        }
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
    }
}
