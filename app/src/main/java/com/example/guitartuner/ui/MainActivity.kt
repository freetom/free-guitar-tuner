package com.example.guitartuner.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.guitartuner.R
import com.example.guitartuner.databinding.ActivityMainBinding
import com.example.guitartuner.settings.SettingsActivity
import com.example.guitartuner.tuning.Instrument
import com.example.guitartuner.tuning.Tuning
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: TunerViewModel by viewModels()

    /** Dynamically created string indicator TextViews, rebuilt when instrument/tuning changes */
    private val stringViews = mutableListOf<TextView>()

    private val requestMic = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.startTuner()
        } else {
            Toast.makeText(this, getString(R.string.mic_permission_denied), Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        setupInstrumentSpinner()
        // Note: setupTuningSpinner is called inside setupInstrumentSpinner's onItemSelected
        // when the spinner fires its initial selection. rebuildStringViews is also called
        // from there. So we only call setupObservers and recording here.
        setupObservers()
        checkAndStartRecording()
    }

    // ── Instrument spinner ────────────────────────────────────────────────────

    private fun setupInstrumentSpinner() {
        val names = Instrument.ALL.map { it.name }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerInstrument.adapter = adapter

        binding.spinnerInstrument.setOnItemSelectedListener(
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    val instrument = Instrument.ALL[position]
                    viewModel.selectInstrument(instrument)
                    // Rebuild tuning spinner for the new instrument
                    setupTuningSpinner(instrument)
                    // Rebuild string indicators for new string count
                    rebuildStringViews(instrument.defaultTuning)
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        )
    }

    // ── Tuning spinner ────────────────────────────────────────────────────────

    private fun setupTuningSpinner(instrument: Instrument) {
        val names = instrument.tunings.map { it.name }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerTuning.adapter = adapter
        // Reset to 0 so the selection listener fires immediately
        binding.spinnerTuning.setSelection(0, false)

        binding.spinnerTuning.setOnItemSelectedListener(
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    val tuning = instrument.tunings[position]
                    viewModel.selectTuning(tuning)
                    rebuildStringViews(tuning)
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        )
    }

    // ── String indicator views (dynamic) ──────────────────────────────────────

    /**
     * Remove all string indicator TextViews and recreate them for [tuning].
     * This handles the switch between 6-string guitar and 4-string ukulele.
     * Layout params are applied manually because StringLabel style attributes like
     * layout_weight are LayoutParams, not TextAppearance, and can't be set via
     * setTextAppearance alone.
     */
    private fun rebuildStringViews(tuning: Tuning) {
        binding.stringIndicatorsContainer.removeAllViews()
        stringViews.clear()

        val marginPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 3f, resources.displayMetrics
        ).toInt()
        val paddingPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 8f, resources.displayMetrics
        ).toInt()

        for (i in tuning.semitones.indices) {
            val tv = TextView(this).apply {
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                lp.setMargins(marginPx, marginPx, marginPx, marginPx)
                layoutParams = lp
                gravity = android.view.Gravity.CENTER
                setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                background = ContextCompat.getDrawable(context, R.drawable.bg_string_label)
                text = Tuning.semitoneToNoteName(tuning.semitones[i])
                isSelected = false
            }
            binding.stringIndicatorsContainer.addView(tv)
            stringViews.add(tv)
        }
    }

    // ── Observers ─────────────────────────────────────────────────────────────

    private fun setupObservers() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.tunerState.collect { state -> updateUI(state) }
                }
                launch {
                    viewModel.referenceHz.collect { hz ->
                        binding.tvReference.text = getString(R.string.reference_hz, hz.roundToInt())
                    }
                }
                launch {
                    viewModel.amplitude.collect { amp ->
                        binding.signalBar.progress = (amp / 5000f * 100).toInt().coerceIn(0, 100)
                    }
                }
            }
        }
    }

    // ── UI update ─────────────────────────────────────────────────────────────

    private fun updateUI(state: TunerViewModel.TunerState) {
        when (state) {
            is TunerViewModel.TunerState.Idle -> {
                binding.tvNote.visibility = View.INVISIBLE
                binding.tvCents.visibility = View.INVISIBLE
                binding.tvFrequency.text = ""
                binding.tuningMeter.setCents(0f)
                binding.tuningMeter.setIdle(true)
                // Deselect all string indicators
                stringViews.forEach { it.isSelected = false }
            }
            is TunerViewModel.TunerState.Detected -> {
                binding.tvNote.visibility = View.VISIBLE
                binding.tvCents.visibility = View.VISIBLE
                val r = state.result
                val cents = r.cents
                binding.tvNote.text = r.noteName
                val centsInt = cents.roundToInt()
                binding.tvCents.text = when {
                    centsInt > 0 -> "+${centsInt}¢"
                    centsInt < 0 -> "${centsInt}¢"
                    else         -> "0¢"
                }
                binding.tvFrequency.text = getString(R.string.frequency_hz, r.detectedHz)
                binding.tuningMeter.setCents(cents)
                binding.tuningMeter.setIdle(false)
                updateStringIndicators(r.stringIndex)
            }
        }
    }

    private fun updateStringIndicators(activeIndex: Int) {
        val tuning = viewModel.selectedTuning.value
        stringViews.forEachIndexed { i, tv ->
            tv.text = Tuning.semitoneToNoteName(tuning.semitones[i])
            tv.isSelected = i == activeIndex
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    private fun checkAndStartRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.startTuner()
        } else {
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onResume() {
        super.onResume()
        // If the string views weren't built yet (e.g. first run before spinners fired),
        // build them now. Normally the spinner onItemSelected callbacks handle this.
        if (stringViews.isEmpty()) {
            rebuildStringViews(viewModel.selectedTuning.value)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.startTuner()
        }
    }

    override fun onPause() {
        super.onPause()
        viewModel.stopTuner()
    }
}
