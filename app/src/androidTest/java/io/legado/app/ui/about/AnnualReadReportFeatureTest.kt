package io.legado.app.ui.about

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.os.ConfigurationCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.data.entities.ReadStat
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.lib.theme.ThemePalette
import io.legado.app.lib.theme.ThemeStore
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
    fun invitationRequestedBeforeResumeAppearsOnceWhenTheActivityResumes() {
        val key = "annualReadReportPromptYear"
        val originalYear = LocalConfig.getInt(key, 0)
        val hadYear = LocalConfig.contains(key)
        val originalPrivacy = LocalConfig.privacyPolicyOk
        val december = LocalDate.of(LocalDate.now().year + 100, 12, 1)
        val context = instrumentation.targetContext
        val message = context.getString(R.string.annual_report_prompt, december.year)
        try {
            LocalConfig.edit().remove(key).commit()
            LocalConfig.privacyPolicyOk = true
            ActivityScenario.launch(ReadRecordActivity::class.java).use { scenario ->
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.onActivity { activity ->
                    AnnualReadReportPrompt.showIfNeeded(activity) { december }
                    assertNotEquals(december.year, LocalConfig.getInt(key, 0))
                }
                scenario.moveToState(Lifecycle.State.RESUMED)
                onView(withText(message)).check(matches(isDisplayed()))
                onView(withText(R.string.annual_report_later)).perform(click())
                scenario.onActivity { AnnualReadReportPrompt.showIfNeeded(it) { december } }
                onView(withText(message)).check(doesNotExist())
                assertEquals(december.year, LocalConfig.getInt(key, 0))
            }
        } finally {
            LocalConfig.edit().apply {
                if (hadYear) putInt(key, originalYear) else remove(key)
            }.commit()
            LocalConfig.privacyPolicyOk = originalPrivacy
        }
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
            onView(withId(R.id.title_bar)).check(matches(isDisplayed()))
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
                        val pager = activity.findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.report_pager)
                        assertEquals(4, pager.adapter?.itemCount)
                        assertEquals(0, pager.currentItem)
                        assertEquals("1 / 4", activity.findViewById<TextView>(R.id.page_indicator).text.toString())
                        pager.setCurrentItem(1, false)
                        assertEquals(1, pager.currentItem)
                        assertEquals("2 / 4", activity.findViewById<TextView>(R.id.page_indicator).text.toString())
                        val content = activity.pages.last()
                        val text = activity.pages.flatMap { texts(it) }.joinToString("\n")
                        assertTrue(text.contains("山海之间"))
                        assertTrue(text.contains("长夜与星光"))
                        assertTrue(text.contains("${fixture().year}-12-15"))
                        val bookDuration = textViews(content).last {
                            it.text.toString() == activity.getString(R.string.annual_report_duration, 2L, 30L)
                        }
                        assertTrue("Book reading time remains readable in theme $night",
                            androidx.core.graphics.ColorUtils.calculateContrast(
                                bookDuration.currentTextColor, activity.reportStyle.paper) >= 4.5)
                        assertTrue(pager.height > 0)
                        val image = Bitmap.createBitmap(pager.width, pager.height, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(image)
                        canvas.drawColor(activity.reportStyle.paper)
                        pager.draw(canvas)
                        File(activity.externalCacheDir, "annual-report-${if (night) "dark" else "light"}.png")
                            .outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        image.recycle()
                        activity.renderReport(AnnualReadReport.create(fixture().year,
                            LocalDate.now(), emptyList()))
                        assertEquals(3, pager.adapter?.itemCount)
                        assertTrue(activity.pages.flatMap { texts(it) }
                            .contains(activity.getString(R.string.annual_report_empty_title)))
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

    @Test
    fun chartSelectionAndChapterDotsReflectTheSavedStatistics() {
        ActivityScenario.launch(AnnualReadReportActivity::class.java).use { scenario ->
            awaitReport(scenario)
            scenario.onActivity { it.renderReport(fixture()) }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val pager = activity.findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.report_pager)
                val dots = activity.findViewById<ViewGroup>(R.id.page_dots)
                assertEquals(4, dots.childCount)
                dots.getChildAt(2).performClick()
                assertEquals(2, pager.currentItem)
                val chart = activity.pages[2]
                val june = chart.findViewWithTag<View>("annual_report_month_6")
                assertTrue(june.performClick())
                assertTrue(june.isSelected)
                assertFalse(chart.findViewWithTag<View>("annual_report_month_1").isSelected)
                assertEquals(activity.getString(R.string.annual_report_month_value,
                    activity.getString(R.string.annual_report_month, 6),
                    activity.getString(R.string.annual_report_duration, 2L, 30L)),
                    chart.findViewWithTag<TextView>("annual_report_month_detail").text.toString())
                assertEquals(activity.reportStyle.ink,
                    activity.findViewById<TextView>(R.id.page_indicator).currentTextColor)
            }
            awaitPagerLayout(scenario, 2)
            scenario.onActivity { activity ->
                val column = activity.pages[2].findViewWithTag<ViewGroup>("annual_report_month_6")
                val track = column.getChildAt(0) as ViewGroup
                assertTrue("June bar should be visible after jumping to the chart; track=${track.height}",
                    track.getChildAt(0).height > 0)
                assertTrue(track.getChildAt(0).background.level > 0)
                activity.renderReport(AnnualReadReport.create(fixture().year, LocalDate.now(), emptyList()))
                activity.findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.report_pager)
                    .setCurrentItem(2, false)
            }
            awaitPagerLayout(scenario, 2)
            scenario.onActivity { activity ->
                assertEquals(3, activity.findViewById<ViewGroup>(R.id.page_dots).childCount)
                repeat(12) { month ->
                    val column = activity.pages[2].findViewWithTag<ViewGroup>("annual_report_month_${month + 1}")
                    assertEquals(0, (column.getChildAt(0) as ViewGroup).getChildAt(0).background.level)
                }
            }
        }
    }

    @Test
    fun journalPagesRemainReadableInBothThemesAndCaptureScreenshots() {
        val original = AppConfig.isNightTheme
        try {
            for (night in listOf(false, true)) {
                instrumentation.runOnMainSync {
                    AppConfig.isNightTheme = night
                    ThemeConfig.applyDayNight(instrumentation.targetContext)
                }
                ActivityScenario.launch(AnnualReadReportActivity::class.java).use { scenario ->
                    awaitReport(scenario)
                    scenario.onActivity { it.renderReport(fixture()) }
                    instrumentation.waitForIdleSync()
                    repeat(4) { page ->
                        scenario.onActivity { activity ->
                            activity.findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.report_pager)
                                .setCurrentItem(page, false)
                        }
                        awaitPagerLayout(scenario, page)
                        scenario.onActivity { activity ->
                            val style = activity.reportStyle
                            assertEquals(ThemePalette(activity).background, style.paper)
                            assertEquals(style.paper, (activity.pages[page].background as ColorDrawable).color)
                            assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(
                                style.ink, style.paper) >= 4.5)
                            assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(
                                style.muted, style.paper) >= 4.5)
                            val root = activity.findViewById<View>(android.R.id.content)
                            val config = activity.resources.configuration
                            val scroll = activity.pages[page]
                            if (page in listOf(0, 2) && config.fontScale <= 1.05f &&
                                scroll.height / activity.resources.displayMetrics.density >= 630 &&
                                ConfigurationCompat.getLocales(config)[0]?.language == "zh") {
                                assertTrue("Chapter $page should fit a normal phone viewport: " +
                                    "content=${scroll.getChildAt(0).height}, viewport=${scroll.height}",
                                    scroll.getChildAt(0).height <= scroll.height)
                            }
                            val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                            root.draw(Canvas(image))
                            File(activity.externalCacheDir,
                                "annual-journal-${if (night) "dark" else "light"}-$page.png")
                                .outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            image.recycle()
                        }
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

    @Test
    fun allChaptersUseTheCurrentCustomAppBackground() {
        val context = instrumentation.targetContext
        val originalBackground = ThemePalette(context).background
        try {
            for (background in listOf(Color.rgb(232, 245, 233), Color.rgb(20, 35, 42))) {
                instrumentation.runOnMainSync {
                    ThemeStore.editTheme(context).backgroundColor(background).apply()
                }
                ActivityScenario.launch(AnnualReadReportActivity::class.java).use { scenario ->
                    awaitReport(scenario)
                    scenario.onActivity { activity ->
                        activity.renderReport(fixture())
                        val style = activity.reportStyle
                        assertEquals(background, style.paper)
                        assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(style.ink, background) >= 4.5)
                        assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(style.muted, background) >= 4.5)
                        activity.pages.forEach { page ->
                            assertEquals(background, (page.background as ColorDrawable).color)
                        }
                        val titleBar = activity.findViewById<io.legado.app.ui.widget.TitleBar>(R.id.title_bar)
                        assertEquals(background, (titleBar.background as ColorDrawable).color)
                        assertEquals(background,
                            (activity.findViewById<View>(R.id.page_navigation).background as ColorDrawable).color)
                    }
                }
            }
        } finally {
            instrumentation.runOnMainSync {
                ThemeStore.editTheme(context).backgroundColor(originalBackground).apply()
            }
        }
    }

    private fun texts(view: View): List<String> = when (view) {
        is TextView -> listOf(view.text.toString())
        is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun textViews(view: View): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { textViews(view.getChildAt(it)) }
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

    private fun awaitPagerLayout(scenario: ActivityScenario<AnnualReadReportActivity>, page: Int) {
        repeat(100) {
            var ready = false
            scenario.onActivity { activity ->
                val pager = activity.findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.report_pager)
                val content = activity.pages[page]
                ready = pager.scrollState == androidx.viewpager2.widget.ViewPager2.SCROLL_STATE_IDLE &&
                    content.isLaidOut && !content.isLayoutRequested
            }
            if (ready) {
                instrumentation.waitForIdleSync()
                return
            }
            Thread.sleep(20)
        }
        fail("Report page did not finish layout")
    }
}
