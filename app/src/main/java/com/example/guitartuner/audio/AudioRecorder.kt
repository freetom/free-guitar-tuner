package com.example.guitartuner.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Continuously records mono 16-bit PCM audio and emits filtered pitch readings.
 *
 * This class is only the Android I/O shell: it reads [PitchTracker.HOP_SAMPLES]
 * (1024 samples, ~23 ms) at a time and hands them to a [PitchTracker], which performs
 * the sliding-window analysis and filtering. Result: ~43 pitch updates per second.
 */
class AudioRecorder {

    companion object {
        const val SAMPLE_RATE = 44100
    }

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    @Volatile private var running = false

    private val tracker = PitchTracker(SAMPLE_RATE)

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
        tracker.minFreq = min
        tracker.maxFreq = max
    }

    /** Forget filter history, e.g. after the instrument or tuning changed. */
    fun resetFilter() {
        tracker.requestReset()
    }

    fun start() {
        if (isRecording) return

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferBytes = maxOf(minBuf, PitchTracker.HOP_SAMPLES * 2 * 4)

        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferBytes
        )
        audioRecord = record
        record.startRecording()
        tracker.requestReset()
        running = true

        recordingThread = Thread {
            val hop = ShortArray(PitchTracker.HOP_SAMPLES)
            while (running) {
                // Read exactly one hop
                var got = 0
                while (running && got < hop.size) {
                    val r = record.read(hop, got, hop.size - got)
                    if (r < 0) return@Thread   // stopped / error
                    got += r
                }
                if (got < hop.size) break

                val frame = tracker.push(hop, hop.size, System.nanoTime() / 1e9)
                _amplitude.value = frame.rms
                _pitchHz.value = frame.hz
            }
        }.also {
            it.name = "AudioRecorder"
            it.isDaemon = true
            it.start()
        }
    }

    fun stop() {
        running = false
        recordingThread?.let {
            it.interrupt()
            try { it.join(200) } catch (_: InterruptedException) {}
        }
        recordingThread = null
        audioRecord?.let {
            try { it.stop() } catch (_: IllegalStateException) {}
            it.release()
        }
        audioRecord = null
        _pitchHz.value = -1f
        _amplitude.value = 0f
    }
}
