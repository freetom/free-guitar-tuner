package com.example.guitartuner.tuning

/**
 * A tuning is a named set of string targets.
 * Notes are expressed as semitone offsets from A4 (MIDI note 69).
 *
 * Standard A4 = 440 Hz → frequency = referenceHz * 2^(semitone/12)
 *
 * Index [0] = lowest string, [stringCount-1] = highest string.
 */
data class Tuning(
    val name: String,
    /** Semitone offset from A4 for each string [0]=lowest string, [n-1]=highest string */
    val semitones: IntArray
) {
    val stringCount: Int get() = semitones.size

    companion object {
        /** Note names for display (chromatic, starting from C) */
        val NOTE_NAMES = arrayOf("C","C#","D","D#","E","F","F#","G","G#","A","A#","B")

        fun semitoneToNoteName(semitone: Int): String {
            // midi note = 69 + semitone; octave = midi / 12 - 1; note = midi % 12
            val midi = 69 + semitone
            val octave = midi / 12 - 1
            val noteIdx = ((midi % 12) + 12) % 12
            return "${NOTE_NAMES[noteIdx]}$octave"
        }
    }
}

/**
 * An instrument groups a display name, a frequency range hint for pitch detection,
 * and a list of supported tunings.
 *
 * [minFreq] / [maxFreq] constrain YIN search to the instrument's playable range.
 */
data class Instrument(
    val name: String,
    val minFreq: Float,
    val maxFreq: Float,
    val tunings: List<Tuning>,
    val defaultTuningIndex: Int = 0
) {
    val defaultTuning: Tuning get() = tunings[defaultTuningIndex]

    companion object {

        // ── Guitar ────────────────────────────────────────────────────────────
        // Semitone offsets relative to A4=69:  note midi - 69
        // E2=40→-29, A2=45→-24, D3=50→-19, G3=55→-14, B3=59→-10, E4=64→-5
        private val GUITAR_STANDARD      = Tuning("Standard (EADGBe)",    intArrayOf(-29, -24, -19, -14, -10, -5))
        private val GUITAR_DROP_D        = Tuning("Drop D (DADGBe)",      intArrayOf(-31, -24, -19, -14, -10, -5))
        private val GUITAR_OPEN_G        = Tuning("Open G (DGDGBd)",      intArrayOf(-31, -26, -19, -14, -10, -17))
        private val GUITAR_OPEN_D        = Tuning("Open D (DADf#Ad)",     intArrayOf(-31, -24, -19, -15, -12, -17))
        private val GUITAR_OPEN_E        = Tuning("Open E (EBEg#Be)",     intArrayOf(-29, -22, -17, -13, -10,  -5))
        private val GUITAR_DADGAD        = Tuning("DADGAD",               intArrayOf(-31, -24, -19, -14, -12, -17))
        private val GUITAR_HALF_DOWN     = Tuning("Half Step Down (Eb)",  intArrayOf(-30, -25, -20, -15, -11,  -6))
        private val GUITAR_FULL_DOWN     = Tuning("Full Step Down (D)",   intArrayOf(-31, -26, -21, -16, -12,  -7))
        private val GUITAR_DROP_C        = Tuning("Drop C (CGCFAd)",      intArrayOf(-33, -26, -21, -16, -12, -17))
        private val GUITAR_OPEN_A        = Tuning("Open A (EAEAc#e)",     intArrayOf(-29, -24, -17, -12,  -9,  -5))
        // Open C: C2, G2, C3, G3, C4, E4
        //   C2=MIDI36→-33, G2=MIDI43→-26, C3=MIDI48→-21, G3=MIDI55→-14, C4=MIDI60→-9, E4=MIDI64→-5
        private val GUITAR_OPEN_C        = Tuning("Open C (CGCGCe)",      intArrayOf(-33, -26, -21, -14, -9, -5))

        val GUITAR = Instrument(
            name = "Guitar",
            minFreq = 60f,   // below open low E2 (82 Hz), covers slack/drop tunings
            maxFreq = 1400f,
            tunings = listOf(
                GUITAR_STANDARD, GUITAR_DROP_D, GUITAR_OPEN_G, GUITAR_OPEN_D,
                GUITAR_OPEN_E, GUITAR_DADGAD, GUITAR_HALF_DOWN, GUITAR_FULL_DOWN,
                GUITAR_DROP_C, GUITAR_OPEN_A, GUITAR_OPEN_C
            ),
            defaultTuningIndex = 0
        )

        // ── Ukulele ───────────────────────────────────────────────────────────
        // Standard soprano/concert/tenor ukulele: G4, C4, E4, A4
        // Semitone offsets from A4=69:
        //   G4 = MIDI 67 → -2
        //   C4 = MIDI 60 → -9
        //   E4 = MIDI 64 → -5
        //   A4 = MIDI 69 →  0
        // Note: string order [0]=G(low-or-high), [1]=C, [2]=E, [3]=A
        private val UKE_STANDARD    = Tuning("Standard (gCEA)",    intArrayOf(-2, -9, -5, 0))
        // Low G tuning: G3 (MIDI 55 → -14) instead of G4
        private val UKE_LOW_G       = Tuning("Low G (GCEA)",       intArrayOf(-14, -9, -5, 0))
        // D tuning (older soprano): A4, D4, F#4, B4
        //   A4→0, D4=MIDI62→-7, F#4=MIDI66→-3, B4=MIDI71→+2
        private val UKE_D_TUNING    = Tuning("D Tuning (ADF#B)",   intArrayOf(0, -7, -3, 2))
        // Open C: G4, C4, E4, G4
        private val UKE_OPEN_C      = Tuning("Open C (gCEG)",      intArrayOf(-2, -9, -5, -2))
        // Baritone ukulele: D3, G3, B3, E4 (like top 4 strings of guitar)
        //   D3=MIDI50→-19, G3=MIDI55→-14, B3=MIDI59→-10, E4=MIDI64→-5
        private val UKE_BARITONE    = Tuning("Baritone (DGBE)",    intArrayOf(-19, -14, -10, -5))
        // Canadian / Bb tuning: F3, Bb3, D4, G4 (a whole step down from standard)
        //   F3=MIDI53→-16, Bb3=MIDI58→-11, D4=MIDI62→-7, G4=MIDI67→-2
        private val UKE_BB_TUNING   = Tuning("Bb Tuning (FBbDG)",  intArrayOf(-16, -11, -7, -2))

        val UKULELE = Instrument(
            name = "Ukulele",
            minFreq = 130f,  // D3 (baritone low) ~147 Hz; some slack headroom below
            maxFreq = 1600f, // High frets on A string
            tunings = listOf(
                UKE_STANDARD, UKE_LOW_G, UKE_D_TUNING, UKE_OPEN_C,
                UKE_BARITONE, UKE_BB_TUNING
            ),
            defaultTuningIndex = 0
        )

        /** All supported instruments, in display order */
        val ALL: List<Instrument> = listOf(GUITAR, UKULELE)
    }
}
