package io.legado.app.ui.main.bookshelf.style2

import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderCoverPreviewTest {
    private val group = BookGroup(groupId = 1, groupName = "分组", bookSort = 3)

    private fun book(index: Int, groupId: Long = 1, type: Int = BookType.text) = Book(
        bookUrl = "book-$index", name = "Book $index", author = "Author $index",
        coverUrl = "cover-$index", origin = "source", group = groupId, type = type,
        order = index, durChapterTime = index.toLong(), latestChapterTime = index.toLong()
    )

    @Test
    fun firstFourFollowGroupOrderAndIncludeBooksInMultipleGroups() {
        val members = listOf(book(6), book(2, 3), book(4), book(1), book(3), book(5))
        val books = members + book(0, 2) + book(-1, type = BookType.text or BookType.notShelf)
        val result = FolderCoverPreview.create(group, books, listOf(group), 0)
        assertEquals(listOf("Book 1", "Book 2", "Book 3", "Book 4"), result.covers.map { it.name })
    }

    @Test
    fun fewerThanFourBooksAreNotDuplicatedAndEmptyFolderStaysEmpty() {
        for (count in 0..3) {
            val result = FolderCoverPreview.create(group, (1..count).map { book(it) }, listOf(group), 0)
            assertEquals(count, result.covers.size)
        }
    }

    @Test
    fun builtInGroupsUseTheSameMembershipRulesAsTheirBookLists() {
        val books = listOf(
            book(1, 0), book(2, 2), book(3, 0, BookType.text or BookType.local),
            book(4, 0, BookType.audio), book(5, 0, BookType.text or BookType.updateError),
            book(6, 0, BookType.text or BookType.notShelf)
        )
        val groups = listOf(group, BookGroup(groupId = 2, show = false))
        fun names(id: Long) = FolderCoverPreview.create(
            BookGroup(groupId = id, bookSort = 3), books, groups, 0
        ).covers.map { it.name }
        assertEquals(listOf("Book 1", "Book 2", "Book 3", "Book 4"), names(BookGroup.IdAll))
        assertEquals(listOf("Book 3"), names(BookGroup.IdLocal))
        assertEquals(listOf("Book 4"), names(BookGroup.IdAudio))
        assertEquals(listOf("Book 5"), names(BookGroup.IdError))
        assertEquals(listOf("Book 1", "Book 5"), names(BookGroup.IdNetNone))
        assertEquals(listOf("Book 3"), names(BookGroup.IdLocalNone))
    }

    @Test
    fun videoFolderIncludesOnlyVideoBooksRegardlessOfUserGroup() {
        val books = listOf(
            book(1, 0, BookType.video), book(2, 1, BookType.video or BookType.local),
            book(3, 4), book(4, 2, BookType.audio),
            book(5, 1, BookType.video or BookType.notShelf)
        )
        val result = FolderCoverPreview.create(
            BookGroup(groupId = BookGroup.IdVideo, bookSort = 3), books, listOf(group), 0
        )
        assertEquals(listOf("Book 1", "Book 2"), result.covers.map { it.name })
    }

    @Test
    fun allSortModesAndInheritedSortChooseTheExpectedFirstBook() {
        val books = listOf(
            book(2).copy(name = "Beta", author = "Alpha", latestChapterTime = 30),
            book(1).copy(name = "Alpha", author = "Beta", durChapterTime = 40),
            book(3).copy(name = "Gamma", author = "Gamma", durChapterTime = 50)
        )
        val expected = listOf("Gamma", "Beta", "Alpha", "Alpha", "Gamma", "Beta")
        expected.forEachIndexed { mode, name ->
            assertEquals(name, FolderCoverPreview.create(
                group.copy(bookSort = mode), books, listOf(group), 0
            ).covers.first().name)
            assertEquals(name, FolderCoverPreview.create(
                group.copy(bookSort = -1), books, listOf(group), mode
            ).covers.first().name)
        }
    }

    @Test
    fun displayCoverUsesBookOverrideAndKeepsMissingCoverForDefaultRendering() {
        val books = listOf(book(1).copy(customCoverUrl = "custom"), book(2).copy(coverUrl = null))
        val result = FolderCoverPreview.create(group, books, listOf(group), 0)
        assertEquals("custom", result.covers.first().path)
        assertNull(result.covers[1].path)
        assertEquals("Book 2", result.covers[1].name)
    }

    @Test
    fun snapshotsDetectCoverAndOrderChangesWithoutMutatingEarlierItems() {
        val books = listOf(book(1), book(2))
        val before = FolderCoverPreview.create(group, books, listOf(group), 0)
        books.first().customCoverUrl = "new-cover"
        group.groupName = "新名称"
        val after = FolderCoverPreview.create(group, books, listOf(group), 0)
        assertNotEquals(before, after)
        assertEquals("分组", before.group.groupName)
        assertEquals("cover-1", before.covers.first().path)
        assertEquals("new-cover", after.covers.first().path)
        val reversed = FolderCoverPreview.create(group.copy(bookSort = 0), books, listOf(group), 0)
        assertNotEquals(after.covers, reversed.covers)
        assertTrue(reversed.covers.first().name == "Book 2")
    }
}
