package io.legado.app.api.controller

import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.utils.GSON
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class BookshelfGroupsTest {
    private fun book(url: String, group: Long = 0, type: Int = BookType.text) =
        Book(bookUrl = url, name = url, author = "author", group = group, type = type)

    @Test
    fun visibleGroupsKeepAppNamesAndOrderIncludingEmptyGroups() {
        val groups = listOf(
            BookGroup(1, "稍后阅读", order = 2),
            BookGroup(2, "隐藏", order = 0, show = false),
            BookGroup(4, "收藏", order = 1)
        )
        val result = BookshelfGroups.create(groups, emptyList())
        assertEquals(listOf("收藏", "稍后阅读"), result.map { it.groupName })
        assertTrue(result.all { it.bookUrls.isEmpty() })
    }

    @Test
    fun membershipKeepsMultipleGroupsAndLongIdsWithoutJavascriptPrecisionLoss() {
        val highId = 1L shl 60
        val groups = listOf(BookGroup(1, "普通"), BookGroup(highId, "高位"))
        val result = BookshelfGroups.create(groups, listOf(
            book("both", highId or 1), book("high", highId), book("other", 2),
            book("off-shelf", highId, BookType.text or BookType.notShelf)
        ))
        assertEquals(listOf("both"), result[0].bookUrls)
        assertEquals(listOf("both", "high"), result[1].bookUrls)
        assertEquals(highId.toString(), result[1].groupId)
        val jsonId = GSON.toJsonTree(result[1]).asJsonObject.getAsJsonPrimitive("groupId")
        assertTrue(jsonId.isString)
        assertEquals(highId.toString(), jsonId.asString)
    }

    @Test
    fun builtInGroupsMatchAppMembershipAndHiddenGroupsStillCountAsAssigned() {
        val ids = listOf(
            BookGroup.IdAll, BookGroup.IdLocal, BookGroup.IdAudio, BookGroup.IdVideo,
            BookGroup.IdError, BookGroup.IdNetNone, BookGroup.IdLocalNone
        )
        val groups = ids.map { BookGroup(it, it.toString()) } +
            BookGroup(1, "隐藏", show = false)
        val books = listOf(
            book("net"), book("assigned", 1), book("deleted-group", 2),
            book("local", type = BookType.text or BookType.local),
            book("local-assigned", 1, BookType.text or BookType.local),
            book("audio", type = BookType.audio), book("video", type = BookType.video),
            book("error", type = BookType.text or BookType.updateError),
            book("off-shelf", type = BookType.text or BookType.notShelf)
        )
        val result = BookshelfGroups.create(groups, books).associateBy { it.groupId }
        fun urls(id: Long) = result.getValue(id.toString()).bookUrls
        assertEquals(8, urls(BookGroup.IdAll).size)
        assertEquals(listOf("local", "local-assigned"), urls(BookGroup.IdLocal))
        assertEquals(listOf("audio"), urls(BookGroup.IdAudio))
        assertEquals(listOf("video"), urls(BookGroup.IdVideo))
        assertEquals(listOf("error"), urls(BookGroup.IdError))
        assertEquals(listOf("net", "deleted-group", "video", "error"), urls(BookGroup.IdNetNone))
        assertEquals(listOf("local"), urls(BookGroup.IdLocalNone))
    }

    @Test
    fun detailSelectionIncludesHiddenCustomGroupsAndKeepsLongIdsAsStrings() {
        val highId = 1L shl 60
        val result = BookshelfGroups.selection(listOf(
            BookGroup(1, "待读", order = 2),
            BookGroup(highId, "隐藏收藏", order = 1, show = false),
            BookGroup(BookGroup.IdAll, "全部"), BookGroup(BookGroup.IdLocal, "本地")
        ), book("book", highId or 1))
        assertEquals(listOf("隐藏收藏", "待读"), result.groups.map { it.groupName })
        assertEquals(listOf(highId.toString(), "1"), result.selectedGroupIds)
    }

    @Test
    fun saveSelectionSupportsMultipleGroupsDuplicatesAndClearingAllGroups() {
        val highId = 1L shl 60
        val groups = listOf(BookGroup(1), BookGroup(highId, show = false))
        val update = BookshelfGroups.parseUpdate(
            """{"bookUrl":"book","groupIds":["1","$highId","1"]}""", groups
        )
        assertEquals("book", update.bookUrl)
        assertEquals(highId or 1, update.group)
        assertEquals(0L, BookshelfGroups.parseUpdate(
            """{"bookUrl":"book","groupIds":[]}""", groups
        ).group)
    }

    @Test
    fun saveSelectionRejectsBuiltInDeletedMalformedAndNumericGroupIds() {
        val groups = listOf(BookGroup(1), BookGroup(3))
        for (ids in listOf("[\"-1\"]", "[\"2\"]", "[\"3\"]", "[1]", "[null]",
            "[\"invalid\"]", "[\"9223372036854775808\"]", "[\"0\"]")) {
            assertThrows(IllegalArgumentException::class.java) {
                BookshelfGroups.parseUpdate("""{"bookUrl":"book","groupIds":$ids}""", groups)
            }
        }
    }

    @Test
    fun saveSelectionRejectsMissingOrInvalidFieldsInsteadOfClearingMembership() {
        for (json in listOf("{}", """{"bookUrl":"book"}""",
            """{"bookUrl":"","groupIds":[]}""", """{"bookUrl":12,"groupIds":[]}""",
            """{"bookUrl":"book","groupIds":null}""", """{"bookUrl":"book","groupIds":{}}""")) {
            assertThrows(IllegalArgumentException::class.java) {
                BookshelfGroups.parseUpdate(json, listOf(BookGroup(1)))
            }
        }
    }
}
