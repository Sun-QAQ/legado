package io.legado.app.ui.book.agent

import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.isLocal
import io.legado.app.model.webBook.WebBook

/** 获取书籍目录与章节正文，供 AI 章节读取工具使用。 */
internal class ReadingContextProvider {

    /** 获取章节列表：数据库优先，网络书缺少目录时再联网抓取。 */
    suspend fun getBookChapters(book: Book): List<BookChapter> {
        appDb.bookChapterDao.getChapterList(book.bookUrl)
            .takeIf { it.isNotEmpty() }
            ?.let { return it }
        if (book.isLocal) return emptyList()
        val source = appDb.bookSourceDao.getBookSource(book.origin)
        if (source == null || book.tocUrl.isBlank()) return emptyList()
        val toc = runCatching { WebBook.getChapterListAwait(source, book).getOrThrow() }
            .onFailure { AppLog.put("读取《${book.name}》章节列表失败", it) }
            .getOrDefault(emptyList())
        if (toc.isNotEmpty()) {
            appDb.bookChapterDao.insert(*toc.toTypedArray())
        }
        return toc
    }

    /** 读取单章正文：本地缓存优先，网络书未缓存时联网抓取。 */
    suspend fun getChapterText(
        book: Book,
        chapter: BookChapter,
        nextChapter: BookChapter?,
        source: BookSource?
    ): String? {
        BookHelp.getContent(book, chapter)?.let { return it }
        if (book.isLocal || source == null) return null
        val nextUrl = nextChapter?.takeIf { !it.isVolume }?.getAbsoluteURL()
        return runCatching {
            WebBook.getContentAwait(
                bookSource = source,
                book = book,
                bookChapter = chapter,
                nextChapterUrl = nextUrl
            )
        }.getOrNull()
            ?.takeIf { it.isNotBlank() && it != chapter.getAbsoluteURL() }
    }

}
