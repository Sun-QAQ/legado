package io.legado.app.ui.main.bookshelf.style2

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.AppCompatSpinner
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.book.group.GroupEditDialog
import io.legado.app.ui.main.MainActivity
import io.legado.app.ui.widget.image.CoverImageView
import io.legado.app.ui.widget.image.FolderCoverView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FolderCoverViewTest {
    @Test
    fun editDialogPreviewsSortChangesAndPreservesStoredCoverWhenSaving() {
        val originalStyle = AppConfig.bookGroupStyle
        val suffix = UUID.randomUUID().toString()
        val group = BookGroup(
            groupId = unusedGroupId(), groupName = "分组预览测试-$suffix",
            bookSort = 3, cover = "stored-cover"
        )
        val books = (1..2).map { index ->
            Book(
                bookUrl = "group-preview-test:$suffix:$index", name = "预览-$suffix-$index",
                group = group.groupId, type = BookType.text or BookType.local, canUpdate = false,
                coverUrl = "/missing-cover-$suffix-$index", order = index,
                durChapterTime = index.toLong()
            )
        }
        try {
            AppConfig.bookGroupStyle = 1
            appDb.bookGroupDao.insert(group)
            appDb.bookDao.insert(*books.toTypedArray())
            assertEquals(2, appDb.bookDao.getBooksByGroup(group.groupId).size)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity {
                    GroupEditDialog(group).showNow(it.supportFragmentManager, "folder-preview-edit")
                }
                fun awaitCover(path: String?) {
                    var lastState = "dialog not found"
                    repeat(200) {
                        var ready = false
                        scenario.onActivity { activity ->
                            val view = activity.supportFragmentManager
                                .findFragmentByTag("folder-preview-edit")?.view
                            val folder = view?.findViewById<FolderCoverView>(R.id.folder_cover)
                            ready = folder?.visibility == View.VISIBLE &&
                                (folder.getChildAt(0) as? CoverImageView)?.bitmapPath == path
                            lastState = "view=${view != null}, visibility=${folder?.visibility}, " +
                                "sort=${view?.findViewById<AppCompatSpinner>(R.id.sp_sort)?.selectedItemPosition}"
                        }
                        if (ready) return
                        SystemClock.sleep(50)
                    }
                    throw AssertionError("Group dialog did not update its folder preview: $lastState")
                }
                awaitCover(books.first().coverUrl)
                scenario.onActivity { activity ->
                    val view = activity.supportFragmentManager
                        .findFragmentByTag("folder-preview-edit")!!.requireView()
                    assertTrue(view.findViewById<View>(R.id.iv_cover).visibility != View.VISIBLE)
                    view.findViewById<AppCompatSpinner>(R.id.sp_sort).setSelection(1)
                }
                awaitCover(books.last().coverUrl)
                scenario.onActivity { activity ->
                    activity.supportFragmentManager.findFragmentByTag("folder-preview-edit")!!
                        .requireView().findViewById<View>(R.id.btn_ok).performClick()
                }
                val saveDeadline = SystemClock.uptimeMillis() + 5000
                while (appDb.bookGroupDao.getByID(group.groupId)?.bookSort != 0 &&
                    SystemClock.uptimeMillis() < saveDeadline
                ) {
                    SystemClock.sleep(20)
                }
                val saved = appDb.bookGroupDao.getByID(group.groupId)!!
                assertEquals(0, saved.bookSort)
                assertEquals("stored-cover", saved.cover)
            }
        } finally {
            appDb.bookDao.delete(*books.toTypedArray())
            appDb.bookGroupDao.delete(group)
            AppConfig.bookGroupStyle = originalStyle
        }
    }

    @Test
    fun foldersLoadAgainAfterRecreationWithoutAnotherBookDatabaseChange() {
        val originalStyle = AppConfig.bookGroupStyle
        val suffix = UUID.randomUUID().toString()
        val group = BookGroup(
            groupId = unusedGroupId(),
            groupName = "文件夹预览测试-$suffix",
            order = Int.MIN_VALUE
        )
        val book = Book(
            bookUrl = "folder-preview-test:$suffix", name = "预览测试-$suffix",
            group = group.groupId, type = BookType.text or BookType.local, canUpdate = false
        )
        try {
            AppConfig.bookGroupStyle = 1
            appDb.bookGroupDao.insert(group)
            appDb.bookDao.insert(book)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.findViewById<View>(R.id.menu_bookshelf).performClick() }
                awaitFolder(scenario, group.groupName)
                scenario.recreate()
                awaitFolder(scenario, group.groupName)
            }
        } finally {
            appDb.bookDao.delete(book)
            appDb.bookGroupDao.delete(group)
            AppConfig.bookGroupStyle = originalStyle
        }
    }

    @Test
    fun recycledFolderKeepsFixedSlotsAndClearsMissingCoversAtListAndGridSizes() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val folder = FolderCoverView(activity)
                activity.setContentView(folder)
                val covers = (1..4).map { FolderBookCover(null, "书籍 $it", "作者", "") }
                for (width in listOf(198, 540)) {
                    folder.load(covers)
                    folder.measure(
                        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                    )
                    folder.layout(0, 0, folder.measuredWidth, folder.measuredHeight)
                    assertEquals(width * 7 / 5, folder.height)
                    val first = folder.getChildAt(0)
                    val second = folder.getChildAt(1)
                    val third = folder.getChildAt(2)
                    val fourth = folder.getChildAt(3)
                    assertEquals(first.top, second.top)
                    assertEquals(first.left, third.left)
                    assertEquals(second.left, fourth.left)
                    assertTrue(first.right < second.left)
                    assertTrue(first.bottom < third.top)
                    assertTrue(fourth.right <= folder.width && fourth.bottom <= folder.height)
                    folder.load(covers.take(1))
                    assertEquals(View.VISIBLE, first.visibility)
                    for (index in 1..3) {
                        assertEquals(View.GONE, folder.getChildAt(index).visibility)
                        assertNull((folder.getChildAt(index) as android.widget.ImageView).drawable)
                    }
                    folder.load(emptyList())
                    assertTrue((0..3).all { folder.getChildAt(it).visibility == View.GONE })
                }
            }
        }
    }

    private fun awaitFolder(scenario: ActivityScenario<MainActivity>, name: String) {
        fun contains(view: View): Boolean {
            if (view is TextView && view.text.toString() == name) return true
            return view is ViewGroup && (0 until view.childCount).any { contains(view.getChildAt(it)) }
        }
        repeat(200) {
            var found = false
            scenario.onActivity { found = contains(it.findViewById(android.R.id.content)) }
            if (found) return
            SystemClock.sleep(50)
        }
        throw AssertionError("Folder did not load without a database update")
    }

    private fun unusedGroupId(): Long {
        val groupMask = appDb.bookGroupDao.all.filter { it.groupId > 0 }
            .fold(0L) { mask, group -> mask or group.groupId }
        val usedMask = appDb.bookDao.all.fold(groupMask) { mask, book -> mask or book.group }
        return (0..62).map { 1L shl it }.first { it and usedMask == 0L }
    }
}
