package com.example.guitartuner.ui

import android.app.Application
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.preference.PreferenceManager
import com.example.guitartuner.audio.AudioRecorder
import com.example.guitartuner.tuning.Instrument
import com.example.guitartuner.tuning.Tuning
import com.example.guitartuner.tuning.TuningAnalyzer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.log2

class TunerViewModel(app: Application) : AndroidViewModel(app) {

    private val recorder = AudioRecorder()
    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(app)

    // ── Instrument selection ──────────────────────────────────────────────────
    private val _selectedInstrument = MutableStateFlow(Instrument.GUITAR)
    val selectedInstrument: StateFlow<Instrument> = _selectedInstrument

    // ── Tuning selection (within the active instrument) ───────────────────────
    private val _selectedTuning = MutableStateFlow(Instrument.GUITAR.defaultTuning)
    val selectedTuning: StateFlow<Tuning> = _selectedTuning

    // ── Reference pitch ───────────────────────────────────────────────────────
    private val _referenceHz = MutableStateFlow(prefs.getFloat("reference_hz", 440f))
    val referenceHz: StateFlow<Float> = _referenceHz

    // ── Tuner output ──────────────────────────────────────────────────────────
    private val _tunerState = MutableStateFlow<TunerState>(TunerState.Idle)
    val tunerState: StateFlow<TunerState> = _tunerState

    val amplitude: StateFlow<Float> = recorder.amplitude

    // ── Smoothing ─────────────────────────────────────────────────────────────
    // Exponential smoothing — smoothed in Hz; large jumps snap immediately.
    private var smoothedHz = -1f
    // Alpha: fraction of new reading blended in each frame.
    // 0.2 is slightly smoother than the previous 0.25, reduces needle jitter.
    private val alpha = 0.20f
    // If new reading jumps more than this many cents, snap immediately (new note struck).
    private val snapThresholdCents = 80f

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "reference_hz") {
            _referenceHz.value = prefs.getFloat("reference_hz", 440f)
            resetSmoothing()
        }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        // Seed the recorder with the default instrument frequency range
        recorder.setFrequencyRange(
            _selectedInstrument.value.minFreq,
            _selectedInstrument.value.maxFreq
        )
        observePitch()
    }

    private fun observePitch() {
        viewModelScope.launch {
            recorder.pitchHz.collectLatest { rawHz ->
                if (rawHz <= 0f) {
                    resetSmoothing()
                    _tunerState.value = TunerState.Idle
                    return@collectLatest
                }

                val hz = if (smoothedHz <= 0f) {
                    rawHz  // first reading — snap directly
                } else {
                    val centsDiff = abs(1200f * log2(rawHz / smoothedHz))
                    if (centsDiff > snapThresholdCents) rawHz  // new note — snap
                    else smoothedHz * (1f - alpha) + rawHz * alpha
                }
                smoothedHz = hz

                val result = TuningAnalyzer.analyze(hz, _selectedTuning.value, _referenceHz.value)
                _tunerState.value = TunerState.Detected(result)
            }
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun startTuner() {
        recorder.start()
    }

    fun stopTuner() {
        recorder.stop()
        resetSmoothing()
        _tunerState.value = TunerState.Idle
    }

    /**
     * Switch to a different instrument.
     * Resets the tuning to the instrument's default and updates the pitch detector range.
     */
    fun selectInstrument(instrument: Instrument) {
        _selectedInstrument.value = instrument
        _selectedTuning.value = instrument.defaultTuning
        recorder.setFrequencyRange(instrument.minFreq, instrument.maxFreq)
        resetSmoothing()
    }

    /**
     * Switch to a different tuning within the current instrument.
     */
    fun selectTuning(tuning: Tuning) {
        _selectedTuning.value = tuning
        resetSmoothing()
    }

    private fun resetSmoothing() {
        smoothedHz = -1f
    }

    override fun onCleared() {
        recorder.stop()
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        super.onCleared()
    }

    sealed class TunerState {
        object Idle : TunerState()
        data class Detected(val result: TuningAnalyzer.Result) : TunerState()
    }
}
