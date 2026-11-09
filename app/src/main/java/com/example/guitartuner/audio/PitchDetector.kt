package com.example.guitartuner.audio

import kotlin.math.abs

/**
 * YIN pitch detection algorithm.
 * Reference: de Cheveigné & Kawahara (2002), "YIN, a fundamental frequency estimator
 * for speech and music", JASA.
 *
 * Improvements over the naive implementation:
 *  - Tighter threshold (0.10) reduces false positives
 *  - Local minimum search: only accept a tau if it's a true local dip, not just
 *    the first crossing (avoids octave errors on rich harmonics)
 *  - Constrained tau search range to the instrument's frequency range (passed in)
 *  - Harmonic confirmation: before returning a candidate tau, its strongest
 *    sub-harmonic (tau/2) is checked; if the sub-harmonic also passes threshold
 *    we prefer it, correcting upward octave errors common with ukulele and
 *    guitar harmonics.
 */
object PitchDetector {

    private const val YIN_THRESHOLD = 0.10f

    /**
     * Detect the fundamental frequency in [buffer].
     *
     * @param buffer     PCM 16-bit samples
     * @param sampleRate Sampling rate in Hz (typically 44100)
     * @param minFreq    Lower bound of expected frequency range for the instrument
     * @param maxFreq    Upper bound of expected frequency range for the instrument
     * @return detected frequency in Hz, or -1f if no reliable pitch found
     */
    fun detect(
        buffer: ShortArray,
        sampleRate: Int,
        minFreq: Float = 60f,
        maxFreq: Float = 1400f
    ): Float {
        val n = buffer.size / 2
        if (n < 2) return -1f

        val tauMin = (sampleRate / maxFreq).toInt().coerceAtLeast(2)
        val tauMax = (sampleRate / minFreq).toInt().coerceAtMost(n - 1)

        // Step 1 & 2: difference function + CMND in one pass
        val cmnd = FloatArray(tauMax + 1)
        cmnd[0] = 1f
        var runningSum = 0.0

        for (tau in 1..tauMax) {
            var sum = 0.0
            for (j in 0 until n - tauMax) {   // fixed window avoids index OOB
                val delta = buffer[j].toDouble() - buffer[j + tau].toDouble()
                sum += delta * delta
            }
            runningSum += sum
            cmnd[tau] = if (runningSum == 0.0) 1f
                        else (sum * tau / runningSum).toFloat()
        }

        // Step 3: find the best local minimum below threshold in the instrument range.
        // Strategy: find ALL dips below threshold, pick the one with lowest CMND value.
        // This avoids octave errors from picking the first (often wrong) crossing.
        var bestTau = -1
        var bestVal = Float.MAX_VALUE

        var tau = tauMin
        while (tau <= tauMax - 1) {
            if (cmnd[tau] < YIN_THRESHOLD) {
                // Walk to the bottom of this dip
                var t = tau
                while (t + 1 <= tauMax && cmnd[t + 1] < cmnd[t]) t++
                if (cmnd[t] < bestVal) {
                    bestVal = cmnd[t]
                    bestTau = t
                }
                tau = t + 1
            } else {
                tau++
            }
        }

        if (bestTau != -1) {
            // Harmonic confirmation: check if tau/2 (one octave up) also passes threshold.
            // If so, prefer the sub-harmonic to correct upward-octave errors, common
            // when detecting notes with strong second harmonics (ukulele, steel strings).
            val halfTau = bestTau / 2
            if (halfTau >= tauMin && halfTau < cmnd.size && cmnd[halfTau] < YIN_THRESHOLD) {
                // Refine the sub-harmonic candidate instead
                val refined = parabolicInterp(cmnd, halfTau, tauMax)
                return sampleRate / refined
            }
            val refined = parabolicInterp(cmnd, bestTau, tauMax)
            return sampleRate / refined
        }

        // Fallback: global minimum in range if confidence is reasonable
        var minVal = Float.MAX_VALUE
        var minTau = tauMin
        for (t in tauMin..tauMax) {
            if (cmnd[t] < minVal) { minVal = cmnd[t]; minTau = t }
        }
        return if (minVal < 0.35f) sampleRate / parabolicInterp(cmnd, minTau, tauMax) else -1f
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
