# La trahison des voix — vox-ball-android

A two-player pong for Android where **the rackets are moved only by the human voice**.
Landscape only, one phone flat on the table between two players.

> *The betrayal of voices* — because a voice you cannot keep steady is a racket you cannot aim.

## Rules

- Two players, left (`J1`) and right (`J2`), sharing one phone and one microphone.
- **Only the player the ball is travelling towards can act.** The other side is muted;
  its racket cannot move and cannot hit.
- **Pitch moves the racket**: a voice higher than your own baseline moves it up, a lower
  voice moves it down. A steady voice keeps it still (dead zone of ±1.1 semitone).
- **A racket must be filled with voice to return the ball.** Talking fills it in about
  0.4 s; silence drains it in about 0.5 s. Below 34 % charge the racket is a ghost and
  the ball goes straight through.
- **Red = you are speaking. Grey = you are silent.**
- **The ball gets faster every level, a new level every 5 balls played.** There is no
  end: only the score survives.
- Before playing, each player hums `mmmm` for two seconds so the game learns their own
  voice as the zero point.

Seven hint screens explain all of this before the first serve.

## How the voice is turned into movement

1. `AudioRecord` captures 22 050 Hz mono PCM in 1024-sample frames (~21 frames/s).
2. Loudness is dBFS; a leaky minimum tracker follows the room noise and gates the signal
   roughly 9 dB above it, so a quiet room and a noisy room both work.
3. Pitch is a normalised autocorrelation over lags 80–450 Hz, with an octave guard and
   parabolic interpolation — no external library, ~4 M multiply-adds per second.
4. The pitch is compared to the player's **calibrated baseline** in semitones, not in Hz:
   `12 * log2(f0 / baseline)`. `+1.1` to `+5` semitones maps (smoothstep) to "up at up to
   full speed", `-1.1` to `-5` to "down", and the dead zone in between holds still.
   That is why a low male voice and a high female voice both play the same way.
5. Charge is integrated from "voiced" frames: `+2.6/s` while speaking, `-1.9/s` while
   silent. The racket's fill bar *is* the charge, and the bounce test reads it.

## Build

Requirements: JDK 17, Android SDK with `platforms;android-35` and `build-tools;35.0.0`.

```bash
export ANDROID_HOME=$HOME/Android/Sdk    # or wherever your SDK lives
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The release build is signed with the debug key on purpose, so `assembleRelease` also
produces a directly installable APK for personal use (do not publish it).

Unit tests cover the physics — bounce, pass-through, misalignment, charge, levels, serve
direction, walls, pause:

```bash
./gradlew testDebugUnitTest
```

## Install without adb

Copy `app-debug.apk` to the phone (USB, cloud, Telegram…), tap it, allow "install from
unknown sources" for the file manager you use. Android 8.0+ (API 26).

On first launch Android asks for the microphone. Without it the game cannot be played —
that is the whole point.

## If it crashes

This APK catches its own uncaught exceptions: the last stack trace is written to the
app's private `crash.txt` and shown on the next launch, on a dark red screen, until you
tap it away. A screenshot of that screen is a complete bug report — no adb needed.
The same trace also goes to logcat.

## Project layout

```
app/src/main/java/com/sambouli/voxball/
├── MainActivity.kt     immersive landscape host + microphone permission
├── GameView.kt         SurfaceView render loop, HUD, hints, calibration, touch
├── GameEngine.kt       pure-Kotlin rules: physics, pitch mapping, charge, levels
├── VoiceAnalyzer.kt    AudioRecord + autocorrelation pitch detector
└── Content.kt          all the French text
```

`GameEngine` has no Android dependency, which is why the rules can be tested on the JVM.

## Tuning knobs

Everything lives in `Tuning` (`GameEngine.kt`): charge rates, dead zone, paddle and ball
speed, speed step per level, and how many balls make a level.

## License

MIT — see `LICENSE`.
