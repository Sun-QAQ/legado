package io.legado.app.api.controller

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.model.AudioPlay
import io.legado.app.model.ReadBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class BookControllerGroupsTest {

    @Test
    fun savedGroupsSurviveNativeProgressSaveWithoutReplacingReadingState() = withBooks { book, group ->
        val reading = book.copy(durChapterPos = 123, customIntro = "尚未保存的简介")
        ReadBook.book = reading
        ReadBook.durChapterIndex = 3
        ReadBook.durChapterPos = 456
        val playing = book.copy(durChapterPos = 789)
        AudioPlay.book = playing

        saveGroups(book, listOf(group.groupId))

        assertSame(reading, ReadBook.book)
        assertSame(playing, AudioPlay.book)
        assertEquals(group.groupId, reading.group)
        assertEquals(group.groupId, playing.group)
        assertEquals(123, reading.durChapterPos)
        assertEquals(789, playing.durChapterPos)
        assertEquals("尚未保存的简介", reading.customIntro)
        ReadBook.saveRead(pageChanged = true)
        awaitReadSave()
        val saved = requireNotNull(appDb.bookDao.getBook(book.bookUrl))
        assertEquals(group.groupId, saved.group)
        assertEquals(3, saved.durChapterIndex)
        assertEquals(456, saved.durChapterPos)
    }

    @Test
    fun clearingGroupsSurvivesNativeProgressSave() = withBooks { book, group ->
        appDb.bookDao.updateBookGroup(book.bookUrl, group.groupId)
        ReadBook.book = book.copy(group = group.groupId)
        AudioPlay.book = book.copy(group = group.groupId)

        saveGroups(book, emptyList())

        assertEquals(0L, ReadBook.book!!.group)
        assertEquals(0L, AudioPlay.book!!.group)
        ReadBook.saveRead(pageChanged = true)
        awaitReadSave()
        assertEquals(0L, appDb.bookDao.getBook(book.bookUrl)!!.group)
    }

    @Test
    fun savingAnotherBookDoesNotChangeActiveReadingOrAudioGroups() = withBooks { book, group ->
        val reading = book.copy(bookUrl = "${book.bookUrl}-other", group = 0)
        val playing = reading.copy()
        ReadBook.book = reading
        AudioPlay.book = playing

        saveGroups(book, listOf(group.groupId))

        assertEquals(group.groupId, appDb.bookDao.getBook(book.bookUrl)!!.group)
        assertEquals(0L, reading.group)
        assertEquals(0L, playing.group)
    }

    @Test
    fun rejectedSaveDoesNotChangeDatabaseOrActiveGroups() = withBooks { book, group ->
        appDb.bookDao.updateBookGroup(book.bookUrl, group.groupId)
        ReadBook.book = book.copy(group = group.groupId)
        AudioPlay.book = book.copy(group = group.groupId)

        val result = BookController.saveBookGroups(
            """{"bookUrl":"${book.bookUrl}","groupIds":["-1"]}"""
        )

        assertTrue(!result.isSuccess)
        assertEquals(group.groupId, appDb.bookDao.getBook(book.bookUrl)!!.group)
        assertEquals(group.groupId, ReadBook.book!!.group)
        assertEquals(group.groupId, AudioPlay.book!!.group)
    }

    private fun saveGroups(book: Book, ids: List<Long>) {
        val groupIds = ids.joinToString(",") { "\"$it\"" }
        val result = BookController.saveBookGroups(
            """{"bookUrl":"${book.bookUrl}","groupIds":[$groupIds]}"""
        )
        assertTrue(result.errorMsg, result.isSuccess)
    }

    private fun awaitReadSave() {
        ReadBook.executor.submit {}.get(10, TimeUnit.SECONDS)
    }

    private fun withBooks(test: (Book, BookGroup) -> Unit) {
        awaitReadSave()
        val previousBook = ReadBook.book
        val previousAudioBook = AudioPlay.book
        val previousIndex = ReadBook.durChapterIndex
        val previousPos = ReadBook.durChapterPos
        val id = UUID.randomUUID().toString()
        val book = Book(bookUrl = "group-save-test:$id", name = id, canUpdate = false)
        val group = BookGroup(appDb.bookGroupDao.getUnusedId(), "group-save-test:$id")
        try {
            appDb.bookGroupDao.insert(group)
            appDb.bookDao.insert(book)
            ReadBook.durChapterIndex = 0
            ReadBook.durChapterPos = 0
            test(book, group)
        } finally {
            awaitReadSave()
            ReadBook.book = previousBook
            AudioPlay.book = previousAudioBook
            ReadBook.durChapterIndex = previousIndex
            ReadBook.durChapterPos = previousPos
            appDb.bookDao.delete(book)
            appDb.bookGroupDao.delete(group)
        }
    }
}
