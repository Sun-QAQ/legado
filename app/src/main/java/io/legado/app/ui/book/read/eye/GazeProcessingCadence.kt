package io.legado.app.ui.book.read.eye

/** Worker-only inference pacing. Periodic probes allow tracking to resume automatically. */
internal class GazeProcessingCadence {
    var intervalMs = 100L
        private set
    private var lastFrame: Long? = null
    private var missingSince: Long? = null

    fun shouldProcess(now: Long): Boolean {
        if (lastFrame?.let { now - it < intervalMs } == true) return false
        lastFrame = now
        return true
    }

    fun onObservation(hasGaze: Boolean, now: Long) {
        if (hasGaze) {
            missingSince = null
            intervalMs = 100L
        } else {
            val since = missingSince ?: now.also { missingSince = it }
            // A normal blink or brief tracking failure must not slow down gestures.
            intervalMs = if (now - since >= 3000L) 500L else 100L
        }
    }
}
