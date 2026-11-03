package io.legado.app.ui.about

import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import org.junit.Assert.*
import org.junit.Test

class AnnualReadReportCoversTest {
    @Test
    fun `current custom cover takes priority over the original cover`() {
        val book = Book(bookUrl = "book", name = "Title", author = "Author",
            coverUrl = "original-cover", customCoverUrl = "custom-cover")
        val selected = AnnualReadReportCovers.select(listOf(book)).getValue(book.name)
        assertEquals("custom-cover", selected.path)
        assertEquals(book.author, selected.author)
        assertEquals(book.origin, selected.origin)
        book.customCoverUrl = "updated-cover"
        assertEquals("updated-cover", AnnualReadReportCovers.select(listOf(book)).getValue(book.name).path)
        assertEquals("custom-cover", selected.path)
    }

    @Test
    fun `only shelf entries participate and the most recently read title wins`() {
        val older = Book(bookUrl = "old", name = "Title", author = "Older", coverUrl = "old-cover", durChapterTime = 10)
        val newer = Book(bookUrl = "new", name = "Title", author = "Newer", coverUrl = "new-cover", durChapterTime = 20)
        val offShelf = Book(bookUrl = "search-result", name = "Title", author = "Other",
            coverUrl = "not-on-shelf", type = BookType.text or BookType.notShelf, durChapterTime = 30)
        assertEquals("new-cover", AnnualReadReportCovers.select(listOf(offShelf, older, newer))["Title"]?.path)
        assertTrue(AnnualReadReportCovers.select(listOf(offShelf)).isEmpty())
    }

    @Test
    fun `missing covers stay missing and tied matches are deterministic`() {
        val first = Book(bookUrl = "a", name = "Title", author = "A", durChapterTime = 10)
        val second = Book(bookUrl = "b", name = "Title", author = "B", coverUrl = "other-cover", durChapterTime = 10)
        val result = AnnualReadReportCovers.select(listOf(second, first)).getValue("Title")
        assertEquals("A", result.author)
        assertNull(result.path)
        assertEquals(result, AnnualReadReportCovers.select(listOf(first, second))["Title"])
    }
}
