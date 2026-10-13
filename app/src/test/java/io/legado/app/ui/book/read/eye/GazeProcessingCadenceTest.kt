package io.legado.app.ui.book.read.eye

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GazeProcessingCadenceTest {
    private fun observe(cadence: GazeProcessingCadence, time: Long, hasGaze: Boolean) {
        assertTrue(cadence.shouldProcess(time))
        cadence.onObservation(hasGaze, time)
    }

    @Test
    fun sustainedMissingGazeSlowsToPeriodicProbes() {
        val cadence = GazeProcessingCadence()
        for (time in 1000L..3900L step 100L) {
            observe(cadence, time, false)
            assertEquals(100L, cadence.intervalMs)
        }
        observe(cadence, 4000L, false)
        assertEquals(500L, cadence.intervalMs)
        for (time in 4100L..4400L step 100L) assertFalse(cadence.shouldProcess(time))
        for (time in 4500L..6500L step 500L) {
            observe(cadence, time, false)
            assertEquals(500L, cadence.intervalMs)
            assertFalse(cadence.shouldProcess(time + 499L))
        }
    }

    @Test
    fun validProbeImmediatelyRestoresNormalFrequency() {
        val cadence = GazeProcessingCadence()
        observe(cadence, 1000L, false)
        observe(cadence, 4000L, false)
        observe(cadence, 4500L, true)
        assertEquals(100L, cadence.intervalMs)
        assertFalse(cadence.shouldProcess(4599L))
        for (time in 4600L..8000L step 100L) observe(cadence, time, true)
        assertEquals(100L, cadence.intervalMs)
    }

    @Test
    fun briefBlinkKeepsNormalFrequency() {
        val cadence = GazeProcessingCadence()
        observe(cadence, 1000L, true)
        for (time in 1100L..1400L step 100L) {
            observe(cadence, time, false)
            assertEquals(100L, cadence.intervalMs)
        }
        observe(cadence, 1500L, true)
        observe(cadence, 1600L, true)
        assertEquals(100L, cadence.intervalMs)
    }

    @Test
    fun recoveredGazeStartsFreshMissingGazeTimeout() {
        val cadence = GazeProcessingCadence()
        for (time in 1000L..3900L step 100L) observe(cadence, time, false)
        observe(cadence, 4000L, true)
        for (time in 4100L..7000L step 100L) {
            observe(cadence, time, false)
            assertEquals(100L, cadence.intervalMs)
        }
        observe(cadence, 7100L, false)
        assertEquals(500L, cadence.intervalMs)
    }
}
