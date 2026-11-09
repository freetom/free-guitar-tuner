package com.example.guitartuner.settings

import android.os.Bundle
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import android.preference.PreferenceManager
import com.example.guitartuner.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    // Seekbar covers 400–480 Hz (80 steps, 1 Hz each)
    private val minHz = 400

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)

        fun currentHz(): Int = prefs.getFloat("reference_hz", 440f).toInt()

        fun applyHz(hz: Int) {
            val clamped = hz.coerceIn(400, 480)
            prefs.edit().putFloat("reference_hz", clamped.toFloat()).apply()
            binding.tvHzValue.text = clamped.toString()
            binding.seekbarHz.progress = clamped - minHz
        }

        // Initialise
        val initial = currentHz()
        binding.tvHzValue.text = initial.toString()
        binding.seekbarHz.progress = initial - minHz

        binding.seekbarHz.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val hz = minHz + progress
                binding.tvHzValue.text = hz.toString()
                prefs.edit().putFloat("reference_hz", hz.toFloat()).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        binding.btnMinus.setOnClickListener { applyHz(currentHz() - 1) }
        binding.btnPlus.setOnClickListener  { applyHz(currentHz() + 1) }
        binding.btnReset.setOnClickListener { applyHz(440) }
        binding.btn432.setOnClickListener   { applyHz(432) }
        binding.btn440.setOnClickListener   { applyHz(440) }
        binding.btn442.setOnClickListener   { applyHz(442) }
        binding.btn444.setOnClickListener   { applyHz(444) }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
