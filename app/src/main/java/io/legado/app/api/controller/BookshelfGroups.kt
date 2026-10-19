package io.legado.app.api.controller

import androidx.annotation.Keep
import com.google.gson.JsonObject
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject

/** Resolve membership on Android so JavaScript never truncates the 64-bit group mask. */
object BookshelfGroups {
    @Keep
    data class GroupOption(val groupId: String, val groupName: String)

    @Keep
    data class Selection(val groups: List<GroupOption>, val selectedGroupIds: List<String>)

    data class Update(val bookUrl: String, val group: Long)

    fun selection(groups: List<BookGroup>, book: Book): Selection {
        val options = groups.filter { it.groupId > 0 }.sortedBy { it.order }
        return Selection(
            options.map { GroupOption(it.groupId.toString(), it.groupName) },
            options.filter { book.group and it.groupId > 0 }.map { it.groupId.toString() }
        )
    }

    fun parseUpdate(postData: String?, groups: List<BookGroup>): Update {
        val json = GSON.fromJsonObject<JsonObject>(postData).getOrThrow()
        val url = json.get("bookUrl")
        require(url?.isJsonPrimitive == true && url.asJsonPrimitive.isString &&
            url.asString.isNotBlank()) { "书籍地址不能为空" }
        val ids = json.get("groupIds")
        require(ids?.isJsonArray == true) { "分组列表格式不正确" }
        val availableIds = groups.filter { it.groupId > 0 }.map { it.groupId }.toSet()
        val mask = ids.asJsonArray.fold(0L) { mask, item ->
            require(item.isJsonPrimitive && item.asJsonPrimitive.isString) { "分组编号必须是字符串" }
            val id = item.asString.toLongOrNull()
            require(id != null && id > 0 && id and (id - 1) == 0L && id in availableIds) {
                "分组已失效，请重新加载后选择"
            }
            mask or id
        }
        return Update(url.asString, mask)
    }

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
