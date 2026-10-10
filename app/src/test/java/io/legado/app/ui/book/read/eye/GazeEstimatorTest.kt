package io.legado.app.ui.book.read.eye

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class GazeEstimatorTest {
    private fun face(irisOffset: Float = 0f, lidOffset: Float = 0f): MutableList<GazeLandmark> =
        MutableList(478) { GazeLandmark(180f, 140f) }.apply {
            this[33] = GazeLandmark(100f, 100f)
            this[133] = GazeLandmark(140f, 100f)
            this[362] = GazeLandmark(200f, 100f)
            this[263] = GazeLandmark(240f, 100f)
            this[159] = GazeLandmark(120f, 94f + lidOffset)
            this[145] = GazeLandmark(120f, 106f + lidOffset)
            this[386] = GazeLandmark(220f, 94f + lidOffset)
            this[374] = GazeLandmark(220f, 106f + lidOffset)
            this[468] = GazeLandmark(120f, 100f + irisOffset)
            this[473] = GazeLandmark(220f, 100f + irisOffset)
        }

    @Test
    fun eyelidsFollowingIrisDoNotCancelVerticalGazeSignal() {
        val top = GazeEstimator.estimate(face(), 400).sample!!
        val bottom = GazeEstimator.estimate(face(irisOffset = 1f, lidOffset = 0.8f), 400).sample!!
        assertEquals(0.025f, bottom.vertical - top.vertical, 0.0001f)
        assertTrue(GazeGestureDetector().calibrate(top.vertical, bottom.vertical))
        // The old eyelid midpoint reference would shrink this movement below its threshold.
        assertFalse((1f - 0.8f) / 40f >= 0.015f)
    }

    @Test
    fun movingOnlyEyelidsDoesNotCreateUpwardGaze() {
        val before = GazeEstimator.estimate(face(irisOffset = 2f), 400).sample!!
        val after = GazeEstimator.estimate(face(irisOffset = 2f, lidOffset = 2f), 400).sample!!
        assertEquals(before.vertical, after.vertical, 0.0001f)
    }

    @Test
    fun headRollTranslationAndScalePreserveEyeRelativeGaze() {
        val points = face(irisOffset = 2f)
        val before = GazeEstimator.estimate(points, 400).sample!!
        val angle = 0.3f
        val moved = points.map {
            GazeLandmark((it.x * cos(angle) - it.y * sin(angle)) * 1.5f + 30f,
                (it.x * sin(angle) + it.y * cos(angle)) * 1.5f + 50f)
        }
        val after = GazeEstimator.estimate(moved, 600).sample!!
        assertEquals(before.vertical, after.vertical, 0.0001f)
        assertEquals(before.headPitch, after.headPitch, 0.0001f)
        assertEquals(before.headYaw, after.headYaw, 0.0001f)
        assertEquals(before.faceScale, after.faceScale, 0.0001f)
    }

    @Test
    fun closedEyeAndDisagreeingEyesRemainRejected() {
        val closed = face().apply {
            this[159] = GazeLandmark(120f, 99f)
            this[145] = GazeLandmark(120f, 101f)
        }
        assertEquals(GazeTrackingState.EYES_CLOSED, GazeEstimator.estimate(closed, 400).state)
        val disagreement = face().apply { this[468] = GazeLandmark(120f, 110f) }
        assertEquals(GazeTrackingState.INCONSISTENT_EYES, GazeEstimator.estimate(disagreement, 400).state)
    }

    @Test
    fun missingNonFiniteAndTooSmallEyesProvideSpecificFailureState() {
        assertEquals(GazeTrackingState.INVALID_LANDMARKS, GazeEstimator.estimate(emptyList(), 400).state)
        val invalid = face().apply { this[468] = GazeLandmark(Float.NaN, 100f) }
        assertEquals(GazeTrackingState.INVALID_LANDMARKS, GazeEstimator.estimate(invalid, 400).state)
        val distant = face().map { GazeLandmark(it.x * 0.1f, it.y * 0.1f) }
        assertEquals(GazeTrackingState.TOO_FAR, GazeEstimator.estimate(distant, 400).state)
    }
}
