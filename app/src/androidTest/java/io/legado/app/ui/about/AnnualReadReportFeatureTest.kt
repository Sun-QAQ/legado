package io.legado.app.ui.about

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.data.entities.ReadStat
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.lib.theme.ThemePalette
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AnnualReadReportFeatureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun fixture(): AnnualReadReport {
        val year = LocalDate.now().year - 1
        return AnnualReadReport.create(year, LocalDate.of(year, 12, 15), listOf(
            ReadStat("山海之间", LocalDate.of(year, 1, 1).toEpochDay(), 3_600_000),
            ReadStat("长夜与星光", LocalDate.of(year, 6, 2).toEpochDay(), 7_200_000),
            ReadStat("长夜与星光", LocalDate.of(year, 6, 3).toEpochDay(), 1_800_000),
            ReadStat("山海之间", LocalDate.of(year, 12, 15).toEpochDay(), 900_000),
        ))
    }

    @Test
    fun snapshotsSurviveNewStoreInstancesAndRemainAvailableNextYear() {
        val context = instrumentation.targetContext
        val root = File(context.cacheDir, "annual-report-test-${UUID.randomUUID()}")
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir(): File = root
        }
        try {
            val report = fixture()
            AnnualReadReportStore(isolated).save(report)
            val reopened = AnnualReadReportStore(isolated)
            assertTrue(reopened.hasReport(report.year))
            assertEquals(report, reopened.load(report.year))
            assertTrue(report.year in reopened.years(LocalDate.of(report.year + 1, 1, 1)))
            assertFalse(report.year in reopened.years(LocalDate.of(report.year, 11, 30)))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun generationRecoversFromAnUnreadableSnapshot() {
        val context = instrumentation.targetContext
        val root = File(context.cacheDir, "annual-report-test-${UUID.randomUUID()}")
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir(): File = root
        }
        try {
            val store = AnnualReadReportStore(isolated)
            val today = LocalDate.now()
            val year = today.year - 1
            val generated = store.generate(year, today)
            assertEquals(generated, store.load(year))
            File(root, "annual_read_reports/$year.json").writeText("{broken")
            assertNull(store.load(year))
            assertEquals(year, store.generate(year, today).year)
            assertNotNull(AnnualReadReportStore(isolated).load(year))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun readingRecordsToolbarOpensTheReportAndReturns() {
        ActivityScenario.launch(ReadRecordActivity::class.java).use {
            onView(withId(R.id.menu_annual_report)).perform(click())
            onView(withId(R.id.content)).check(matches(isDisplayed()))
            pressBack()
            onView(withId(R.id.recycler_view)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun reportAndEmptyStateRenderInBothThemesWithoutChangingReadingHistory() {
        val original = AppConfig.isNightTheme
        try {
            for (night in listOf(false, true)) {
                instrumentation.runOnMainSync {
                    AppConfig.isNightTheme = night
                    ThemeConfig.applyDayNight(instrumentation.targetContext)
                }
                ActivityScenario.launch(AnnualReadReportActivity::class.java).use { scenario ->
                    awaitReport(scenario)
                    scenario.onActivity { activity -> activity.renderReport(fixture()) }
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { activity ->
                        val content = activity.findViewById<ViewGroup>(R.id.content)
                        val text = texts(content).joinToString("\n")
                        assertTrue(text.contains("山海之间"))
                        assertTrue(text.contains("长夜与星光"))
                        assertTrue(text.contains("${fixture().year}-12-15"))
                        assertTrue(content.height > 0)
                        val image = Bitmap.createBitmap(content.width, content.height, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(image)
                        canvas.drawColor(ThemePalette(activity).background)
                        content.draw(canvas)
                        File(activity.externalCacheDir, "annual-report-${if (night) "dark" else "light"}.png")
                            .outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        image.recycle()
                        activity.renderReport(AnnualReadReport.create(fixture().year,
                            LocalDate.now(), emptyList()))
                        assertTrue(texts(content).contains(activity.getString(R.string.annual_report_empty_title)))
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

    private fun awaitReport(scenario: ActivityScenario<AnnualReadReportActivity>) {
        repeat(100) {
            var ready = false
            scenario.onActivity { ready = !it.findViewById<View>(R.id.progress).isVisible }
            if (ready) return
            Thread.sleep(50)
        }
        fail("Report did not finish loading")
    }
}
