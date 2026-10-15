package io.legado.app.ui.book.manage

import android.content.Intent
import android.os.SystemClock
import android.view.View
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.asFlow
import androidx.room.Room
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BookshelfGroupFilterTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun legacyVideoGroupUsesBookTypeInsteadOfNegativeGroupMask() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val videoGroup = BookGroup(groupId = BookGroup.IdVideo, groupName = "视频")
            db.bookGroupDao.insert(videoGroup, BookGroup(groupId = 1, groupName = "视频"))
            db.bookDao.insert(
                book("network-video", BookType.video, 0),
                book("local-video", BookType.video or BookType.local, 1),
                book("text", BookType.text, 4),
                book("audio", BookType.audio, 8),
                book("temporary-video", BookType.video or BookType.notShelf, 1)
            )
            assertEquals(
                setOf("network-video", "local-video"),
                db.bookDao.flowByGroup(BookGroup.IdVideo).first().map { it.name }.toSet()
            )
            assertEquals(
                listOf("local-video"),
                db.bookDao.flowByGroup(1).first().map { it.name }
            )
            assertTrue(db.bookGroupDao.show.asFlow().first().any { it.groupId == BookGroup.IdVideo })
        } finally {
            db.close()
        }
    }

    @Test
    fun sameNameAndOrderMenuEntriesSelectTheirOwnGroupAndSearchWithinIt() {
        val name = "同名分组-${UUID.randomUUID()}"
        val firstGroup = BookGroup(appDb.bookGroupDao.getUnusedId(), name, order = 999)
        appDb.bookGroupDao.insert(firstGroup)
        val secondGroup = BookGroup(appDb.bookGroupDao.getUnusedId(), name, order = 999)
        val firstBook = book("$name-first", BookType.text, firstGroup.groupId)
        val secondBook = book("$name-second", BookType.video, secondGroup.groupId)
        try {
            appDb.bookGroupDao.insert(secondGroup)
            appDb.bookDao.insert(firstBook, secondBook)
            val intent = Intent(context, BookshelfManageActivity::class.java)
                .putExtra("groupId", BookGroup.IdAll)
            ActivityScenario.launch<BookshelfManageActivity>(intent).use { scenario ->
                awaitState(scenario) { activity ->
                    activity.groupList.any { it.groupId == secondGroup.groupId }
                }
                fun select(group: BookGroup) {
                    scenario.onActivity { activity ->
                        val menu = PopupMenu(activity, activity.findViewById<View>(R.id.title_bar)).menu
                        activity.onCompatCreateOptionsMenu(menu)
                        activity.onPrepareOptionsMenu(menu)
                        val subMenu = menu.findItem(R.id.menu_book_group).subMenu!!
                        val index = activity.groupList.indexOfFirst { it.groupId == group.groupId }
                        assertTrue(subMenu.performIdentifierAction(subMenu.getItem(index + 1).itemId, 0))
                    }
                }
                fun displayedNames(activity: BookshelfManageActivity) =
                    (activity.findViewById<RecyclerView>(R.id.recycler_view).adapter as BookAdapter)
                        .getItems().map { it.name }

                select(secondGroup)
                awaitState(scenario) { displayedNames(it) == listOf(secondBook.name) }
                scenario.onActivity { activity ->
                    activity.findViewById<SearchView>(R.id.search_view)
                        .setQuery("first", false)
                }
                awaitState(scenario) { displayedNames(it).isEmpty() }
                scenario.onActivity { activity ->
                    activity.findViewById<SearchView>(R.id.search_view)
                        .setQuery("", false)
                }
                select(firstGroup)
                awaitState(scenario) { displayedNames(it) == listOf(firstBook.name) }
            }
        } finally {
            appDb.bookDao.delete(firstBook, secondBook)
            appDb.bookGroupDao.delete(firstGroup, secondGroup)
        }
    }

    private fun book(name: String, type: Int, group: Long) = Book(
        bookUrl = "group-filter-test:$name", name = name, type = type, group = group,
        canUpdate = false
    )

    private fun awaitState(
        scenario: ActivityScenario<BookshelfManageActivity>,
        predicate: (BookshelfManageActivity) -> Boolean
    ) {
        repeat(200) {
            var ready = false
            scenario.onActivity { ready = predicate(it) }
            if (ready) return
            SystemClock.sleep(25)
        }
        throw AssertionError("Book management filter did not reach the expected state")
    }
}
