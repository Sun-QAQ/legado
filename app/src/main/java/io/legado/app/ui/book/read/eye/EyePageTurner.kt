package io.legado.app.ui.book.read.eye

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.legado.app.R
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.utils.ColorUtils
import kotlin.math.abs

/** Main-thread coordinator. Calibration covers the same area as the reading page. */
class EyePageTurner(
    private val activity: Activity,
    private val root: FrameLayout,
    private val canRead: () -> Boolean,
    private val canCalibrate: () -> Boolean,
    private val onNextPage: () -> Unit,
    private val onError: (Throwable) -> Unit,
    private val onCancel: () -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val detector = GazeGestureDetector()
    private val calibration = GazeCalibration()
    private var camera: EyeCameraTracker? = null
    private var generation = 0
    private var overlay: FrameLayout? = null
    private var target: View? = null
    private var message: TextView? = null
    private var calibratingBottom = false
    private var topValue = 0f
    private var topPose: GazeSample? = null
    val isCalibrating: Boolean get() = overlay != null
    private val timeout = Runnable {
        if (overlay != null) onError(IllegalStateException(activity.getString(R.string.eye_page_calibration_failed)))
    }

    @Suppress("DEPRECATION")
    fun start() {
        if (camera != null) return
        val current = ++generation
        val degrees = when (activity.windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        detector.reset(SystemClock.uptimeMillis(), cooldown = true)
        if (!detector.calibrated) showCalibration()
        camera = EyeCameraTracker(activity.applicationContext, degrees, { sample, time ->
            if (current == generation) accept(sample, time)
        }, { error ->
            if (current == generation) onError(error)
        })
    }

    private fun accept(sample: GazeSample?, time: Long) {
        // Discard results queued while the UI thread was busy.
        if (SystemClock.uptimeMillis() - time > 350L) {
            detector.reset()
            calibration.reset()
            return
        }
        if (overlay != null) {
            if (!canCalibrate()) {
                calibration.reset()
                return
            }
            val value = calibration.add(sample, time) ?: return
            calibration.reset()
            if (!calibratingBottom) {
                topValue = value
                topPose = sample
                calibratingBottom = true
                updateTarget()
                handler.removeCallbacks(timeout)
                handler.postDelayed(timeout, 20000L)
            } else if (sample != null && topPose?.let {
                    abs(sample.headPitch - it.headPitch) <= 0.12f
                            && abs(sample.headYaw - it.headYaw) <= 0.12f
                            && sample.faceScale / it.faceScale in 0.8f..1.2f
                } == true && detector.calibrate(topValue, value)) {
                hideCalibration()
                detector.reset(time, cooldown = true)
                android.widget.Toast.makeText(activity, R.string.eye_page_ready, android.widget.Toast.LENGTH_SHORT).show()
            } else {
                onError(IllegalStateException(activity.getString(R.string.eye_page_calibration_failed)))
            }
            return
        }
        if (!canRead()) {
            detector.reset()
            return
        }
        if (detector.update(sample, time)) onNextPage()
    }

    private fun showCalibration() {
        calibration.reset()
        calibratingBottom = false
        val backgroundColor = activity.bottomBackground
        val textColor = activity.getPrimaryTextColor(ColorUtils.isColorLight(backgroundColor))
        val accent = activity.accentColor
        val panel = FrameLayout(activity).apply {
            setBackgroundColor(backgroundColor)
            isClickable = true
            // Block manual page turns while learning the two targets.
            isFocusable = true
        }
        target = View(activity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(accent)
            }
            contentDescription = activity.getString(R.string.eye_page_target)
        }
        panel.addView(target, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            .apply { topMargin = dp(56) })
        val center = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), 0, dp(32), 0)
        }
        message = TextView(activity).apply {
            gravity = Gravity.CENTER
            textSize = 18f
            setTextColor(textColor)
        }
        center.addView(message, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        center.addView(TextView(activity).apply {
            setText(R.string.cancel)
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(accent)
            setPadding(dp(24), dp(16), dp(24), dp(16))
            setOnClickListener { onCancel() }
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(24) })
        panel.addView(center, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER))
        root.addView(panel, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        overlay = panel
        updateTarget()
        handler.postDelayed(timeout, 20000L)
    }

    private fun updateTarget() {
        target?.layoutParams = FrameLayout.LayoutParams(dp(24), dp(24),
            Gravity.CENTER_HORIZONTAL or if (calibratingBottom) Gravity.BOTTOM else Gravity.TOP).apply {
            topMargin = dp(56)
            bottomMargin = dp(56)
        }
        message?.setText(if (calibratingBottom) R.string.eye_page_calibrate_bottom else R.string.eye_page_calibrate_top)
    }

    private fun dp(value: Int) = (activity.resources.displayMetrics.density * value).toInt()

    private fun hideCalibration() {
        handler.removeCallbacks(timeout)
        overlay?.let(root::removeView)
        overlay = null
        message = null
        target = null
        calibration.reset()
    }

    fun resetGesture() = detector.reset(SystemClock.uptimeMillis(), cooldown = true)

    fun stop() {
        generation++
        camera?.stop()
        camera = null
        hideCalibration()
        resetGesture()
    }
}
