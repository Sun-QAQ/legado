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
import io.legado.app.constant.AppLog
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.utils.ColorUtils
import kotlin.math.abs
import java.util.Locale

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
    private var status: TextView? = null
    private var calibratingBottom = false
    private var topPoint: GazeCalibrationPoint? = null
    private var retryCount = 0
    private var retryHint: String? = null
    private var lastIssue = R.string.eye_page_camera_starting
    private var staleFrames = 0
    private var receivedFrames = 0
    val isCalibrating: Boolean get() = overlay != null
    private val timeout = Runnable {
        if (overlay != null) {
            AppLog.put("眼动校准超时：stage=${stage()} frames=$receivedFrames stale=$staleFrames progress=${calibration.progress} issue=${issueMessage()}")
            onError(IllegalStateException(activity.getString(R.string.eye_page_calibration_detail,
                issueMessage())))
        }
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
        camera = EyeCameraTracker(activity.applicationContext, degrees, { observation, time ->
            if (current == generation) accept(observation, time)
        }, { error ->
            if (current == generation) onError(error)
        })
    }

    private fun accept(observation: GazeObservation, time: Long) {
        receivedFrames++
        val sample = observation.sample
        // Discard results queued while the UI thread was busy.
        if (SystemClock.uptimeMillis() - time > 350L) {
            staleFrames++
            lastIssue = R.string.eye_page_processing_slow
            status?.setText(lastIssue)
            detector.reset()
            calibration.reset()
            return
        }
        if (overlay != null) {
            if (!canCalibrate()) {
                lastIssue = R.string.eye_page_calibration_paused
                calibration.reset()
                return
            }
            val point = calibration.add(sample, time)
            lastIssue = when {
                calibration.unstable -> R.string.eye_page_gaze_unstable
                observation.state == GazeTrackingState.NO_FACE -> R.string.eye_page_no_face
                observation.state == GazeTrackingState.MULTIPLE_FACES -> R.string.eye_page_multiple_faces
                observation.state == GazeTrackingState.EYES_CLOSED -> R.string.eye_page_eyes_closed
                observation.state == GazeTrackingState.TOO_FAR -> R.string.eye_page_too_far
                observation.state != GazeTrackingState.TRACKING -> R.string.eye_page_eyes_unclear
                else -> R.string.eye_page_sampling
            }
            status?.text = if (lastIssue == R.string.eye_page_sampling) {
                activity.getString(lastIssue, calibration.progress)
            } else activity.getString(lastIssue)
            if (point == null) return
            AppLog.put("眼动校准采样完成：stage=${stage()} ${describe(point)}")
            calibration.reset()
            if (!calibratingBottom) {
                topPoint = point
                calibratingBottom = true
                updateTarget()
                status?.setText(R.string.eye_page_target_changed)
                handler.removeCallbacks(timeout)
                handler.postDelayed(timeout, 20000L)
            } else {
                val top = topPoint ?: return
                val noise = maxOf(top.noise, point.noise)
                val samePose = top.hasSimilarPose(point)
                val separated = detector.calibrate(top.sample.vertical, point.sample.vertical, noise)
                AppLog.put("眼动校准比较：top=${describe(top)} bottom=${describe(point)} span=${abs(point.sample.vertical - top.sample.vertical)} requiredSpan=${maxOf(0.008f, noise * 3f)} samePose=$samePose separated=$separated")
                if (samePose && separated) {
                    hideCalibration()
                    detector.reset(time, cooldown = true)
                    android.widget.Toast.makeText(activity, R.string.eye_page_ready, android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    retryCalibration(if (!samePose) R.string.eye_page_pose_changed else R.string.eye_page_gaze_too_close)
                }
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
        topPoint = null
        retryCount = 0
        retryHint = null
        staleFrames = 0
        receivedFrames = 0
        lastIssue = R.string.eye_page_camera_starting
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
        status = TextView(activity).apply {
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(textColor)
            setText(R.string.eye_page_camera_starting)
        }
        center.addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) })
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
        val instruction = activity.getString(if (calibratingBottom) R.string.eye_page_calibrate_bottom else R.string.eye_page_calibrate_top)
        message?.text = instruction + (retryHint?.let { "\n\n$it" } ?: "")
    }

    private fun retryCalibration(reason: Int) {
        lastIssue = reason
        if (retryCount >= 1) {
            onError(IllegalStateException(activity.getString(R.string.eye_page_calibration_detail, activity.getString(reason))))
            return
        }
        retryCount++
        calibration.reset()
        calibratingBottom = false
        topPoint = null
        // A rejected pair must not leave an otherwise valid detector active.
        detector.calibrate(0f, 0f)
        retryHint = activity.getString(reason)
        updateTarget()
        status?.setText(R.string.eye_page_retrying)
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, 20000L)
    }

    private fun stage() = if (calibratingBottom) "bottom" else "top"

    private fun issueMessage(): String = activity.getString(
        if (lastIssue == R.string.eye_page_sampling) R.string.eye_page_gaze_unstable else lastIssue)

    private fun describe(point: GazeCalibrationPoint): String = String.format(Locale.ROOT,
        "gaze=%.5f noise=%.5f pitch=%.4f yaw=%.4f scale=%.4f count=%d",
        point.sample.vertical, point.noise, point.sample.headPitch, point.sample.headYaw,
        point.sample.faceScale, point.sampleCount)

    private fun dp(value: Int) = (activity.resources.displayMetrics.density * value).toInt()

    private fun hideCalibration() {
        handler.removeCallbacks(timeout)
        overlay?.let(root::removeView)
        overlay = null
        message = null
        status = null
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
