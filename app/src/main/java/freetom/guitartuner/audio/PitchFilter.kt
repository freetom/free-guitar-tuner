package freetom.guitartuner.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * Time-based smoothing of a stream of pitch readings. Replaces the old
 * "median(7 frames) + per-frame EMA" chain, which added >1 s of lag.
 *
 * Works in the cents domain (1200 * log2(Hz)), so deviations mean the same thing at
 * every pitch:
 *
 *  1. **Jump gate** – a reading further than [jumpCents] from the current output is not
 *     fed to the filter right away. If the *next* reading agrees with it (within
 *     [jumpAgreeCents]) it is a real new note: the filter snaps to it. If not, it was
 *     an outlier (typically an octave glitch) and is discarded. A new note is therefore
 *     locked in after 2 readings instead of waiting for a median window to turn over.
 *  2. **Short median** ([medianSize] readings) – removes single-frame spikes within
 *     the normal range.
 *  3. **One-pole low-pass** with time constant [tauSeconds]; alpha is derived from the
 *     elapsed time, so behaviour does not depend on the update rate.
 */
class PitchFilter(
    private val medianSize: Int = 5,
    private val tauSeconds: Float = 0.10f,
    private val jumpCents: Float = 50f,
    private val jumpAgreeCents: Float = 30f
) {
    private val recent = ArrayDeque<Float>(medianSize + 1)
    private var smoothedCents = 0f
    private var lastTime = 0.0
    private var initialised = false
    private var pendingCents = 0f
    private var hasPending = false

    /** Current filtered pitch in Hz, or -1 if nothing has been fed since the last reset. */
    val currentHz: Float
        get() = if (initialised) fromCents(smoothedCents) else -1f

    fun reset() {
        recent.clear()
        initialised = false
        hasPending = false
    }

    /**
     * Feed a raw reading.
     *
     * @param hz       detected frequency (> 0)
     * @param timeSec  monotonic timestamp of the reading, in seconds
     * @return filtered frequency in Hz
     */
    fun update(hz: Float, timeSec: Double): Float {
        val c = toCents(hz)

        if (!initialised) {
            recent.clear()
            recent.addLast(c)
            smoothedCents = c
            lastTime = timeSec
            initialised = true
            hasPending = false
            return hz
        }

        if (abs(c - smoothedCents) > jumpCents) {
            if (hasPending && abs(c - pendingCents) <= jumpAgreeCents) {
                // Two consecutive readings agree on a new pitch: snap.
                recent.clear()
                recent.addLast(c)
                smoothedCents = (c + pendingCents) / 2f
                lastTime = timeSec
                hasPending = false
                return fromCents(smoothedCents)
            }
            pendingCents = c
            hasPending = true
            lastTime = timeSec
            return fromCents(smoothedCents)   // hold until confirmed
        }
        hasPending = false

        recent.addLast(c)
        if (recent.size > medianSize) recent.removeFirst()
        val sorted = recent.sorted()
        val median = sorted[sorted.size / 2]

        val dt = (timeSec - lastTime).coerceAtLeast(0.0)
        lastTime = timeSec
        val alpha = 1f - exp(-dt / tauSeconds).toFloat()
        smoothedCents += alpha * (median - smoothedCents)
        return fromCents(smoothedCents)
    }

    private fun toCents(hz: Float): Float = (1200.0 * ln(hz.toDouble()) / LN2).toFloat()
    private fun fromCents(c: Float): Float = Math.pow(2.0, c / 1200.0).toFloat()

    private companion object {
        val LN2 = ln(2.0)
    }
}
