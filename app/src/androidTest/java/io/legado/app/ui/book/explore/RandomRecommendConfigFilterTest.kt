package io.legado.app.ui.book.explore

import android.os.SystemClock
import android.widget.TextView
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.tabs.TabLayout
import io.legado.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the current source list without changing sources or saved recommendation selections. */
@RunWith(AndroidJUnit4::class)
class RandomRecommendConfigFilterTest {
    @Test
    fun searchAndGroupSurviveRecreationAndClearingFiltersRestoresSources() {
        ActivityScenario.launch(RandomRecommendConfigActivity::class.java).use { scenario ->
            awaitGroups(scenario)
            var initialCount = 0
            var selectedGroup: String? = null
            val query = "__no_source_${System.nanoTime()}__"
            scenario.onActivity { activity ->
                val list = activity.findViewById<RecyclerView>(R.id.recycler_view)
                initialCount = list.adapter!!.itemCount
                val tabs = activity.findViewById<TabLayout>(R.id.tab_group)
                if (tabs.tabCount > 1) {
                    tabs.getTabAt(1)!!.select()
                    selectedGroup = tabs.getTabAt(1)!!.tag as? String
                }
                activity.findViewById<SearchView>(R.id.search_view).setQuery(query, false)
                assertEquals(0, list.adapter!!.itemCount)
                if (initialCount > 0) {
                    assertEquals(activity.getString(R.string.random_recommend_no_matching_sources),
                        activity.findViewById<TextView>(R.id.tv_hint).text.toString())
                }
            }
            scenario.recreate()
            awaitGroups(scenario)
            scenario.onActivity { activity ->
                val search = activity.findViewById<SearchView>(R.id.search_view)
                val tabs = activity.findViewById<TabLayout>(R.id.tab_group)
                val list = activity.findViewById<RecyclerView>(R.id.recycler_view)
                assertEquals(query, search.query.toString())
                assertEquals(selectedGroup, tabs.getTabAt(tabs.selectedTabPosition)!!.tag)
                assertEquals(0, list.adapter!!.itemCount)
                search.setQuery("", false)
                tabs.getTabAt(0)!!.select()
                assertEquals(initialCount, list.adapter!!.itemCount)
                assertTrue(search.width > 0)
                assertTrue(tabs.width > 0)
            }
        }
    }

    private fun awaitGroups(scenario: ActivityScenario<RandomRecommendConfigActivity>) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            var ready = false
            scenario.onActivity { ready = it.findViewById<TabLayout>(R.id.tab_group).tabCount > 0 }
            if (ready) return
            SystemClock.sleep(50)
        }
        error("Source groups did not load")
    }
}
