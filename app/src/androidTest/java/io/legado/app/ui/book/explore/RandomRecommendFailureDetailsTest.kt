package io.legado.app.ui.book.explore

import android.view.View
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.R
import kotlinx.coroutines.cancelChildren
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RandomRecommendFailureDetailsTest {
    @Test
    fun failurePreviewOpensCompleteListAndClearsAfterSuccess() {
        val failures = (1..8).map { "测试书源 $it · 测试分类 $it" }
        ActivityScenario.launch(RandomRecommendActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[RandomRecommendViewModel::class.java]
                // Render synthetic results without changing any source or saved configuration.
                model.viewModelScope.coroutineContext.cancelChildren()
                model.state.value = RandomRecommendViewModel.State(
                    message = activity.getString(R.string.random_recommend_partial, 0, failures.size),
                    failedCategories = failures
                )
                val status = activity.findViewById<TextView>(R.id.tv_status)
                assertTrue(status.text.contains(failures.first()))
                assertFalse(status.text.contains(failures.last()))
                assertEquals(6, status.maxLines)
                val details = activity.findViewById<TextView>(R.id.tv_failed_categories)
                assertEquals(View.VISIBLE, details.visibility)
                assertTrue(details.performClick())
            }
            onView(withText(R.string.random_recommend_failed_categories)).check(matches(isDisplayed()))
            onView(withText(failures.joinToString("\n\n"))).check(matches(isDisplayed()))
            onView(withText(android.R.string.ok)).perform(click())
            scenario.onActivity { activity ->
                ViewModelProvider(activity)[RandomRecommendViewModel::class.java].state.value =
                    RandomRecommendViewModel.State()
                assertEquals(View.GONE, activity.findViewById<View>(R.id.tv_status).visibility)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.tv_failed_categories).visibility)
            }
        }
    }
}
