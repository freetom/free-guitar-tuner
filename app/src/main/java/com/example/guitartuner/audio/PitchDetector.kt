package com.example.guitartuner.audio

import kotlin.math.abs

/**
 * YIN pitch detection algorithm.
 * Reference: de Cheveigné & Kawahara (2002), "YIN, a fundamental frequency estimator
 * for speech and music", JASA.
 *
 * Implementation notes:
 *  - Operates on a float window; the whole window is used (the integration length is
 *    `length - tauMax`, so every sample contributes).
 *  - The DC offset (common on phone microphones) is removed before analysis.
 *  - Float arithmetic in the O(W * tauMax) inner loop; only the running sums used for
 *    normalisation are kept in double precision.
 *  - Scratch buffers are reused between calls, so an instance is NOT thread safe.
 *  - Tau search is constrained to the instrument's frequency range.
 *  - First dip below the threshold wins (walked down to its local minimum).
 *  - Octave check: a strong 2nd harmonic can create a false first dip at half the true
 *    period. If the neighbourhood of 2*tau (+-[OCTAVE_SEARCH] samples) is clearly deeper
 *    than the chosen dip (by [OCTAVE_MARGIN]) the longer period is used instead. On a
 *    clean periodic signal all multiples of the period are equally deep, so the margin
 *    keeps the shorter (correct) period.
 *  - [clarity] (1 - CMND at the chosen lag, 0..1) is exposed after every call so callers
 *    can gate on confidence.
 */
class PitchDetector {

    companion object {
        private const val YIN_THRESHOLD = 0.10f
        private const val FALLBACK_THRESHOLD = 0.35f
        private const val OCTAVE_MARGIN = 0.03f
        private const val OCTAVE_SEARCH = 2
        private const val HALF_RELAXED_THRESHOLD = 0.20f
    }

    /** Clarity (0..1) of the last [detect] result; 0 when nothing was detected. */
    var clarity: Float = 0f
        private set

    private var cmnd = FloatArray(0)
    private var work = FloatArray(0)

    /**
     * Detect the fundamental frequency in the first [length] samples of [buffer].
     *
     * @param buffer     samples (any scale, 16-bit PCM scale is fine)
     * @param length     number of valid samples in [buffer]
     * @param sampleRate Sampling rate in Hz (typically 44100)
     * @param minFreq    Lower bound of expected frequency range for the instrument
     * @param maxFreq    Upper bound of expected frequency range for the instrument
     * @return detected frequency in Hz, or -1f if no reliable pitch found
     */
    fun detect(
        buffer: FloatArray,
        length: Int,
        sampleRate: Int,
        minFreq: Float = 60f,
        maxFreq: Float = 1400f
    ): Float {
        clarity = 0f
        if (length < 4) return -1f

        // The integration window must stay at least as long as the largest lag.
        val tauMin = (sampleRate / maxFreq).toInt().coerceAtLeast(2)
        val tauMax = (sampleRate / minFreq).toInt().coerceAtMost(length / 2)
        if (tauMax <= tauMin + 1) return -1f
        val w = length - tauMax

        if (work.size < length) work = FloatArray(length)
        if (cmnd.size < tauMax + 1) cmnd = FloatArray(tauMax + 1)
        val x = work
        val cmnd = cmnd

        // DC removal
        var mean = 0.0
        for (i in 0 until length) mean += buffer[i]
        val m = (mean / length).toFloat()
        for (i in 0 until length) x[i] = buffer[i] - m

        // Step 1 & 2: difference function + cumulative mean normalised difference
        cmnd[0] = 1f
        var runningSum = 0.0
        for (tau in 1..tauMax) {
            var sum = 0f
            for (j in 0 until w) {
                val d = x[j] - x[j + tau]
                sum += d * d
            }
            runningSum += sum
            cmnd[tau] = if (runningSum == 0.0) 1f
                        else (sum.toDouble() * tau / runningSum).toFloat()
        }

        // Step 3: take the FIRST dip below the threshold (standard YIN) and walk to its
        // bottom. Later dips are multiples of the period and are numerically equivalent,
        // so picking the deepest one would select an arbitrary sub-octave.
        var bestTau = -1
        var tau = tauMin
        while (tau <= tauMax - 1) {
            if (cmnd[tau] < YIN_THRESHOLD) {
                var t = tau
                while (t + 1 <= tauMax && cmnd[t + 1] < cmnd[t]) t++
                bestTau = t
                break
            }
            tau++
        }

        if (bestTau != -1) {
            // Octave check: is the period at ~2*tau clearly better than this dip?
            val twice = bestTau * 2
            if (twice + OCTAVE_SEARCH < tauMax) {
                var t2 = twice - OCTAVE_SEARCH
                for (t in twice - OCTAVE_SEARCH..twice + OCTAVE_SEARCH) if (cmnd[t] < cmnd[t2]) t2 = t
                if (cmnd[t2] < YIN_THRESHOLD && cmnd[t2] < cmnd[bestTau] - OCTAVE_MARGIN) {
                    while (t2 + 1 <= tauMax && cmnd[t2 + 1] < cmnd[t2]) t2++
                    while (t2 - 1 >= tauMin && cmnd[t2 - 1] < cmnd[t2]) t2--
                    bestTau = t2
                }
            }
            // Sub-octave guard: the true period may have just missed the threshold while a
            // multiple of it passed. Look around tau/2 with a relaxed threshold.
            val half = bestTau / 2
            if (half - OCTAVE_SEARCH >= tauMin) {
                var h = half - OCTAVE_SEARCH
                for (t in half - OCTAVE_SEARCH..half + OCTAVE_SEARCH) if (cmnd[t] < cmnd[h]) h = t
                if (cmnd[h] < HALF_RELAXED_THRESHOLD && cmnd[h] <= cmnd[bestTau] + OCTAVE_MARGIN) {
                    bestTau = h
                }
            }
            clarity = (1f - cmnd[bestTau]).coerceIn(0f, 1f)
            return sampleRate / parabolicInterp(cmnd, bestTau, tauMax)
        }

        // Fallback: global minimum in range if confidence is reasonable
        var minVal = Float.MAX_VALUE
        var minTau = tauMin
        for (t in tauMin..tauMax) {
            if (cmnd[t] < minVal) { minVal = cmnd[t]; minTau = t }
        }
        if (minVal >= FALLBACK_THRESHOLD) return -1f
        clarity = (1f - minVal).coerceIn(0f, 1f)
        return sampleRate / parabolicInterp(cmnd, minTau, tauMax)
    }

    private fun parabolicInterp(array: FloatArray, tau: Int, max: Int): Float {
        if (tau <= 0 || tau >= max) return tau.toFloat()
        val s0 = array[tau - 1]
        val s1 = array[tau]
        val s2 = array[tau + 1]
        val denom = 2f * (s0 - 2f * s1 + s2)
        return if (abs(denom) < 1e-6f) tau.toFloat()
               else (tau + (s0 - s2) / denom).coerceIn((tau - 1).toFloat(), (tau + 1).toFloat())
    }
}
