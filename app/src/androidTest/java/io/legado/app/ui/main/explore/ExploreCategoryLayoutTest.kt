package io.legado.app.ui.main.explore

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayout
import io.legado.app.data.entities.rule.FlexChildStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExploreCategoryLayoutTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun defaultCategoriesKeepThreeColumnsAcrossPixelWidths() = onMain {
        val layout = newLayout()
        repeat(7) { addCategory(layout) }
        // 连续宽度覆盖除以三的所有余数，以及不同屏幕密度对应的像素宽度。
        for (width in 240..1600) {
            measureAndLayout(layout, width)
            val first = layout.getChildAt(0)
            assertEquals("width=$width", first.top, layout.getChildAt(2).top)
            assertTrue("width=$width", layout.getChildAt(3).top > first.top)
            assertEquals(layout.getChildAt(3).top, layout.getChildAt(5).top)
            assertTrue(layout.getChildAt(6).top > layout.getChildAt(5).top)
            assertTrue(layout.getChildAt(2).right <= width)
            assertEquals(first.width, layout.getChildAt(6).width)
        }
    }

    @Test
    fun sectionHeadersKeepFullRowsAndPartialRowsKeepColumnWidth() = onMain {
        val layout = newLayout()
        val heading = FlexChildStyle(layout_flexBasisPercent = 1f, layout_flexGrow = 1f)
        addCategory(layout, heading)
        repeat(4) { addCategory(layout) }
        addCategory(layout, heading)
        repeat(3) { addCategory(layout) }
        measureAndLayout(layout, 1001)
        assertEquals(1001, layout.getChildAt(0).width)
        assertEquals(1001, layout.getChildAt(5).width)
        assertTrue(layout.getChildAt(1).top > layout.getChildAt(0).top)
        assertEquals(layout.getChildAt(1).top, layout.getChildAt(3).top)
        assertEquals(layout.getChildAt(1).width, layout.getChildAt(4).width)
        assertTrue(layout.getChildAt(5).top > layout.getChildAt(4).top)
        assertTrue(layout.getChildAt(6).top > layout.getChildAt(5).top)
        assertEquals(layout.getChildAt(6).top, layout.getChildAt(8).top)
    }

    @Test
    fun customWidthsAndGrowRemainEffective() = onMain {
        val layout = newLayout()
        val half = FlexChildStyle(layout_flexBasisPercent = 0.5f)
        addCategory(layout, half)
        addCategory(layout, half)
        addCategory(layout, FlexChildStyle(layout_wrapBefore = true, layout_flexGrow = 1f))
        measureAndLayout(layout, 1000)
        assertEquals(500, layout.getChildAt(0).width)
        assertEquals(500, layout.getChildAt(1).width)
        assertEquals(layout.getChildAt(0).top, layout.getChildAt(1).top)
        assertTrue(layout.getChildAt(2).top > layout.getChildAt(1).top)
        assertEquals(1000, layout.getChildAt(2).width)
    }

    @Test
    fun wrapBeforeStartsANewRowEvenWhenThereIsSpace() = onMain {
        val layout = newLayout()
        addCategory(layout)
        addCategory(layout, FlexChildStyle(layout_wrapBefore = true))
        addCategory(layout)
        measureAndLayout(layout, 1001)
        assertTrue(layout.getChildAt(1).top > layout.getChildAt(0).top)
        assertEquals(layout.getChildAt(1).top, layout.getChildAt(2).top)
    }

    @Test
    fun remeasurementUsesCurrentWidthAndPadding() = onMain {
        val layout = newLayout().apply { setPadding(11, 0, 14, 0) }
        repeat(4) { addCategory(layout) }
        for (width in listOf(1001, 701, 1272, 1000)) {
            measureAndLayout(layout, width)
            assertEquals((width - 25) / 3, layout.getChildAt(0).width)
            assertEquals(layout.getChildAt(0).top, layout.getChildAt(2).top)
            assertTrue(layout.getChildAt(2).right <= width - 14)
            assertTrue(layout.getChildAt(3).top > layout.getChildAt(2).top)
        }
    }

    private fun newLayout() = ExploreCategoryLayout(instrumentation.targetContext).apply {
        flexWrap = FlexWrap.WRAP
    }

    private fun addCategory(layout: ExploreCategoryLayout, style: FlexChildStyle = FlexChildStyle()) {
        val view = TextView(layout.context).apply {
            text = "巅峰榜(男女合频)"
            maxLines = 1
            minHeight = 48
            layoutParams = if (style.layout_flexBasisPercent < 0f) {
                ExploreCategoryLayout.DefaultColumnLayoutParams()
            } else {
                FlexboxLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        }
        style.apply(view)
        layout.addView(view)
    }

    private fun measureAndLayout(layout: ExploreCategoryLayout, width: Int) {
        layout.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        layout.layout(0, 0, width, layout.measuredHeight)
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
}
