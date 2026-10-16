package io.legado.app.ui.book.read.eye

import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.ui.main.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** 合成视线验证准备阶段，不打开摄像头，也不完成或覆盖用户的校准。 */
@RunWith(AndroidJUnit4::class)
class EyePageTurnerCalibrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val accept = EyePageTurner::class.java.getDeclaredMethod(
        "accept", GazeObservation::class.java, java.lang.Long.TYPE
    ).apply { isAccessible = true }

    private fun value(turner: EyePageTurner, name: String): Any? =
        EyePageTurner::class.java.getDeclaredField(name).apply { isAccessible = true }.get(turner)

    private fun sample(position: Float) = GazeObservation(
        GazeSample(0.3f + position * 0.3f, 0f, 0f, 1f), GazeTrackingState.TRACKING
    )

    private fun startedAt(turner: EyePageTurner): Any? =
        GazeCalibration::class.java.getDeclaredField("startedAt").apply { isAccessible = true }
            .get(value(turner, "calibration"))

    private fun withCalibration(block: (EyePageTurner, AtomicBoolean, FrameLayout) -> Unit) {
        val available = AtomicBoolean(true)
        val error = AtomicReference<Throwable?>()
        lateinit var turner: EyePageTurner
        lateinit var root: FrameLayout
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                root = FrameLayout(activity)
                activity.setContentView(root)
                turner = EyePageTurner(activity, root, { null }, { available.get() }, {}, {},
                    { error.set(it) }, {})
                EyePageTurner::class.java.getDeclaredMethod("showCalibration")
                    .apply { isAccessible = true }.invoke(turner)
            }
            try {
                block(turner, available, root)
                assertNull(error.get())
            } finally {
                instrumentation.runOnMainSync { turner.stop() }
            }
        }
    }

    private fun awaitMain(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 4500L
        while (SystemClock.uptimeMillis() < deadline) {
            var done = false
            instrumentation.runOnMainSync { done = condition() }
            if (done) return
            SystemClock.sleep(25L)
        }
        instrumentation.runOnMainSync { assertTrue("等待校准状态超时", condition()) }
    }

    @Test
    fun countdownDiscardsSamplesAndRestartsWhenBottomTargetAppears() {
        withCalibration { turner, _, _ ->
            instrumentation.runOnMainSync {
                assertEquals(true, value(turner, "calibrationPreparing"))
                val message = (value(turner, "message") as TextView).text.toString()
                assertTrue(message.contains("顶部圆点"))
                assertTrue(message.contains("不要看说明或其他地方"))
                assertTrue((value(turner, "status") as TextView).text.contains("3 秒"))
                accept.invoke(turner, sample(0f), SystemClock.uptimeMillis())
                assertNull(startedAt(turner))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    assertFalse((value(turner, "handler") as Handler)
                        .hasCallbacks(value(turner, "timeout") as Runnable))
                }
            }
            instrumentation.waitForIdleSync()
            val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            val directory = instrumentation.targetContext.getExternalFilesDir("test-artifacts")!!
            File(directory, "eye-calibration-preparation.png").outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            screenshot.recycle()
            awaitMain { (value(turner, "status") as TextView).text.contains("2 秒") }
            awaitMain { (value(turner, "status") as TextView).text.contains("1 秒") }
            awaitMain { value(turner, "calibrationPreparing") == false }
            instrumentation.runOnMainSync {
                val readyAt = value(turner, "calibrationReadyAt") as Long
                accept.invoke(turner, sample(0f), readyAt - 1L)
                assertNull(startedAt(turner))
                accept.invoke(turner, sample(0f), SystemClock.uptimeMillis())
                assertNotNull(startedAt(turner))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    assertTrue((value(turner, "handler") as Handler)
                        .hasCallbacks(value(turner, "timeout") as Runnable))
                }
            }
            repeat(25) {
                SystemClock.sleep(100L)
                instrumentation.runOnMainSync {
                    accept.invoke(turner, sample(0f), SystemClock.uptimeMillis())
                }
            }
            instrumentation.runOnMainSync {
                assertEquals(true, value(turner, "calibratingBottom"))
                assertEquals(true, value(turner, "calibrationPreparing"))
                assertTrue((value(turner, "message") as TextView).text.contains("底部圆点"))
                accept.invoke(turner, sample(1f), SystemClock.uptimeMillis())
                assertNull(startedAt(turner))
            }
            awaitMain { value(turner, "calibrationPreparing") == false }
        }
    }

    @Test
    fun pausingRestartsFullCountdownBeforeSampling() {
        withCalibration { turner, available, _ ->
            SystemClock.sleep(1200L)
            instrumentation.runOnMainSync {
                available.set(false)
                accept.invoke(turner, sample(0f), SystemClock.uptimeMillis())
            }
            SystemClock.sleep(2100L)
            instrumentation.runOnMainSync {
                assertEquals(true, value(turner, "calibrationPreparing"))
                assertNull(startedAt(turner))
                available.set(true)
                accept.invoke(turner, sample(0f), SystemClock.uptimeMillis())
                assertTrue((value(turner, "status") as TextView).text.contains("3 秒"))
            }
            SystemClock.sleep(2200L)
            instrumentation.runOnMainSync {
                assertEquals(true, value(turner, "calibrationPreparing"))
                accept.invoke(turner, sample(0f), SystemClock.uptimeMillis())
                assertNull(startedAt(turner))
            }
            awaitMain { value(turner, "calibrationPreparing") == false }
        }
    }

    @Test
    fun retryStartsPreparationAndStopRemovesCountdown() {
        withCalibration { turner, _, root ->
            instrumentation.runOnMainSync {
                EyePageTurner::class.java.getDeclaredMethod("retryCalibration", Integer.TYPE)
                    .apply { isAccessible = true }.invoke(turner, R.string.eye_page_pose_changed)
                assertEquals(true, value(turner, "calibrationPreparing"))
                assertEquals(false, value(turner, "calibratingBottom"))
                assertTrue((value(turner, "status") as TextView).text.contains("3 秒"))
                accept.invoke(turner, sample(0f), SystemClock.uptimeMillis())
                assertNull(startedAt(turner))
                turner.stop()
            }
            SystemClock.sleep(3200L)
            instrumentation.runOnMainSync {
                assertFalse(turner.isCalibrating)
                assertEquals(false, value(turner, "calibrationPreparing"))
                assertEquals(0, root.childCount)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    assertFalse((value(turner, "handler") as Handler)
                        .hasCallbacks(value(turner, "calibrationCountdown") as Runnable))
                }
            }
        }
    }
}
