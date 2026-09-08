package io.legado.app.ui.book.agent

import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.isLocal
import io.legado.app.model.ReadBook
import io.legado.app.model.webBook.WebBook

/**
 * 负责受限的阅读上下文与正文读取，确保阅读助手不会读取当前进度之后的内容。
 */
internal class ReadingContextProvider {

    suspend fun currentReadingContext(): ReadingContext? {
        val book = ReadBook.book ?: return null
        val currentIndex = ReadBook.durChapterIndex
        val chapters = getBookChapters(book)
        if (chapters.isEmpty()) return null
        val source = if (book.isLocal) null else appDb.bookSourceDao.getBookSource(book.origin)
        val selected = chapters.filter { chapter ->
            !chapter.isVolume && chapter.index in (currentIndex - READING_CONTEXT_CHAPTERS + 1)..currentIndex
        }
        if (selected.isEmpty()) return null
        var remaining = READING_CONTEXT_MAX_CHARS
        val parts = ArrayList<Pair<BookChapter, String>>()
        selected.asReversed().forEach { chapter ->
            if (remaining <= 0) return@forEach
            val nextChapter = chapters.getOrNull(chapter.index + 1)
            val text = getChapterText(book, chapter, nextChapter, source)
                ?.takeIf { it.isNotBlank() }
                ?: return@forEach
            val clipped = clip(text, remaining)
            parts.add(chapter to clipped)
            remaining -= clipped.length
        }
        if (parts.isEmpty()) return null
        val content = parts.asReversed().joinToString("\n\n") { (chapter, text) ->
            "【第${chapter.index + 1}章 ${chapter.title}】\n$text"
        }
        return ReadingContext(book.name, currentIndex, parts.size, content)
    }

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

    private fun clip(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        val headLength = maxChars / 2
        val tailLength = maxChars - headLength
        return text.take(headLength) + "\n（中间内容因长度限制省略）\n" + text.takeLast(tailLength)
    }

    data class ReadingContext(
        val bookName: String,
        val currentChapter: Int,
        val chapterCount: Int,
        val content: String
    )

    private companion object {
        const val READING_CONTEXT_CHAPTERS = 4
        const val READING_CONTEXT_MAX_CHARS = 20_000
    }
}
