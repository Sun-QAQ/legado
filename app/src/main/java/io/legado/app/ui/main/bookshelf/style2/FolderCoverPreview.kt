package io.legado.app.ui.main.bookshelf.style2

import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.utils.cnCompare
import kotlin.math.max

/** A separate display snapshot lets the differ notice changes to books inside a folder. */
data class BookshelfFolder(
    val group: BookGroup,
    val covers: List<FolderBookCover>
)

data class FolderBookCover(
    val path: String?,
    val name: String,
    val author: String,
    val origin: String
)

object FolderCoverPreview {
    fun sort(books: List<Book>, sort: Int): List<Book> = when (sort) {
        1 -> books.sortedByDescending { it.latestChapterTime }
        2 -> books.sortedWith { first, second -> first.name.cnCompare(second.name) }
        3 -> books.sortedBy { it.order }
        4 -> books.sortedByDescending { max(it.latestChapterTime, it.durChapterTime) }
        5 -> books.sortedWith { first, second -> first.author.cnCompare(second.author) }
        else -> books.sortedByDescending { it.durChapterTime }
    }

    fun create(
        group: BookGroup,
        books: List<Book>,
        allGroups: List<BookGroup>,
        defaultSort: Int
    ): BookshelfFolder {
        val userGroupMask = allGroups.filter { it.groupId > 0 }
            .fold(0L) { mask, item -> mask or item.groupId }
        val members = books.filter { book ->
            book.type and BookType.notShelf == 0 && when (group.groupId) {
                BookGroup.IdAll -> true
                BookGroup.IdLocal -> book.type and BookType.local > 0
                BookGroup.IdAudio -> book.type and BookType.audio > 0
                BookGroup.IdVideo -> book.type and BookType.video > 0
                BookGroup.IdError -> book.type and BookType.updateError > 0
                BookGroup.IdNetNone -> book.type and (BookType.audio or BookType.local) == 0 &&
                    book.group and userGroupMask == 0L
                BookGroup.IdLocalNone -> book.type and BookType.local > 0 &&
                    book.group and userGroupMask == 0L
                else -> book.group and group.groupId > 0
            }
        }
        val sortOrder = group.bookSort.takeIf { it >= 0 } ?: defaultSort
        return BookshelfFolder(group.copy(), sort(members, sortOrder).take(4).map { book ->
            FolderBookCover(book.getDisplayCover(), book.name, book.author, book.origin)
        })
    }
}
