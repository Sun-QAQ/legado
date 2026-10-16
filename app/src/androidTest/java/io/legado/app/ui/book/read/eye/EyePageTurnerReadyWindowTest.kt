package io.legado.app.ui.book.read.eye

import android.os.SystemClock
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.ui.main.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** 合成视线验证主线程提示计时，不打开摄像头或修改已保存的校准。 */
@RunWith(AndroidJUnit4::class)
class EyePageTurnerReadyWindowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val accept = EyePageTurner::class.java.getDeclaredMethod(
        "accept", GazeObservation::class.java, java.lang.Long.TYPE
    ).apply { isAccessible = true }

    private fun sample(position: Float) = GazeObservation(
        GazeSample(0.3f + position * 0.3f, 0f, 0f, 1f), GazeTrackingState.TRACKING
    )

    private fun withReadyTurner(block: (EyePageTurner, AtomicBoolean, AtomicInteger) -> Unit) {
        val ready = AtomicBoolean(false)
        val turns = AtomicInteger(0)
        lateinit var turner: EyePageTurner
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                turner = EyePageTurner(activity, FrameLayout(activity), { null }, { true },
                    { ready.set(it) }, { turns.incrementAndGet() },
                    { throw AssertionError(it) }, {})
                val detector = EyePageTurner::class.java.getDeclaredField("detector")
                    .apply { isAccessible = true }.get(turner) as GazeGestureDetector
                assertTrue(detector.calibrate(0.3f, 0.6f))
                val now = SystemClock.uptimeMillis()
                accept.invoke(turner, sample(1f), now - 100L)
                accept.invoke(turner, sample(1f), now)
                assertTrue(ready.get())
            }
            try {
                block(turner, ready, turns)
            } finally {
                instrumentation.runOnMainSync { turner.stop() }
            }
        }
    }

    @Test
    fun missingAndStaleFramesKeepHintUntilFixedDeadline() {
        withReadyTurner { turner, ready, turns ->
            instrumentation.runOnMainSync {
                accept.invoke(turner, GazeObservation(null, GazeTrackingState.NO_FACE),
                    SystemClock.uptimeMillis())
                accept.invoke(turner, sample(0f), SystemClock.uptimeMillis() - 400L)
                assertTrue(ready.get())
            }
            SystemClock.sleep(2200L)
            instrumentation.runOnMainSync { assertTrue(ready.get()) }
            val waitUntil = SystemClock.uptimeMillis() + 1500L
            while (ready.get() && SystemClock.uptimeMillis() < waitUntil) {
                SystemClock.sleep(50L)
            }
            instrumentation.runOnMainSync {
                assertFalse(ready.get())
                assertEquals(0, turns.get())
            }
        }
    }

    @Test
    fun upwardGazeInThirdSecondTurnsOnceAndClearsHint() {
        withReadyTurner { turner, ready, turns ->
            SystemClock.sleep(2250L)
            instrumentation.runOnMainSync {
                val now = SystemClock.uptimeMillis()
                // Two fresh synthetic frames avoid depending on emulator rendering speed.
                accept.invoke(turner, sample(0f), now - 200L)
                assertEquals(0, turns.get())
                assertTrue(ready.get())
                accept.invoke(turner, sample(0f), now)
                assertEquals(1, turns.get())
                assertFalse(ready.get())
            }
            SystemClock.sleep(3100L)
            instrumentation.runOnMainSync {
                assertEquals(1, turns.get())
                assertFalse(ready.get())
            }
        }
    }
}
