package io.legado.app.ui.book.read.eye

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class GazeGestureDetectorTest {
    private fun sample(position: Float, pitch: Float = 0f, yaw: Float = 0f, scale: Float = 1f) =
        GazeSample(0.3f + position * 0.3f, pitch, yaw, scale)

    private fun detector() = GazeGestureDetector().apply { assertTrue(calibrate(0.3f, 0.6f)) }

    private fun readBottom(detector: GazeGestureDetector, start: Long = 1000L) {
        for (time in start..start + 800L step 100L) assertFalse(detector.update(sample(1f), time))
    }

    @Test
    fun bottomThenUpTurnsExactlyOnce() {
        val detector = detector()
        readBottom(detector)
        assertFalse(detector.update(sample(0f), 1900))
        assertFalse(detector.update(sample(0f), 2000))
        assertTrue(detector.update(sample(0f), 2100))
        for (time in 2200L..4000L step 100L) assertFalse(detector.update(sample(0f), time))
    }

    @Test
    fun ordinaryDownwardReadingAndLookingUpWithoutBottomDoNotTurn() {
        val detector = detector()
        for (time in 1000L..2000L step 100L) assertFalse(detector.update(sample(0f), time))
        for (index in 0..10) assertFalse(detector.update(sample(index / 10f), 2100L + index * 100))
        for (time in 3200L..4300L step 100L) assertFalse(detector.update(sample(1f), time))
    }

    @Test
    fun briefBottomGlanceAndUpwardNoiseDoNotTurn() {
        val detector = detector()
        for (time in 1000L..1300L step 100L) assertFalse(detector.update(sample(1f), time))
        for (time in 1400L..1800L step 100L) assertFalse(detector.update(sample(0f), time))
        readBottom(detector, 2000)
        assertFalse(detector.update(sample(0f), 2900))
        assertFalse(detector.update(sample(0.6f), 3000))
        assertFalse(detector.update(sample(0f), 3100))
    }

    @Test
    fun blinkOrLostFaceRequiresNewBottomDwell() {
        val detector = detector()
        readBottom(detector)
        assertFalse(detector.update(null, 1900))
        for (time in 2000L..2500L step 100L) assertFalse(detector.update(sample(0f), time))
        readBottom(detector, 2600)
        assertFalse(detector.update(sample(0f), 3500))
        assertTrue(detector.update(sample(0f), 3700))
    }

    @Test
    fun headMovementCannotTriggerUpwardGesture() {
        for (moved in listOf(sample(0f, pitch = 0.3f), sample(0f, yaw = 0.3f), sample(0f, scale = 1.4f))) {
            val detector = detector()
            readBottom(detector)
            for (time in 1900L..2400L step 100L) assertFalse(detector.update(moved, time))
        }
    }

    @Test
    fun cameraStallDoesNotJoinUnrelatedGazes() {
        val detector = detector()
        readBottom(detector)
        for (time in 2500L..3000L step 100L) assertFalse(detector.update(sample(0f), time))
    }

    @Test
    fun upwardMovementMustFollowBottomPromptly() {
        val detector = detector()
        readBottom(detector)
        for (time in 1900L..4100L step 100L) assertFalse(detector.update(sample(0.6f), time))
        for (time in 4200L..4600L step 100L) assertFalse(detector.update(sample(0f), time))
    }

    @Test
    fun pageChangeOrMenuResetCancelsPendingGesture() {
        val detector = detector()
        readBottom(detector)
        detector.reset(1850, cooldown = true)
        for (time in 1900L..2600L step 100L) assertFalse(detector.update(sample(0f), time))
        readBottom(detector, 5000)
        assertFalse(detector.update(sample(0f), 5900))
        assertTrue(detector.update(sample(0f), 6100))
    }

    @Test
    fun cooldownPreventsDoubleTurnButAllowsNextPage() {
        val detector = detector()
        readBottom(detector)
        assertFalse(detector.update(sample(0f), 1900))
        assertTrue(detector.update(sample(0f), 2100))
        readBottom(detector, 2200)
        for (time in 3100L..3500L step 100L) assertFalse(detector.update(sample(0f), time))
        readBottom(detector, 5200)
        assertFalse(detector.update(sample(0f), 6100))
        assertTrue(detector.update(sample(0f), 6300))
    }

    @Test
    fun calibrationRejectsIndistinguishableTargetsAndNonFiniteValues() {
        val detector = GazeGestureDetector()
        assertFalse(detector.calibrate(0.3f, 0.301f))
        assertFalse(detector.calibrate(Float.NaN, 0.6f))
        assertFalse(detector.update(sample(0f), 1000))
        assertTrue(detector.calibrate(0.6f, 0.3f))
        for (time in 1000L..1800L step 100L) assertFalse(detector.update(sample(0f), time))
        assertFalse(detector.update(sample(1f), 1900))
        assertTrue(detector.update(sample(1f), 2100))
    }

    @Test
    fun calibrationWaitsForStableOpenEyesAndResetsOnLostFace() {
        val calibration = GazeCalibration()
        for (time in 1000L..2400L step 100L) assertEquals(null, calibration.add(sample(0f), time))
        assertEquals(null, calibration.add(null, 2500))
        for (time in 2600L..4700L step 100L) assertEquals(null, calibration.add(sample(0f), time))
        assertEquals(0.3f, calibration.add(sample(0f), 4800)!!, 0.001f)
    }

    @Test
    fun unstableCalibrationDoesNotComplete() {
        val calibration = GazeCalibration()
        for (index in 0..22) {
            assertEquals(null, calibration.add(sample(if (index % 2 == 0) 0f else 1f), 1000L + index * 100))
        }
    }
}
