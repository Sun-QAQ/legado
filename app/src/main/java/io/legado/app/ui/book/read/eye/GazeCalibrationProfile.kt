package io.legado.app.ui.book.read.eye

/** Versioned, eye-relative calibration; no images or in-progress samples are stored. */
internal data class GazeCalibrationProfile(val top: Float, val bottom: Float, val noise: Float) {
    fun encode(): String = "1,$top,$bottom,$noise"

    companion object {
        fun decode(value: String?): GazeCalibrationProfile? {
            val fields = value?.split(',') ?: return null
            if (fields.size != 4 || fields[0] != "1") return null
            val top = fields[1].toFloatOrNull() ?: return null
            val bottom = fields[2].toFloatOrNull() ?: return null
            val noise = fields[3].toFloatOrNull() ?: return null
            if (noise < 0f || !(bottom - top).isFinite()
                || !GazeGestureDetector().calibrate(top, bottom, noise)) return null
            return GazeCalibrationProfile(top, bottom, noise)
        }
    }
}
