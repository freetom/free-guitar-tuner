package com.example.guitartuner.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Continuously records mono 16-bit PCM audio and emits detected pitches.
 *
 * Buffer sizing rationale:
 *  - Lowest guitar note: E2 = 82 Hz → period = 12.2 ms → need many cycles for YIN
 *  - 8192 samples @ 44100 Hz = 185 ms = ~15 cycles of E2 — reliable detection
 *  - This is a power-of-2 so FFT-style operations stay efficient
 *  - For ukulele (lowest: G3 ~196 Hz) 8192 samples gives ~32 cycles — excellent
 *
 * Stabilization pipeline:
 *  1. Noise gate: skip detection if RMS is below floor
 *  2. YIN pitch detection (time-domain, instrument-aware frequency bounds)
 *  3. Median filter (window=7) — eliminates single-frame and multi-frame outliers
 *     without introducing lag (median is robust to spikes, unlike mean)
 *  4. Exponential smoothing in TunerViewModel for display continuity
 */
class AudioRecorder {

    companion object {
        const val SAMPLE_RATE = 44100
        private const val BUFFER_SAMPLES = 8192   // 185 ms — handles low E2 reliably
        private const val NOISE_FLOOR_RMS = 120f  // slightly more sensitive than 150
        // Median filter window — wider window = more outlier rejection, still < 1 frame lag
        private const val MEDIAN_WINDOW = 7
    }

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null

    // Instrument frequency bounds — updated when the instrument changes
    @Volatile private var minFreq: Float = 60f
    @Volatile private var maxFreq: Float = 1400f

    private val _pitchHz = MutableStateFlow(-1f)
    val pitchHz: StateFlow<Float> = _pitchHz

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude

    val isRecording: Boolean
        get() = audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING

    /**
     * Update the frequency search bounds for the active instrument.
     * Safe to call while recording — the next detection frame will pick them up.
     */
    fun setFrequencyRange(min: Float, max: Float) {
        minFreq = min
        maxFreq = max
    }

    fun start() {
        if (isRecording) return

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferBytes = maxOf(minBuf, BUFFER_SAMPLES * 2)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferBytes
        )
        audioRecord?.startRecording()

        val buffer = ShortArray(BUFFER_SAMPLES)
        val recentPitches = ArrayDeque<Float>(MEDIAN_WINDOW)

        recordingThread = Thread {
            while (!Thread.currentThread().isInterrupted && isRecording) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
                if (read <= 0) continue

                val rms = rms(buffer, read)
                _amplitude.value = rms

                if (rms < NOISE_FLOOR_RMS) {
                    recentPitches.clear()
                    _pitchHz.value = -1f
                    continue
                }

                val raw = PitchDetector.detect(buffer, SAMPLE_RATE, minFreq, maxFreq)

                if (raw > 0f) {
                    // Add to median window
                    if (recentPitches.size >= MEDIAN_WINDOW) recentPitches.removeFirst()
                    recentPitches.addLast(raw)

                    // Emit median of window — robust against outlier frames
                    val sorted = recentPitches.sorted()
                    _pitchHz.value = sorted[sorted.size / 2]
                }
                // Bad detection — don't clear window; sustain last good reading briefly
            }
        }.also {
            it.name = "AudioRecorder"
            it.isDaemon = true
            it.start()
        }
    }

    fun stop() {
        recordingThread?.interrupt()
        recordingThread = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        _pitchHz.value = -1f
        _amplitude.value = 0f
    }

    private fun rms(buf: ShortArray, count: Int): Float {
        var sum = 0.0
        for (i in 0 until count) sum += buf[i].toDouble() * buf[i].toDouble()
        return Math.sqrt(sum / count).toFloat()
    }
}
