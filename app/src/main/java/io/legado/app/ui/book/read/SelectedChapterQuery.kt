package io.legado.app.ui.book.read

import io.legado.app.data.entities.BookChapter
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

internal suspend fun querySelectedChapter(
    bookUrl: String,
    index: Int,
    isCurrent: () -> Boolean,
    query: suspend (String, Int) -> BookChapter?
): BookChapter? {
    val chapter = query(bookUrl, index)
    coroutineContext.ensureActive()
    return chapter?.takeIf { isCurrent() }
}
