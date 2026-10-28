package io.legado.app.ui.book.explore

import android.os.SystemClock
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.tabs.TabLayout
import io.legado.app.R
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.BookSourcePart
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RandomRecommendSourceMenuTest {
    @Test
    fun longPressUsesCurrentRowAndShowsLoginOnlyWhenSupported() {
        ActivityScenario.launch(RandomRecommendConfigActivity::class.java).use { scenario ->
            awaitActivity(scenario) { it.findViewById<TabLayout>(R.id.tab_group).tabCount > 0 }
            showMenu(scenario, BookSourcePart("first", "测试登录书源", hasLoginUrl = true))
            awaitPopupItem(R.string.login)
            awaitPopupItem(R.string.edit)
            onView(withText(android.R.string.cancel)).inRoot(isPlatformPopup()).check(doesNotExist())
            pressBack()

            showMenu(scenario, BookSourcePart("second", "测试普通书源"))
            awaitPopupItem(R.string.edit)
            onView(withText(R.string.login)).inRoot(isPlatformPopup()).check(doesNotExist())
            pressBack()
        }
    }

    private fun awaitPopupItem(title: Int) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        var lastFailure: AssertionError? = null
        while (SystemClock.uptimeMillis() < deadline) {
            try {
                onView(withText(title)).inRoot(isPlatformPopup()).check(matches(isDisplayed()))
                return
            } catch (failure: AssertionError) {
                lastFailure = failure
            }
            SystemClock.sleep(50)
        }
        throw lastFailure ?: AssertionError("Source popup did not become visible")
    }

    private fun showMenu(scenario: ActivityScenario<RandomRecommendConfigActivity>, source: BookSourcePart) {
        scenario.onActivity { activity ->
            // Populate only the view adapter; never persist synthetic sources or change selections.
            @Suppress("UNCHECKED_CAST")
            val adapter = activity.findViewById<RecyclerView>(R.id.recycler_view).adapter
                as RecyclerAdapter<BookSourcePart, *>
            adapter.setItems(listOf(source))
        }
        awaitActivity(scenario) { activity ->
            activity.findViewById<RecyclerView>(R.id.recycler_view).findViewHolderForAdapterPosition(0)
                ?.itemView?.findViewById<TextView>(R.id.tv_name)?.text == source.bookSourceName
        }
        scenario.onActivity { activity ->
            val row = activity.findViewById<RecyclerView>(R.id.recycler_view)
                .findViewHolderForAdapterPosition(0)!!.itemView
            assertTrue(row.performLongClick())
        }
    }

    private fun awaitActivity(
        scenario: ActivityScenario<RandomRecommendConfigActivity>,
        condition: (RandomRecommendConfigActivity) -> Boolean
    ) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            scenario.onActivity { ready = condition(it) }
            if (ready) return
            SystemClock.sleep(50)
        }
        error("Configuration source row did not become ready")
    }
}
