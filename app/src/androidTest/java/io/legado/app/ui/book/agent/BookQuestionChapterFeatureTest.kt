package io.legado.app.ui.book.agent

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonObject
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiSource
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookHelp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/** 使用合成缓存章节验证真实请求和工具执行路径，不调用模型或外部书源。 */
@RunWith(AndroidJUnit4::class)
class BookQuestionChapterFeatureTest {
    @Test
    fun currentChapterReadsBoundFullChapterWhileExplicitFirstChapterStillWorks() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val agent = AgentViewModel(app, restoreLatestConversation = false)
        val id = UUID.randomUUID().toString()
        val book = Book(bookUrl = "https://synthetic.invalid/$id", name = "问书定位测试$id", author = "合成作者",
            origin = "https://synthetic.invalid")
        val chapters = (0..2).map {
            BookChapter(bookUrl = book.bookUrl, url = "${book.bookUrl}/$it", index = it, title = "合成章节$it")
        }
        val currentText = "当前章节独有正文" + "中".repeat(13000) + "当前章节结尾"
        try {
            appDb.bookDao.insert(book)
            appDb.bookChapterDao.insert(*chapters.toTypedArray())
            BookHelp.saveText(book, chapters[0], "书籍介绍独有正文")
            BookHelp.saveText(book, chapters[1], currentText)
            BookHelp.saveText(book, chapters[2], "后续章节独有正文")

            assertFalse(toolNames(request(agent)).contains(BookQuestionContext.CURRENT_CHAPTER_TOOL))
            agent.bookQuestionContext = BookQuestionContext(book.name, book.author, book.bookUrl,
                chapters[1].title, "仅当前页摘录", 1)
            val request = request(agent)
            assertTrue(toolNames(request).contains(BookQuestionContext.CURRENT_CHAPTER_TOOL))
            val prompt = request.getAsJsonArray("messages")[0].asJsonObject.get("content").asString
            assertTrue(prompt.contains("当前目录索引（从0开始）：1"))
            val schema = request.getAsJsonArray("tools").map { it.asJsonObject.getAsJsonObject("function") }
                .single { it.get("name").asString == BookQuestionContext.CURRENT_CHAPTER_TOOL }
            assertEquals(0, schema.getAsJsonObject("parameters").getAsJsonObject("properties").size())

            // 即使模型误传了第0章、其他书籍和多章参数，当前章节工具仍绑定目录第1项。
            val result = execute(agent, BookQuestionContext.CURRENT_CHAPTER_TOOL,
                """{"book":"wrong-book","startIndex":0,"count":20}""")
            val text = result.javaClass.getDeclaredField("text").apply { isAccessible = true }.get(result) as String
            assertTrue(text.contains(currentText))
            assertFalse(text.contains("书籍介绍独有正文"))
            assertFalse(text.contains("后续章节独有正文"))
            val metadata = result.javaClass.getDeclaredField("readingContext").apply { isAccessible = true }
                .get(result) as ChapterContext
            assertEquals(book.bookUrl, metadata.bookUrl)
            assertEquals(listOf(1), metadata.chapters.map { it.index })

            val first = execute(agent, "read_book_content",
                """{"book":"${book.bookUrl}","startIndex":0,"count":1}""")
            val firstText = first.javaClass.getDeclaredField("text").apply { isAccessible = true }.get(first) as String
            assertTrue(firstText.contains("书籍介绍独有正文"))
            assertFalse(firstText.contains("当前章节独有正文"))
        } finally {
            chapters.forEach { BookHelp.delContent(book, it) }
            appDb.bookDao.delete(book)
        }
    }

    private fun request(agent: AgentViewModel): JsonObject = AgentViewModel::class.java.getDeclaredMethod(
        "buildChatRequest", AiSource::class.java, List::class.java
    ).apply { isAccessible = true }.invoke(agent, AiSource(model = "synthetic-test", baseUrl = "http://localhost"),
        listOf(AgentChatTurn("user", "当前章节主要讲了什么？"))) as JsonObject

    private fun toolNames(request: JsonObject): List<String> = request.getAsJsonArray("tools")
        .map { it.asJsonObject.getAsJsonObject("function").get("name").asString }

    private suspend fun execute(agent: AgentViewModel, name: String, arguments: String): Any =
        suspendCoroutineUninterceptedOrReturn { continuation ->
            AgentViewModel::class.java.getDeclaredMethod("executeTool", String::class.java, String::class.java,
                Boolean::class.javaPrimitiveType, Continuation::class.java)
                .apply { isAccessible = true }.invoke(agent, name, arguments, false, continuation)
        }
}
