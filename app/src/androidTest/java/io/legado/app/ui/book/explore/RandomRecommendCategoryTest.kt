package io.legado.app.ui.book.explore

import android.view.ContextThemeWrapper
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.data.entities.rule.ExploreKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RandomRecommendCategoryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun headingsRemainInOrderAndCannotBeSelected() = onMain {
        val categories = listOf(
            ExploreKind("分组"),
            ExploreKind("分类一", "first"),
            ExploreKind("分组", ""),
            ExploreKind("空白地址", "  "),
            ExploreKind("分类二", "second")
        )
        val list = newList()
        val adapter = newAdapter(list, categories)
        list.adapter = adapter
        assertEquals(5, list.count)
        assertFalse(adapter.areAllItemsEnabled())
        assertEquals(listOf(false, true, false, false, true), categories.indices.map(adapter::isEnabled))
        assertEquals(categories.map { it.title }, categories.indices.map { adapter.getItem(it) })
    }

    @Test
    fun recycledHeadingRowsRestoreEnabledAppearanceForSelectableCategories() = onMain {
        val list = newList()
        val adapter = newAdapter(list, listOf(ExploreKind("标题"), ExploreKind("分类", "category")))
        val heading = adapter.getView(0, null, list)
        assertFalse(heading.isEnabled)
        assertEquals(0.5f, heading.alpha, 0f)
        val category = adapter.getView(1, heading, list)
        assertTrue(category.isEnabled)
        assertEquals(1f, category.alpha, 0f)
        assertEquals("分类", (category as TextView).text.toString())
        val headingAgain = adapter.getView(0, category, list)
        assertFalse(headingAgain.isEnabled)
        assertEquals("标题", (headingAgain as TextView).text.toString())
    }

    private fun newList() = ListView(ContextThemeWrapper(instrumentation.targetContext, R.style.AppTheme_Light))

    private fun newAdapter(list: ListView, categories: List<ExploreKind>): RandomRecommendCategoryAdapter {
        val wrapped = ArrayAdapter(list.context, android.R.layout.simple_list_item_multiple_choice,
            categories.map { it.title })
        return RandomRecommendCategoryAdapter(wrapped, categories)
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
}
