package io.legado.app.ui.book.read.eye

import kotlin.math.abs

/** Eye-relative coordinates; head pose is used only to reject movement, never to turn pages. */
internal data class GazeSample(
    val vertical: Float,
    val headPitch: Float,
    val headYaw: Float,
    val faceScale: Float
)

/** All times are monotonic milliseconds. Kept independent of Android for gesture tests. */
internal class GazeGestureDetector {
    private var top = 0f
    private var bottom = 0f
    var calibrated = false
        private set
    private var bottomSince: Long? = null
    private var lastBottom = 0L
    private var upperSince: Long? = null
    private var bottomPose: GazeSample? = null
    private var armed = false
    private var blockedUntil = 0L
    private var lastSample = 0L

    fun calibrate(top: Float, bottom: Float): Boolean {
        calibrated = top.isFinite() && bottom.isFinite() && abs(bottom - top) >= 0.015f
        this.top = top
        this.bottom = bottom
        reset()
        return calibrated
    }

    fun reset(now: Long = 0L, cooldown: Boolean = false) {
        bottomSince = null
        upperSince = null
        bottomPose = null
        armed = false
        if (cooldown) blockedUntil = now + 3000L
    }

    fun update(sample: GazeSample?, now: Long): Boolean {
        if (sample == null || !sample.vertical.isFinite() || !calibrated) {
            reset()
            return false
        }
        if (lastSample != 0L && now - lastSample > 350L) reset()
        lastSample = now
        if (now < blockedUntil) return false
        val position = (sample.vertical - top) / (bottom - top)
        val pose = bottomPose
        if (pose != null && (abs(sample.headPitch - pose.headPitch) > 0.12f
                    || abs(sample.headYaw - pose.headYaw) > 0.12f
                    || sample.faceScale / pose.faceScale !in 0.8f..1.2f)) {
            reset()
            return false
        }
        if (position >= 0.78f && position <= 1.5f) {
            if (bottomSince == null) {
                bottomSince = now
                bottomPose = sample
            }
            lastBottom = now
            upperSince = null
            if (now - bottomSince!! >= 600L) armed = true
        } else if (armed) {
            if (now - lastBottom > 2000L) {
                reset()
            } else if (position <= 0.4f && position >= -0.5f) {
                if (upperSince == null) upperSince = now
                if (now - upperSince!! >= 180L) {
                    reset(now, cooldown = true)
                    return true
                }
            } else {
                upperSince = null
            }
        } else {
            reset()
        }
        return false
    }
}

/** Requires consecutive, open-eye samples after allowing time to move to the target. */
internal class GazeCalibration {
    private val values = ArrayList<Float>()
    private var startedAt: Long? = null
    private var lastSample = 0L
    private var pose: GazeSample? = null

    fun reset() {
        values.clear()
        startedAt = null
        pose = null
    }

    fun add(sample: GazeSample?, now: Long): Float? {
        if (sample == null || !sample.vertical.isFinite()) {
            reset()
            return null
        }
        if (lastSample != 0L && now - lastSample > 350L) reset()
        lastSample = now
        val reference = pose
        if (reference != null && (abs(sample.headPitch - reference.headPitch) > 0.12f
                    || abs(sample.headYaw - reference.headYaw) > 0.12f
                    || sample.faceScale / reference.faceScale !in 0.8f..1.2f)) reset()
        if (pose == null) pose = sample
        val start = startedAt ?: now.also { startedAt = it }
        if (now - start < 700L) return null
        values.add(sample.vertical)
        if (now - start < 2200L || values.size < 12) return null
        val sorted = values.sorted()
        val median = sorted[sorted.size / 2]
        // Unstable gaze must not produce an unusable calibration.
        if (sorted[sorted.size * 9 / 10] - sorted[sorted.size / 10] > 0.06f) {
            reset()
            return null
        }
        return median
    }
}
