package freetom.guitartuner.audio

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
 * Pipeline: sliding window → noise gate (with hysteresis) → YIN ([PitchDetector]) →
 * clarity gate → [PitchFilter] → dropout hold.
 *
 * Dropouts: when a frame is rejected (low clarity or no pitch) the last good pitch is
 * held for [HOLD_SECONDS] so a decaying string or a brief pick transient does not blank
 * the display; after that the output goes to "no pitch".
 */
class PitchTracker(private val sampleRate: Int = 44100) {

    companion object {
        const val HOP_SAMPLES = 1024
        const val WINDOW_SAMPLES = 3072
        /** Level needed to start detecting. */
        const val NOISE_FLOOR_RMS = 120f
        /** Once locked, the level may decay to this before the signal is considered gone. */
        const val NOISE_FLOOR_RELEASE_RMS = 60f
        /** Minimum YIN clarity (1 - CMND) for a reading to be accepted. */
        const val MIN_CLARITY = 0.70f
        /** How long the last good pitch is held across rejected frames. */
        const val HOLD_SECONDS = 0.25
    }

    /**
     * @param rms window RMS (16-bit PCM scale)
     * @param hz filtered pitch, or -1 if none
     * @param clarity YIN clarity of the latest accepted reading (0..1)
     * @param held true if [hz] is a held (stale) value rather than a fresh reading
     */
    data class Frame(val rms: Float, val hz: Float, val clarity: Float = 0f, val held: Boolean = false)

    @Volatile var minFreq: Float = 60f
    @Volatile var maxFreq: Float = 1400f
    @Volatile private var resetRequested = false

    private val window = FloatArray(WINDOW_SAMPLES)
    private var filled = 0
    private val detector = PitchDetector()
    private val filter = PitchFilter()
    private var lastHz = -1f
    private var lastClarity = 0f
    private var lastGoodTime = 0.0
    private var locked = false

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
            clearState()
        }

        val rms = rms(filled)
        if (filled < WINDOW_SAMPLES) return Frame(rms, -1f)

        // Hysteresis: harder to start than to continue, so a decaying note is followed
        // further down before it is dropped.
        val floor = if (locked) NOISE_FLOOR_RELEASE_RMS else NOISE_FLOOR_RMS
        if (rms < floor) {
            clearState()
            return Frame(rms, -1f)
        }

        val raw = detector.detect(window, WINDOW_SAMPLES, sampleRate, minFreq, maxFreq)
        val clarity = detector.clarity
        if (raw > 0f && clarity >= MIN_CLARITY) {
            lastHz = filter.update(raw, timeSec)
            lastClarity = clarity
            lastGoodTime = timeSec
            locked = true
            return Frame(rms, lastHz, clarity, held = false)
        }

        // Rejected frame: hold the last good pitch briefly, then give up.
        if (lastHz > 0f && timeSec - lastGoodTime <= HOLD_SECONDS) {
            return Frame(rms, lastHz, lastClarity, held = true)
        }
        clearState()
        return Frame(rms, -1f)
    }

    private fun clearState() {
        filter.reset()
        lastHz = -1f
        lastClarity = 0f
        locked = false
    }

    private fun rms(count: Int): Float {
        var sum = 0.0
        for (i in WINDOW_SAMPLES - count until WINDOW_SAMPLES) sum += window[i].toDouble() * window[i]
        return Math.sqrt(sum / count).toFloat()
    }
}
