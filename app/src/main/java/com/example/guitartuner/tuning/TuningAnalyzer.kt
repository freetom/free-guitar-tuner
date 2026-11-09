package com.example.guitartuner.tuning

import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Given a detected frequency and a reference pitch (A4 in Hz), determines
 * which string target is closest and how many cents off the detected pitch is.
 *
 * Works with any [Tuning] regardless of string count (guitar, ukulele, bass, etc.).
 */
object TuningAnalyzer {

    /**
     * Result of analyzing a detected frequency against a tuning.
     *
     * @param stringIndex 0 = lowest string, (stringCount-1) = highest string
     * @param noteName    target note name, e.g. "E2"
     * @param cents       deviation in cents: negative = flat, positive = sharp
     * @param targetHz    exact target frequency
     * @param detectedHz  what was detected
     */
    data class Result(
        val stringIndex: Int,
        val noteName: String,
        val cents: Float,
        val targetHz: Float,
        val detectedHz: Float
    )

    /**
     * Convert semitone offset from A4 to Hz given [referenceHz] for A4.
     */
    fun semitoneToHz(semitone: Int, referenceHz: Float): Float =
        referenceHz * 2f.pow(semitone / 12f)

    /**
     * Return cents deviation of [detectedHz] from [targetHz].
     * 100 cents = 1 semitone.
     */
    fun centsOff(detectedHz: Float, targetHz: Float): Float {
        if (targetHz <= 0f || detectedHz <= 0f) return 0f
        return (1200f * log2(detectedHz / targetHz))
    }

    /**
     * Find the closest string in [tuning] to [detectedHz].
     * Considers octave equivalents within ±50 cents of any harmonic/subharmonic.
     */
    fun analyze(detectedHz: Float, tuning: Tuning, referenceHz: Float): Result {
        var bestIndex = 0
        var bestCents = Float.MAX_VALUE
        var bestTargetHz = referenceHz

        tuning.semitones.forEachIndexed { i, semi ->
            val target = semitoneToHz(semi, referenceHz)
            // Check fundamental and ±1 octave equivalents
            for (octaveShift in -1..1) {
                val shiftedTarget = target * 2f.pow(octaveShift.toFloat())
                val c = abs(centsOff(detectedHz, shiftedTarget))
                if (c < abs(bestCents)) {
                    bestCents = centsOff(detectedHz, shiftedTarget)
                    bestIndex = i
                    bestTargetHz = shiftedTarget
                }
            }
        }

        return Result(
            stringIndex = bestIndex,
            noteName = Tuning.semitoneToNoteName(tuning.semitones[bestIndex]),
            cents = bestCents,
            targetHz = bestTargetHz,
            detectedHz = detectedHz
        )
    }
}
