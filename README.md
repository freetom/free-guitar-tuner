# Free Tuner — Android

A bloatware-free, offline chromatic tuner for Android supporting **Guitar** and **Ukulele**. No ads, no tracking, no internet permission. Pure pitch detection from the microphone.

<img width="400" alt="screen" src="https://github.com/user-attachments/assets/9646ba5c-1b27-4984-9758-1442e1fbd726" />

---

## Features

### Tuner
- Real-time pitch detection via the **YIN algorithm** (pure Kotlin, no native libs)
- **Responsive**: ~43 readings per second; a new note is recognised in ~100 ms and the needle follows a peg turn closely
- **Stable**: rejects noise and chords, holds the last pitch briefly when the signal drops out, and follows a decaying note
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
- Sliding-window analysis: a 3072-sample window (~70 ms) is analysed every 1024 samples (~23 ms), i.e. **~43 readings per second**
- Detection range: instrument-dependent (60–1400 Hz for guitar, 130–1600 Hz for ukulele)
- **Octave-error handling**: first YIN dip wins, then 2×tau and tau/2 neighbourhoods are checked so weak-fundamental notes are not reported an octave off
- **Clarity gating**: readings with YIN clarity below 0.70 (noise, chords, transients) are rejected; the last good pitch is held for 250 ms across dropouts
- **Noise gate with hysteresis**: starts at RMS 120, keeps following a decaying note down to RMS 60
- **Time-based filter** (cents domain): short median (5) + one-pole smoother (τ = 100 ms), and a new note is locked in after two agreeing readings (~90 ms)
- Needle animation is time-based (τ = 50 ms), independent of display refresh rate

### Architecture
```
app/src/main/java/com/example/guitartuner/
├── audio/
│   ├── AudioRecorder.kt      — microphone capture (Android I/O only)
│   ├── PitchTracker.kt       — sliding window, gates, clarity, dropout hold
│   ├── PitchDetector.kt      — YIN algorithm, octave check, clarity
│   └── PitchFilter.kt        — time-based median + smoothing, jump confirmation
├── tuning/
│   ├── Tuning.kt             — Tuning + Instrument models, all tuning definitions
│   └── TuningAnalyzer.kt     — maps detected Hz to closest string + cents deviation
├── ui/
│   ├── MainActivity.kt       — instrument/tuning spinners, dynamic string indicators
│   ├── TunerViewModel.kt     — instrument selection, state management
│   └── TuningMeterView.kt    — custom Canvas-drawn analog meter
└── settings/
    └── SettingsActivity.kt   — reference pitch configuration

app/src/test/java/com/example/guitartuner/audio/
└── PitchTrackerTest.kt       — JVM tests on synthetic signals (accuracy, octave errors,
                                lock-on and tracking latency, noise rejection, dropout hold)
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

Run the unit tests (JVM only, no device needed):
```bash
./gradlew testDebugUnitTest
```
The tests feed synthetic guitar/ukulele-like signals through the pitch pipeline and print
measured latencies (lock-on time, settling time, glide-tracking error) with `-i`.

Install on a connected device:
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Dependencies
All AndroidX (plus JUnit for tests) — no third-party audio or DSP libraries.

| Library | Version | Purpose |
|---|---|---|
| androidx.core-ktx | 1.13.1 | Kotlin extensions |
| androidx.appcompat | 1.7.0 | Backward-compatible UI |
| material | 1.12.0 | Material Design components |
| constraintlayout | 2.2.0 | Layout |
| lifecycle-viewmodel-ktx | 2.8.7 | ViewModel + coroutines |
| lifecycle-runtime-ktx | 2.8.7 | Lifecycle-aware coroutines |
| activity-ktx | 1.9.3 | Activity result APIs |
| fragment-ktx | 1.8.5 | Fragment support |
| junit (test only) | 4.13.2 | Unit tests |

---

## Permissions

| Permission | Why |
|---|---|
| `RECORD_AUDIO` | Microphone access for pitch detection |

No other permissions. No internet access. No analytics.

---

## License

GNU General Public License v2.0 — see [LICENSE](LICENSE) for details.
