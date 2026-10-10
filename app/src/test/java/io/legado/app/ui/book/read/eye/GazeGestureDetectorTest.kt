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
    fun lostFaceRequiresNewBottomDwell() {
        val detector = detector()
        readBottom(detector)
        assertFalse(detector.update(null, 1900))
        for (time in 2000L..2500L step 100L) assertFalse(detector.update(sample(0f), time))
        readBottom(detector, 2600)
        assertFalse(detector.update(sample(0f), 3500))
        assertTrue(detector.update(sample(0f), 3700))
    }

    @Test
    fun phoneCalibrationWithBottomJitterAndBlinkStillTurns() {
        // Calibration endpoints/noise from the attached HONOR log. The per-frame
        // positions below simulate reading jitter; the log only contains aggregates.
        val top = -0.06420f
        val bottom = -0.00439f
        val detector = GazeGestureDetector()
        assertTrue(detector.calibrate(top, bottom, 0.01036f))
        fun phoneSample(position: Float) = GazeSample(top + position * (bottom - top),
            0.4263f, 0.049f, 0.2677f)
        val positions = listOf(1f, 0.75f, 0.68f, 0.92f, null, 0.70f, 0.95f, 1.1f, 0.72f, 0.83f, 0.91f)
        positions.forEachIndexed { index, position ->
            assertFalse(detector.update(position?.let(::phoneSample), 1000L + index * 100, position == null))
        }
        assertTrue(detector.armed)
        assertFalse(detector.update(phoneSample(0.15f), 2100))
        assertFalse(detector.update(phoneSample(0.25f), 2200))
        assertTrue(detector.update(phoneSample(0.12f), 2300))
    }

    @Test
    fun naturalBlinkBetweenBottomAndUpDoesNotEraseArming() {
        val detector = detector()
        readBottom(detector)
        assertFalse(detector.update(null, 1900, eyesClosed = true))
        assertFalse(detector.update(null, 2000, eyesClosed = true))
        assertFalse(detector.update(sample(0f), 2100))
        assertFalse(detector.update(sample(0f), 2200))
        assertTrue(detector.update(sample(0f), 2300))
    }

    @Test
    fun closedEyeTimeDoesNotCountAsBottomDwell() {
        val detector = detector()
        for (time in 1000L..1300L step 100L) assertFalse(detector.update(sample(1f), time))
        for (time in 1400L..1600L step 100L) assertFalse(detector.update(null, time, eyesClosed = true))
        assertFalse(detector.update(sample(1f), 1700))
        assertEquals(300L, detector.bottomDwellMs)
        for (time in 1800L..2300L step 100L) assertFalse(detector.update(sample(0f), time))
    }

    @Test
    fun prolongedClosedEyesRequireNewBottomDwell() {
        val detector = detector()
        readBottom(detector)
        for (time in 1900L..2600L step 100L) assertFalse(detector.update(null, time, eyesClosed = true))
        for (time in 2700L..3200L step 100L) assertFalse(detector.update(sample(0f), time))
    }

    @Test
    fun reopeningAfterBlinkTimeoutCannotJoinBottomAndUp() {
        val detector = detector()
        readBottom(detector)
        assertFalse(detector.update(null, 2100, eyesClosed = true))
        for (time in 2400L..2900L step 100L) assertFalse(detector.update(sample(0f), time))
    }

    @Test
    fun blinkDuringUpwardGlanceRequiresFreshOpenEyeDwell() {
        val detector = detector()
        readBottom(detector)
        assertFalse(detector.update(sample(0f), 1900))
        assertFalse(detector.update(null, 2000, eyesClosed = true))
        assertFalse(detector.update(sample(0f), 2100))
        assertFalse(detector.update(sample(0f), 2200))
        assertTrue(detector.update(sample(0f), 2300))
    }

    @Test
    fun headMovementDuringBlinkStillCancelsGesture() {
        val detector = detector()
        readBottom(detector)
        assertFalse(detector.update(null, 1900, eyesClosed = true))
        for (time in 2000L..2500L step 100L) assertFalse(detector.update(sample(0f, pitch = 0.3f), time))
    }

    @Test
    fun middleOfPageDoesNotStartBottomDwell() {
        val detector = detector()
        for (time in 1000L..2200L step 100L) assertFalse(detector.update(sample(0.65f), time))
        for (time in 2300L..2800L step 100L) assertFalse(detector.update(sample(0f), time))
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
    fun calibrationWaitsForStableOpenEyesAndResetsOnProlongedLostFace() {
        val calibration = GazeCalibration()
        for (time in 1000L..2400L step 100L) assertEquals(null, calibration.add(sample(0f), time))
        assertEquals(null, calibration.add(null, 2500))
        assertEquals(null, calibration.add(null, 3300))
        for (time in 3400L..5500L step 100L) assertEquals(null, calibration.add(sample(0f), time))
        assertEquals(0.3f, calibration.add(sample(0f), 5600)!!.sample.vertical, 0.001f)
    }

    @Test
    fun unstableCalibrationDoesNotComplete() {
        val calibration = GazeCalibration()
        for (index in 0..22) {
            assertEquals(null, calibration.add(sample(if (index % 2 == 0) 0f else 1f), 1000L + index * 100))
        }
    }

    @Test
    fun naturalBlinkPausesCalibrationWithoutErasingSamples() {
        val calibration = GazeCalibration()
        for (time in 1000L..2400L step 100L) assertEquals(null, calibration.add(sample(0f), time))
        val progressBeforeBlink = calibration.progress
        assertEquals(null, calibration.add(null, 2500))
        assertEquals(progressBeforeBlink, calibration.progress)
        var result: GazeCalibrationPoint? = null
        for (time in 2600L..3400L step 100L) result = calibration.add(sample(0f), time)
        assertEquals(0.3f, result!!.sample.vertical, 0.001f)
        assertTrue(result.sampleCount >= 12)
    }

    @Test
    fun calibrationUsesMedianPoseInsteadOfLastFrame() {
        val calibration = GazeCalibration()
        for (time in 1000L..3100L step 100L) assertEquals(null, calibration.add(sample(0f, pitch = 0.03f), time))
        val top = calibration.add(sample(0f, pitch = -0.08f), 3200)!!
        val bottom = GazeCalibrationPoint(sample(1f, pitch = 0.06f), 0f, 16)
        assertEquals(0.03f, top.sample.headPitch, 0.001f)
        assertTrue(top.hasSimilarPose(bottom))
        assertFalse(top.hasSimilarPose(bottom.copy(sample = sample(1f, pitch = 0.4f))))
    }

    @Test
    fun smallStableSignalCanCalibrateButNoisyOrIdenticalTargetsCannot() {
        val detector = GazeGestureDetector()
        assertTrue(detector.calibrate(0.02f, 0.032f, 0.001f))
        assertFalse(detector.calibrate(0.02f, 0.032f, 0.006f))
        assertFalse(detector.calibrate(0.02f, 0.02f, 0.001f))
        assertFalse(detector.calibrate(0.02f, 0.032f, Float.NaN))
    }

    @Test
    fun noisySamplesEventuallyRecoverAfterGazeStabilizes() {
        val calibration = GazeCalibration()
        for (index in 0..30) assertEquals(null,
            calibration.add(sample(if (index % 2 == 0) 0f else 1f), 1000L + index * 100))
        var point: GazeCalibrationPoint? = null
        for (time in 4100L..9500L step 100L) point = calibration.add(sample(0f), time)
        assertEquals(0.3f, point!!.sample.vertical, 0.001f)
    }
}
