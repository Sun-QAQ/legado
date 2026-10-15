package io.legado.app.ui.main.bookshelf.style2

import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.ui.main.MainActivity
import io.legado.app.ui.widget.image.FolderCoverView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FolderCoverViewTest {
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
}
