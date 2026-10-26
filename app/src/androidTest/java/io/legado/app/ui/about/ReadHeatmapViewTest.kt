package io.legado.app.ui.about

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.data.entities.ReadStat
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class ReadHeatmapViewTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun calendarRendersAllLevelsAndSelectsDaysInBothThemes() {
        val originalNight = AppConfig.isNightTheme
        try {
            for (night in listOf(false, true)) {
                instrumentation.runOnMainSync {
                    AppConfig.isNightTheme = night
                    ThemeConfig.applyDayNight(instrumentation.targetContext)
                }
                ActivityScenario.launch(ReadRecordActivity::class.java).use { scenario ->
                    awaitSummary(scenario)
                    val today = LocalDate.now()
                    // Fixtures are only supplied to the view, never written into reading history.
                    val data = ReadHeatmap(today, (0L..91L step 3).map { offset ->
                        ReadStat("sample", today.minusDays(offset).toEpochDay(),
                            listOf(10L, 45L, 90L, 150L)[(offset / 3 % 4).toInt()] * 60_000)
                    })
                    scenario.onActivity { activity ->
                        val chart = activity.findViewById<ReadHeatmapView>(R.id.read_heatmap)
                        chart.setData(data)
                        activity.findViewById<TextView>(R.id.tv_heatmap_summary).text =
                            activity.getString(R.string.read_heatmap_summary, data.activeDays,
                                activity.formatDuring(data.totalTime))
                    }
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { activity ->
                        val chart = activity.findViewById<ReadHeatmapView>(R.id.read_heatmap)
                        assertEquals("Three months fit the card", (chart.parent as View).width -
                            (chart.parent as View).paddingLeft - (chart.parent as View).paddingRight, chart.width)
                        val bitmap = Bitmap.createBitmap(chart.width, chart.height, Bitmap.Config.ARGB_8888)
                        chart.draw(Canvas(bitmap))
                        val levelColors = mutableMapOf<Int, Int>()
                        data.days.forEachIndexed { index, day ->
                            val node = checkNotNull(chart.accessibilityNodeProvider.createAccessibilityNodeInfo(index))
                            val bounds = Rect()
                            @Suppress("DEPRECATION")
                            node.getBoundsInParent(bounds)
                            assertTrue("Every day is visible", bounds.left >= 0 && bounds.right <= chart.width)
                            assertTrue("Calendar cells stay square", abs(bounds.width() - bounds.height()) <= 1)
                            levelColors[day.level] = bitmap.getPixel(bounds.centerX(), bounds.centerY())
                            assertEquals(chart.describeDay(day), node.contentDescription.toString())
                        }
                        assertEquals("Every level has a different fill", 5, levelColors.values.toSet().size)
                        val mondayIndex = data.days.indexOfFirst { it.date.dayOfWeek.value == 1 }
                        val positions = listOf(mondayIndex, mondayIndex + 1, mondayIndex + 7).map { index ->
                            Rect().also { bounds ->
                                @Suppress("DEPRECATION")
                                checkNotNull(chart.accessibilityNodeProvider.createAccessibilityNodeInfo(index))
                                    .getBoundsInParent(bounds)
                            }
                        }
                        assertTrue("Week and weekday spacing match", abs(
                            (positions[2].left - positions[0].left) -
                                (positions[1].top - positions[0].top)
                        ) <= 1)
                        val luminance = (0..4).map { ColorUtils.calculateLuminance(levelColors.getValue(it)) }
                        luminance.zipWithNext { less, more ->
                            assertTrue("Reading intensity increases in theme $night",
                                if (night) more > less else more < less)
                        }
                        bitmap.recycle()
                        val selectedIndex = data.days.lastIndex - 3
                        assertTrue(chart.accessibilityNodeProvider.performAction(selectedIndex,
                            AccessibilityNodeInfoCompat.ACTION_CLICK, null))
                        assertTrue(checkNotNull(chart.accessibilityNodeProvider
                            .createAccessibilityNodeInfo(selectedIndex)).isSelected)
                        val bounds = Rect()
                        @Suppress("DEPRECATION")
                        checkNotNull(chart.accessibilityNodeProvider.createAccessibilityNodeInfo(data.days.lastIndex))
                            .getBoundsInParent(bounds)
                        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                            val event = MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(),
                                action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
                            chart.dispatchTouchEvent(event)
                            event.recycle()
                        }
                        assertTrue(checkNotNull(chart.accessibilityNodeProvider
                            .createAccessibilityNodeInfo(data.days.lastIndex)).isSelected)
                        val card = activity.findViewById<View>(R.id.heatmap_card)
                        val preview = Bitmap.createBitmap(card.width, card.height, Bitmap.Config.ARGB_8888)
                        card.draw(Canvas(preview))
                        File(activity.externalCacheDir, "read-heatmap-${if (night) "dark" else "light"}.png")
                            .outputStream().use { preview.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        preview.recycle()
                    }
                }
            }
        } finally {
            instrumentation.runOnMainSync {
                AppConfig.isNightTheme = originalNight
                ThemeConfig.applyDayNight(instrumentation.targetContext)
            }
        }
    }

    private fun awaitSummary(scenario: ActivityScenario<ReadRecordActivity>) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            scenario.onActivity {
                ready = it.findViewById<TextView>(R.id.tv_heatmap_summary).text.isNotBlank()
            }
            if (ready) return
            SystemClock.sleep(50)
        }
        throw AssertionError("Reading summary did not load")
    }
}
