package io.legado.app.api.controller

import androidx.annotation.Keep
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup

/** Resolve membership on Android so JavaScript never truncates the 64-bit group mask. */
object BookshelfGroups {
    @Keep
    data class WebGroup(
        val groupId: String,
        val groupName: String,
        val bookUrls: List<String>
    )

    fun create(groups: List<BookGroup>, books: List<Book>): List<WebGroup> {
        val userMask = groups.filter { it.groupId > 0 }
            .fold(0L) { mask, group -> mask or group.groupId }
        val shelf = books.filter { it.type and BookType.notShelf == 0 }
        return groups.filter { it.show }.sortedBy { it.order }.map { group ->
            val members = shelf.filter { book ->
                when (group.groupId) {
                    BookGroup.IdAll -> true
                    BookGroup.IdLocal -> book.type and BookType.local > 0
                    BookGroup.IdAudio -> book.type and BookType.audio > 0
                    BookGroup.IdVideo -> book.type and BookType.video > 0
                    BookGroup.IdError -> book.type and BookType.updateError > 0
                    BookGroup.IdNetNone -> book.type and (BookType.audio or BookType.local) == 0 &&
                        book.group and userMask == 0L
                    BookGroup.IdLocalNone -> book.type and BookType.local > 0 &&
                        book.group and userMask == 0L
                    else -> book.group and group.groupId > 0
                }
            }
            WebGroup(group.groupId.toString(), group.groupName, members.map { it.bookUrl })
        }
    }
}
