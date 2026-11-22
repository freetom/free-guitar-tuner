package com.example.guitartuner.audio

/**
 * Turns a stream of PCM chunks into a stream of filtered pitch readings.
 * Pure Kotlin (no Android classes) so it can be unit tested.
 *
 * Sliding-window analysis: every [HOP_SAMPLES] new samples (~23 ms at 44.1 kHz) the
 * most recent [WINDOW_SAMPLES] samples (~70 ms) are analysed, which gives ~43 readings
 * per second. The old design analysed disjoint 186 ms blocks (5 readings per second).
 *
 *  window 3072 samples: with tauMax = 735 (60 Hz) the YIN integration length is still
 *  2337 samples, i.e. ≥ 3 periods of the lowest guitar string.
 *
 * Pipeline: sliding window → noise gate → YIN ([PitchDetector]) → [PitchFilter].
 */
class PitchTracker(private val sampleRate: Int = 44100) {

    companion object {
        const val HOP_SAMPLES = 1024
        const val WINDOW_SAMPLES = 3072
        const val NOISE_FLOOR_RMS = 120f
    }

    /** @param rms window RMS (16-bit PCM scale); @param hz filtered pitch, or -1 if none. */
    data class Frame(val rms: Float, val hz: Float)

    @Volatile var minFreq: Float = 60f
    @Volatile var maxFreq: Float = 1400f
    @Volatile private var resetRequested = false

    private val window = FloatArray(WINDOW_SAMPLES)
    private var filled = 0
    private val detector = PitchDetector()
    private val filter = PitchFilter()
    private var lastHz = -1f

    /** Drop all filter state (e.g. after switching instrument/tuning). Thread safe. */
    fun requestReset() {
        resetRequested = true
    }

    /**
     * Feed [count] new samples. [timeSec] is a monotonic timestamp of the end of the chunk.
     */
    fun push(samples: ShortArray, count: Int, timeSec: Double): Frame {
        val n = count.coerceAtMost(WINDOW_SAMPLES)
        System.arraycopy(window, n, window, 0, WINDOW_SAMPLES - n)
        for (i in 0 until n) window[WINDOW_SAMPLES - n + i] = samples[i].toFloat()
        filled = (filled + n).coerceAtMost(WINDOW_SAMPLES)

        if (resetRequested) {
            resetRequested = false
            filter.reset()
            lastHz = -1f
        }

        val rms = rms(filled)
        if (filled < WINDOW_SAMPLES) return Frame(rms, -1f)

        if (rms < NOISE_FLOOR_RMS) {
            filter.reset()
            lastHz = -1f
            return Frame(rms, -1f)
        }

        val raw = detector.detect(window, WINDOW_SAMPLES, sampleRate, minFreq, maxFreq)
        if (raw > 0f) lastHz = filter.update(raw, timeSec)
        // Bad detection: keep sustaining the last good reading.
        return Frame(rms, lastHz)
    }

    private fun rms(count: Int): Float {
        var sum = 0.0
        for (i in WINDOW_SAMPLES - count until WINDOW_SAMPLES) sum += window[i].toDouble() * window[i]
        return Math.sqrt(sum / count).toFloat()
    }
}
