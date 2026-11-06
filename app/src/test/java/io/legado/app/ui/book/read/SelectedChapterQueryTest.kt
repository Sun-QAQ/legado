package io.legado.app.ui.book.read

import io.legado.app.data.entities.BookChapter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SelectedChapterQueryTest {
    @Test fun `late chapter is discarded after book or chapter changes or page closes`() = runBlocking {
        for (change in listOf("book", "chapter", "closed")) {
            var book = "first"
            var index = 0
            var active = true
            val result = CompletableDeferred<BookChapter?>()
            val request = async(start = CoroutineStart.UNDISPATCHED) {
                querySelectedChapter("first", 0, { active && book == "first" && index == 0 }) { url, chapter ->
                    assertEquals("first", url)
                    assertEquals(0, chapter)
                    result.await()
                }
            }
            when (change) {
                "book" -> book = "second"
                "chapter" -> index = 1
                else -> active = false
            }
            result.complete(BookChapter(bookUrl = "first", index = 0))
            assertNull(request.await())
        }
    }

    @Test fun `current chapter and missing chapter preserve query results`() = runBlocking {
        val chapter = BookChapter(bookUrl = "book", index = 2)
        assertSame(chapter, querySelectedChapter("book", 2, { true }) { _, _ -> chapter })
        assertNull(querySelectedChapter("book", 2, { true }) { _, _ -> null })
    }
}
