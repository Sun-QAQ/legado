package io.legado.app.ui.book.read.eye

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GazeCalibrationProfileTest {
    @Test
    fun roundTripKeepsSmallPhoneSignalAndReversedDirection() {
        for (profile in listOf(GazeCalibrationProfile(-0.06420f, -0.00439f, 0.01036f, -0.034295f, 0.01036f),
            GazeCalibrationProfile(0.032f, 0.020f, 0.001f, 0.026f, 0.001f))) {
            assertEquals(profile, GazeCalibrationProfile.decode(profile.encode()))
        }
    }

    @Test
    fun savedCalibrationWorksInMultipleNewReadingSessions() {
        val stored = GazeCalibrationProfile(-0.06420f, -0.00439f, 0.01036f, -0.034295f, 0.01036f).encode()
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
        for (stored in listOf(null, "", "2", "2,0.3,0.6,0", "2,0.3,0.6,0,0.45,0,extra",
            "2,bad,0.6,0,0.45,0", "2,NaN,0.6,0,0.45,0", "2,0.3,Infinity,0,0.45,0",
            "2,0.3,0.6,NaN,0.45,0", "2,0.3,0.6,-0.1,0.45,0", "2,0.3,0.3,0,0.3,0",
            "2,0.3,0.301,0,0.3005,0", "2,0.3,0.6,0.2,0.45,0", "2,-3.4E38,3.4E38,0,0,0",
            "2,0.3,0.6,0,NaN,0", "2,0.3,0.6,0,0.45,Infinity", "2,0.3,0.6,0,0.45,-0.1")) {
            assertNull(stored, GazeCalibrationProfile.decode(stored))
        }
    }

    @Test
    fun legacyTwoPointCalibrationRequiresMidpointValidation() {
        assertNull(GazeCalibrationProfile.decode("1,0.3,0.6,0"))
    }

    @Test
    fun midpointNearEitherEndpointOrOutsideMappingIsRejectedInBothDirections() {
        for ((top, bottom) in listOf(0f to 1f, 1f to 0f)) {
            for (position in listOf(-0.1f, 0f, 0.29f, 0.71f, 0.85f, 1f, 1.1f)) {
                val profile = GazeCalibrationProfile(top, bottom, 0f, top + position * (bottom - top), 0f)
                assertFalse("position=$position", profile.hasValidMidpoint())
                assertNull(GazeCalibrationProfile.decode(profile.encode()))
            }
            for (position in listOf(0.4f, 0.5f, 0.6f)) {
                assertTrue(GazeCalibrationProfile(top, bottom, 0f,
                    top + position * (bottom - top), 0f).hasValidMidpoint())
            }
        }
    }

    @Test
    fun midpointNoiseMustStayClearOfBottomEntryThreshold() {
        assertTrue(GazeCalibrationProfile(0f, 1f, 0.01f, 0.65f, 0.09f).hasValidMidpoint())
        assertFalse(GazeCalibrationProfile(0f, 1f, 0.01f, 0.65f, 0.11f).hasValidMidpoint())
    }

    @Test
    fun validatedMidpointDoesNotArmBottomGesture() {
        for (profile in listOf(GazeCalibrationProfile(0.3f, 0.6f, 0.001f, 0.48f, 0.002f),
            GazeCalibrationProfile(0.6f, 0.3f, 0.001f, 0.42f, 0.002f))) {
            assertTrue(profile.hasValidMidpoint())
            val detector = GazeGestureDetector()
            assertTrue(detector.calibrate(profile.top, profile.bottom, profile.noise))
            for (time in 1000L..2500L step 100L) {
                assertFalse(detector.update(GazeSample(profile.midpoint, 0f, 0f, 1f), time))
                assertFalse(detector.armed)
            }
        }
    }
}
