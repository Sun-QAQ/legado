package io.legado.app.ui.about

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.data.entities.ReadSession
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.globalExecutor
import io.legado.app.lib.theme.ThemePalette
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class ReadTimelineViewTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun readingRecordsEntryNavigatesDatesAndReturns() {
        ActivityScenario.launch(ReadRecordActivity::class.java).use {
            onView(withId(R.id.read_timeline)).perform(click())
            onView(withId(R.id.select_date)).check(matches(withText(LocalDate.now().toString())))
            onView(withId(R.id.previous_day)).perform(click())
            onView(withId(R.id.select_date)).check(matches(withText(LocalDate.now().minusDays(1).toString())))
            onView(withId(R.id.menu_timeline_today)).perform(click())
            onView(withId(R.id.select_date)).check(matches(withText(LocalDate.now().toString())))
            onView(withId(R.id.select_date)).perform(click())
            onView(withText(android.R.string.cancel)).perform(click())
            pressBack()
            onView(withId(R.id.read_timeline)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun intervalsAndEmptyStateRenderInBothThemesWithoutWritingSampleHistory() {
        val original = AppConfig.isNightTheme
        try {
            for (night in listOf(false, true)) {
                instrumentation.runOnMainSync {
                    AppConfig.isNightTheme = night
                    ThemeConfig.applyDayNight(instrumentation.targetContext)
                }
                ActivityScenario.launch(ReadTimelineActivity::class.java).use { scenario ->
                    globalExecutor.submit {}.get()
                    for (attempt in 0 until 100) {
                        var loaded = false
                        scenario.onActivity { activity ->
                            loaded = activity.findViewById<TextView>(R.id.summary).text.isNotEmpty()
                        }
                        if (loaded) break
                        Thread.sleep(20)
                    }
                    val date = LocalDate.now()
                    val zone = ZoneId.systemDefault()
                    val start = date.atTime(20, 10).atZone(zone).toInstant().toEpochMilli()
                    val sessions = listOf(
                        ReadSession("view-only-a", "fixture", "local:a", "长夜与星光", "示例作者",
                            date.toEpochDay(), start, start + 2_400_000, 2_400_000, zone.id),
                        ReadSession("view-only-b", "fixture", "local:b", "山海之间", "示例作者",
                            date.toEpochDay(), start - 3_600_000, start - 1_800_000, 1_800_000, zone.id),
                    )
                    scenario.onActivity { it.renderSessions(sessions) }
                    for (attempt in 0 until 50) {
                        instrumentation.waitForIdleSync()
                        var visible = false
                        scenario.onActivity { activity ->
                            visible = texts(activity.findViewById(R.id.recycler_view)).contains("长夜与星光")
                        }
                        if (visible) break
                        Thread.sleep(20)
                    }
                    scenario.onActivity { activity ->
                        val root = activity.findViewById<ViewGroup>(android.R.id.content)
                        val text = texts(root).joinToString("\n")
                        assertTrue(text.contains("长夜与星光"))
                        assertTrue(text.contains("20:10:00"))
                        assertTrue(text.contains("20:50:00"))
                        val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(image)
                        canvas.drawColor(ThemePalette(activity).background)
                        root.draw(canvas)
                        File(activity.externalCacheDir, "read-timeline-${if (night) "dark" else "light"}.png")
                            .outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        image.recycle()
                        activity.renderSessions(emptyList())
                        assertTrue(activity.findViewById<View>(R.id.empty).visibility == View.VISIBLE)
                    }
                }
            }
        } finally {
            instrumentation.runOnMainSync {
                AppConfig.isNightTheme = original
                ThemeConfig.applyDayNight(instrumentation.targetContext)
            }
        }
    }

    private fun texts(view: View): List<String> = when (view) {
        is TextView -> listOf(view.text.toString())
        is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
    }
}
