# Free Tuner — Android

A bloatware-free, offline chromatic tuner for Android supporting **Guitar** and **Ukulele**. No ads, no tracking, no internet permission. Pure pitch detection from the microphone.

---

## Features

### Tuner
- Real-time pitch detection via the **YIN algorithm** (pure Kotlin, no native libs)
- Analog-style **tuning meter** with color feedback:
  - Green — in tune (±5 cents)
  - Blue — flat
  - Red — sharp
- Displays detected **note name**, **cents deviation** (+/−), and **exact frequency in Hz**
- **String indicators** — dynamically highlights the closest string for the active tuning (6 for guitar, 4 for ukulele)
- **Signal level bar** — shows microphone input level
- **Instrument spinner** — switch between Guitar and Ukulele; detection frequency range adjusts automatically
- **Tuning spinner** — select from multiple tunings per instrument

### Guitar Tunings
| Name | Strings (low → high) |
|---|---|
| Standard | E A D G B e |
| Drop D | D A D G B e |
| Drop C | C G C F A d |
| Half Step Down | E♭ A♭ D♭ G♭ B♭ e♭ |
| Full Step Down | D G C F A d |
| Open G | D G D G B d |
| Open D | D A D F# A d |
| Open E | E B E G# B e |
| DADGAD | D A D G A d |
| Open A | E A E A C# e |
| Open C | C G C G C e |

### Ukulele Tunings
| Name | Strings (low → high) |
|---|---|
| Standard | g C E A |
| Low G | G C E A |
| D Tuning | A D F# B |
| Open C | g C E G |
| Baritone | D G B E |
| Bb Tuning | F B♭ D G |

### Settings
- **Reference pitch (A4)** — adjustable from 400 to 480 Hz
  - Seekbar for continuous adjustment
  - Fine-tune with **−/+** buttons (1 Hz steps)
  - Quick presets: **432 Hz**, **440 Hz**, **442 Hz**, **444 Hz**
  - Reset to 440 Hz button
- Setting persists across app restarts

---

## Technical Details

### Pitch Detection
- Algorithm: **YIN** (de Cheveigné & Kawahara, 2002)
- Sample rate: 44100 Hz mono PCM
- Buffer: 8192 samples (~185 ms) — covers ~15 full cycles of low E2 (82 Hz)
- Detection range: instrument-dependent (60–1400 Hz for guitar, 130–1600 Hz for ukulele)
- **Harmonic confirmation**: tau/2 octave-up check reduces upward-octave errors common on ukulele high strings
- **Median filter** (7-frame window) rejects outlier readings
- **Exponential smoothing** (α = 0.20) in the UI layer damps rapid fluctuations; large jumps (>80 cents) snap immediately for fast note response

### Architecture
```
app/src/main/java/com/example/guitartuner/
├── audio/
│   ├── AudioRecorder.kt      — microphone capture, median filter, noise gate
│   └── PitchDetector.kt      — YIN algorithm, dynamic frequency range
├── tuning/
│   ├── Tuning.kt             — Tuning + Instrument models, all tuning definitions
│   └── TuningAnalyzer.kt     — maps detected Hz to closest string + cents deviation
├── ui/
│   ├── MainActivity.kt       — instrument/tuning spinners, dynamic string indicators
│   ├── TunerViewModel.kt     — instrument selection, pitch smoothing, state management
│   └── TuningMeterView.kt    — custom Canvas-drawn analog meter
└── settings/
    └── SettingsActivity.kt   — reference pitch configuration
```

### Requirements
- Android 8.0 (API 26) or higher
- Microphone permission (`RECORD_AUDIO`) — requested at first launch

### Build
```bash
# Requires Android SDK and JDK 17+
./gradlew assembleDebug        # debug APK → app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # release APK (requires signing config)
```

Install on a connected device:
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Dependencies
All AndroidX — no third-party audio or DSP libraries.

| Library | Version | Purpose |
|---|---|---|
| androidx.core-ktx | 1.13.1 | Kotlin extensions |
| androidx.appcompat | 1.7.0 | Backward-compatible UI |
| material | 1.12.0 | Material Design components |
| constraintlayout | 2.2.0 | Layout |
| lifecycle-viewmodel-ktx | 2.8.7 | ViewModel + coroutines |
| activity-ktx | 1.9.3 | Activity result APIs |
| fragment-ktx | 1.8.5 | Fragment support |

---

## Permissions

| Permission | Why |
|---|---|
| `RECORD_AUDIO` | Microphone access for pitch detection |

No other permissions. No internet access. No analytics.

---

## License

MIT
