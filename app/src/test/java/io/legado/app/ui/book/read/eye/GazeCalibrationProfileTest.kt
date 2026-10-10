package io.legado.app.ui.book.read.eye

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GazeCalibrationProfileTest {
    @Test
    fun roundTripKeepsSmallPhoneSignalAndReversedDirection() {
        for (profile in listOf(GazeCalibrationProfile(-0.06420f, -0.00439f, 0.01036f),
            GazeCalibrationProfile(0.032f, 0.020f, 0.001f))) {
            assertEquals(profile, GazeCalibrationProfile.decode(profile.encode()))
        }
    }

    @Test
    fun savedCalibrationWorksInMultipleNewReadingSessions() {
        val stored = GazeCalibrationProfile(-0.06420f, -0.00439f, 0.01036f).encode()
        repeat(2) {
            val restored = GazeCalibrationProfile.decode(stored)!!
            val detector = GazeGestureDetector()
            assertTrue(detector.calibrate(restored.top, restored.bottom, restored.noise))
            fun sample(position: Float) = GazeSample(
                restored.top + position * (restored.bottom - restored.top), 0.4263f, 0.049f, 0.2677f)
            for (time in 1000L..1800L step 100L) assertFalse(detector.update(sample(1f), time))
            assertFalse(detector.update(sample(0f), 1900))
            assertTrue(detector.update(sample(0f), 2100))
        }
    }

    @Test
    fun missingMalformedOrUnusableCalibrationRequiresFirstCalibration() {
        for (stored in listOf(null, "", "1", "2,0.3,0.6,0", "1,0.3,0.6,0,extra",
            "1,bad,0.6,0", "1,NaN,0.6,0", "1,0.3,Infinity,0", "1,0.3,0.6,NaN",
            "1,0.3,0.6,-0.1", "1,0.3,0.3,0", "1,0.3,0.301,0", "1,0.3,0.6,0.2",
            "1,-3.4E38,3.4E38,0")) {
            assertNull(stored, GazeCalibrationProfile.decode(stored))
        }
    }
}
