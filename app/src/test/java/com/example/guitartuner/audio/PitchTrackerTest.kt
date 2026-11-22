package com.example.guitartuner.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.sin

class PitchTrackerTest {

    private val sr = 44100
    private val hop = PitchTracker.HOP_SAMPLES

    /** Guitar-like tone: weak fundamental, strong harmonics (worst case for octave errors). */
    private class Synth(private val sr: Int, private val harmonics: DoubleArray) {
        var phase = 0.0
        fun next(n: Int, hz: Double, amp: Double = 6000.0): ShortArray {
            val out = ShortArray(n)
            for (i in 0 until n) {
                var v = 0.0
                for (h in harmonics.indices) v += harmonics[h] * sin((h + 1) * phase)
                out[i] = (amp * v).toInt().toShort()
                phase += 2 * PI * hz / sr
            }
            return out
        }
    }

    private fun cents(a: Float, b: Double) = 1200.0 * log2(a / b)

    private class Run(val tracker: PitchTracker, val sr: Int) {
        var t = 0.0
        val hopsPerSec = sr.toDouble() / PitchTracker.HOP_SAMPLES
        fun push(s: ShortArray): PitchTracker.Frame {
            t += 1.0 / hopsPerSec
            return tracker.push(s, s.size, t)
        }
    }

    private fun detectSteady(hz: Double, harmonics: DoubleArray, min: Float, max: Float): Float {
        val tr = PitchTracker(sr).also { it.minFreq = min; it.maxFreq = max }
        val run = Run(tr, sr)
        val synth = Synth(sr, harmonics)
        var last = PitchTracker.Frame(0f, -1f)
        repeat(40) { last = run.push(synth.next(hop, hz)) }
        return last.hz
    }

    @Test
    fun steadyGuitarStringsWithinOneCent() {
        val h = doubleArrayOf(1.0, 0.8, 0.5, 0.3, 0.2)
        for (f in listOf(82.41, 110.0, 146.83, 196.0, 246.94, 329.63)) {
            val hz = detectSteady(f, h, 60f, 1400f)
            assertTrue("no pitch for $f", hz > 0)
            assertEquals("string $f", 0.0, cents(hz, f), 1.0)
        }
    }

    @Test
    fun weakFundamentalNoOctaveError() {
        val h = doubleArrayOf(0.3, 1.0, 0.7, 0.4)
        for (f in listOf(82.41, 110.0, 196.0, 329.63)) {
            val hz = detectSteady(f, h, 60f, 1400f)
            assertEquals("string $f", 0.0, cents(hz, f), 3.0)
        }
    }

    @Test
    fun ukuleleStrings() {
        val h = doubleArrayOf(1.0, 0.6, 0.3)
        for (f in listOf(196.0, 261.63, 329.63, 440.0)) {
            val hz = detectSteady(f, h, 130f, 1600f)
            assertEquals("string $f", 0.0, cents(hz, f), 1.0)
        }
    }

    @Test
    fun silenceGivesNoPitch() {
        val tr = PitchTracker(sr)
        val run = Run(tr, sr)
        repeat(20) { assertEquals(-1f, run.push(ShortArray(hop)).hz, 0f) }
    }

    @Test
    fun updateRateIsAbout43PerSecond() {
        val tr = PitchTracker(sr)
        val run = Run(tr, sr)
        val synth = Synth(sr, doubleArrayOf(1.0, 0.5))
        var valid = 0
        repeat(43) { if (run.push(synth.next(hop, 110.0)).hz > 0) valid++ }
        // after warm-up (window fill = 3 hops) almost every hop must yield a reading
        assertTrue("valid=$valid", valid >= 38)
    }

    /** Tuning up: pitch glides 100 cents flat -> in tune. Measures how far the output lags. */
    @Test
    fun followsSlowPegTurnWithSmallLag() {
        val tr = PitchTracker(sr)
        val run = Run(tr, sr)
        val synth = Synth(sr, doubleArrayOf(1.0, 0.6, 0.3))
        val target = 110.0
        val secs = 3.0
        val hops = (secs * sr / hop).toInt()
        var maxLag = 0.0
        for (i in 0 until hops) {
            val progress = i.toDouble() / hops
            val c = -100.0 * (1 - progress)           // -100 .. 0 cents
            val f = target * Math.pow(2.0, c / 1200.0)
            val fr = run.push(synth.next(hop, f))
            if (fr.hz > 0 && i > 10) {
                maxLag = maxOf(maxLag, abs(cents(fr.hz, f)))
            }
        }
        // ramp is ~33 cents/s: even 250 ms of lag would be ~8 cents
        println("max tracking error during 33 c/s glide: %.2f cents".format(maxLag))
        assertTrue("tracking error $maxLag", maxLag < 6.0)
    }

    /** New note struck: output must switch within ~100-150 ms. */
    @Test
    fun locksOnNewNoteQuickly() {
        val tr = PitchTracker(sr)
        val run = Run(tr, sr)
        val synth = Synth(sr, doubleArrayOf(1.0, 0.6, 0.3))
        repeat(20) { run.push(synth.next(hop, 110.0)) }
        var hopsToLock = -1
        for (i in 1..40) {
            val fr = run.push(synth.next(hop, 146.83))
            if (fr.hz > 0 && abs(cents(fr.hz, 146.83)) < 5.0) { hopsToLock = i; break }
        }
        val ms = hopsToLock * 1000.0 * hop / sr
        println("lock-on after new note: $hopsToLock hops = %.0f ms".format(ms))
        assertTrue("never locked", hopsToLock > 0)
        assertTrue("took $ms ms", ms <= 150.0)
    }

    /** Fine step: 10 cents sharper, should be reflected within ~300 ms. */
    @Test
    fun settlesAfterSmallStep() {
        val tr = PitchTracker(sr)
        val run = Run(tr, sr)
        val synth = Synth(sr, doubleArrayOf(1.0, 0.6, 0.3))
        val f0 = 110.0
        val f1 = f0 * Math.pow(2.0, 10.0 / 1200.0)
        repeat(20) { run.push(synth.next(hop, f0)) }
        var settledHops = -1
        for (i in 1..60) {
            val fr = run.push(synth.next(hop, f1))
            if (fr.hz > 0 && abs(cents(fr.hz, f1)) < 2.0) { settledHops = i; break }
        }
        val ms = settledHops * 1000.0 * hop / sr
        println("settle within 2 cents after 10 cent step: $settledHops hops = %.0f ms".format(ms))
        assertTrue("never settled", settledHops > 0)
        assertTrue("took $ms ms", ms <= 450.0)
    }

    @Test
    fun filterRejectsSingleOctaveGlitch() {
        val f = PitchFilter()
        var t = 0.0
        var out = 0f
        repeat(10) { t += 0.023; out = f.update(110f, t) }
        t += 0.023; out = f.update(220f, t)            // one-frame octave error
        assertEquals(0.0, cents(out, 110.0), 1.0)
        t += 0.023; out = f.update(110f, t)
        assertEquals(0.0, cents(out, 110.0), 1.0)
    }

    @Test
    fun dcOffsetDoesNotBreakDetection() {
        val tr = PitchTracker(sr)
        val run = Run(tr, sr)
        val synth = Synth(sr, doubleArrayOf(1.0, 0.5))
        var last = -1f
        repeat(30) {
            val s = synth.next(hop, 110.0)
            for (i in s.indices) s[i] = (s[i] + 4000).toShort()
            last = run.push(s).hz
        }
        assertEquals(0.0, cents(last, 110.0), 1.0)
    }

    // ── Steps 4/5 ─────────────────────────────────────────────────────────────

    private fun noise(n: Int, amp: Double, rnd: java.util.Random) =
        ShortArray(n) { (rnd.nextGaussian() * amp).toInt().toShort() }

    @Test
    fun loudNoiseIsRejectedByClarityGate() {
        val tr = PitchTracker(sr)
        val run = Run(tr, sr)
        val rnd = java.util.Random(1)
        var accepted = 0
        repeat(60) { if (run.push(noise(hop, 3000.0, rnd)).hz > 0) accepted++ }
        // white noise is loud enough to pass the level gate but has no periodicity
        assertTrue("accepted $accepted of 60 noise frames", accepted <= 3)
    }

    @Test
    fun holdsLastPitchThenDrops() {
        val tr = PitchTracker(sr)
        val run = Run(tr, sr)
        val synth = Synth(sr, doubleArrayOf(1.0, 0.5))
        repeat(20) { run.push(synth.next(hop, 110.0)) }
        val rnd = java.util.Random(2)
        // loud noise: rejected by clarity, level still high -> hold (~250 ms = ~10 hops)
        // (the window still contains some tone for the first hops, so skip those)
        var heldFrames = 0
        var dropped = -1
        for (i in 1..40) {
            val fr = run.push(noise(hop, 3000.0, rnd))
            if (fr.hz > 0) { if (fr.held) heldFrames++ }
            else { dropped = i; break }
        }
        println("held frames: $heldFrames, dropped at hop $dropped")
        assertTrue("never dropped", dropped > 0)
        assertTrue("held too long: $dropped hops", dropped <= 20)
    }

    @Test
    fun decayingNoteIsFollowedDownToReleaseLevel() {
        val tr = PitchTracker(sr)
        val run = Run(tr, sr)
        val synth = Synth(sr, doubleArrayOf(1.0, 0.5))
        repeat(20) { run.push(synth.next(hop, 110.0)) }
        // amplitude such that rms is ~90: below start floor (120) but above release (60)
        var last = PitchTracker.Frame(0f, -1f)
        repeat(20) { last = run.push(synth.next(hop, 110.0, amp = 110.0)) }
        assertTrue("rms=${last.rms}", last.rms in 60f..120f)
        assertTrue("lost the note while decaying", last.hz > 0)
        assertEquals(0.0, cents(last.hz, 110.0), 1.5)
    }

    @Test
    fun quietNoteDoesNotStartDetection() {
        val tr = PitchTracker(sr)
        val run = Run(tr, sr)
        val synth = Synth(sr, doubleArrayOf(1.0, 0.5))
        repeat(20) { assertEquals(-1f, run.push(synth.next(hop, 110.0, amp = 110.0)).hz, 0f) }
    }

    @Test
    fun octaveRobustnessSweep() {
        // many harmonic mixes, incl. very weak / missing fundamental, across the range
        val mixes = listOf(
            doubleArrayOf(1.0, 0.3, 0.1),
            doubleArrayOf(0.4, 1.0, 0.6, 0.3),
            doubleArrayOf(0.2, 1.0, 0.8, 0.5, 0.3),
            doubleArrayOf(1.0, 1.0, 0.7, 0.5, 0.3, 0.2)
        )
        var bad = 0; var total = 0
        for (m in mixes) for (f in listOf(82.41, 98.0, 110.0, 130.81, 164.81, 196.0, 220.0, 293.66, 329.63, 392.0)) {
            total++
            val hz = detectSteady(f, m, 60f, 1400f)
            if (hz <= 0 || abs(cents(hz, f)) > 5.0) {
                bad++
                println("octave sweep miss: f=$f mix=${m.toList()} -> $hz")
            }
        }
        assertTrue("$bad of $total wrong", bad == 0)
    }

    @Test
    fun clarityIsHighForCleanToneAndLowForNoise() {
        val d = PitchDetector()
        val synth = Synth(sr, doubleArrayOf(1.0, 0.5))
        val tone = synth.next(3072, 196.0).let { s -> FloatArray(s.size) { s[it].toFloat() } }
        assertTrue(d.detect(tone, tone.size, sr, 60f, 1400f) > 0)
        assertTrue("clarity ${d.clarity}", d.clarity > 0.9f)
        val rnd = java.util.Random(3)
        val n = FloatArray(3072) { (rnd.nextGaussian() * 3000).toFloat() }
        d.detect(n, n.size, sr, 60f, 1400f)
        assertTrue("noise clarity ${d.clarity}", d.clarity < 0.7f)
    }
}
