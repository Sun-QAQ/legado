package io.legado.app.ui.widget.image

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.model.BookCover
import io.legado.app.ui.main.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoverImageViewTest {
    private fun withCover(block: (CoverImageView) -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val nameFlag = BookCover::class.java.getDeclaredField("drawBookName")
                    .apply { isAccessible = true }
                val authorFlag = BookCover::class.java.getDeclaredField("drawBookAuthor")
                    .apply { isAccessible = true }
                val originalName = BookCover.drawBookName
                val originalAuthor = BookCover.drawBookAuthor
                try {
                    nameFlag.setBoolean(BookCover, true)
                    authorFlag.setBoolean(BookCover, true)
                    val cover = CoverImageView(activity, filletRadius = 0f)
                    CoverImageView::class.java.getDeclaredField("name")
                        .apply { isAccessible = true }.set(cover, "测试书名")
                    CoverImageView::class.java.getDeclaredField("author")
                        .apply { isAccessible = true }.set(cover, "作者")
                    cover.setImageDrawable(ColorDrawable(Color.WHITE))
                    cover.measure(View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                    cover.layout(0, 0, cover.measuredWidth, cover.measuredHeight)
                    block(cover)
                } finally {
                    nameFlag.setBoolean(BookCover, originalName)
                    authorFlag.setBoolean(BookCover, originalAuthor)
                }
            }
        }
    }

    private fun assertVisibleText(cover: CoverImageView, background: Int, text: Int) {
        val bitmap = Bitmap.createBitmap(cover.width, cover.height, Bitmap.Config.ARGB_8888)
        try {
            cover.draw(Canvas(bitmap))
            assertEquals("封面底色", background, bitmap.getPixel(cover.width / 2, cover.height / 2))
            var namePixels = 0
            var authorPixels = 0
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                if (bitmap.getPixel(x, y) == text) {
                    if (x < bitmap.width / 2) namePixels++ else authorPixels++
                }
            }
            assertTrue("书名必须与封面底色有对比", namePixels > 0)
            assertTrue("作者必须与封面底色有对比", authorPixels > 0)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun defaultCoverTextRemainsVisibleForInkLightAndDarkTints() {
        withCover { cover ->
            for ((background, text) in listOf(
                Color.BLACK to Color.WHITE,
                Color.WHITE to Color.BLACK,
                Color.rgb(13, 27, 42) to Color.WHITE,
                Color.rgb(238, 238, 238) to Color.BLACK,
                Color.rgb(229, 57, 53) to Color.BLACK
            )) {
                cover.imageTintList = ColorStateList.valueOf(background)
                assertVisibleText(cover, background, text)
            }
        }
    }

    @Test
    fun statefulTintChangesBothTitleAndAuthorTextColors() {
        withCover { cover ->
            cover.imageTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()),
                intArrayOf(Color.BLACK, Color.WHITE)
            )
            assertVisibleText(cover, Color.WHITE, Color.BLACK)
            cover.isSelected = true
            assertVisibleText(cover, Color.BLACK, Color.WHITE)
            cover.isSelected = false
            assertVisibleText(cover, Color.WHITE, Color.BLACK)
        }
    }
}
