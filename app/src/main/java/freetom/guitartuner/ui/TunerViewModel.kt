package freetom.guitartuner.ui

import android.app.Application
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.preference.PreferenceManager
import freetom.guitartuner.audio.AudioRecorder
import freetom.guitartuner.tuning.Instrument
import freetom.guitartuner.tuning.Tuning
import freetom.guitartuner.tuning.TuningAnalyzer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

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
        // Smoothing/median filtering already happened in the audio pipeline (PitchFilter).
        viewModelScope.launch {
            recorder.pitchHz.collectLatest { hz ->
                if (hz <= 0f) {
                    _tunerState.value = TunerState.Idle
                    return@collectLatest
                }
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
        recorder.resetFilter()
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
