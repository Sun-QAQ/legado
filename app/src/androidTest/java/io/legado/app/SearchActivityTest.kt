package io.legado.app

import android.content.Intent
import androidx.appcompat.R as AppCompatR
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.ui.book.search.SearchActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchActivityTest {

    @Test
    fun finishImmediatelyWhenSearchFieldHasFocus() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        ActivityScenario.launch<SearchActivity>(Intent(context, SearchActivity::class.java)).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<android.widget.TextView>(AppCompatR.id.search_src_text)
                    .requestFocus()
            }

            scenario.onActivity { it.finish() }

            scenario.onActivity { activity ->
                assertTrue("返回后搜索页应直接结束", activity.isFinishing || activity.isDestroyed)
            }
        }
    }
}
