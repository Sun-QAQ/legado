package io.legado.app.ui.about

import io.legado.app.data.appDb
import io.legado.app.data.dao.BookDao
import io.legado.app.data.entities.Book
import io.legado.app.help.book.isNotShelf

internal data class AnnualReadReportCover(
    val path: String?,
    val author: String,
    val origin: String,
)

/** Resolve current bookshelf covers without putting URLs into saved reading statistics. */
internal object AnnualReadReportCovers {
    // Call on the IO dispatcher, alongside loading the report.
    fun load(report: AnnualReadReport, bookDao: BookDao = appDb.bookDao): Map<String, AnnualReadReportCover> {
        val names = report.books.take(10).map { it.name }.toTypedArray()
        if (names.isEmpty()) return emptyMap()
        return select(bookDao.findByName(*names))
    }

    fun select(books: List<Book>): Map<String, AnnualReadReportCover> = books.asSequence()
        .filterNot { it.isNotShelf }
        // Reports aggregate by title. Prefer the most recently read shelf entry when
        // multiple authors share a title, with a stable tie-breaker for older snapshots.
        .sortedWith(compareByDescending<Book> { it.durChapterTime }.thenBy { it.bookUrl })
        .distinctBy { it.name }
        .associate { it.name to AnnualReadReportCover(it.getDisplayCover(), it.author, it.origin) }
}
