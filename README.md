<p align="center">
  <img src="docs/hero.png" alt="Supertonic TTS" width="100%">
</p>

<p align="center">
  <b>English</b> · <a href="docs/README.ko.md">한국어</a>
</p>

# Supertonic TTS

**Turn any text into natural speech, entirely on your phone, and send it to whatever speaker you choose.**

Supertonic TTS is an Android app that synthesizes spoken audio from text using the on-device [Supertonic 3](https://github.com/supertone-inc/supertonic) model — no cloud, no account, no network once the voices are downloaded. Pick a voice, pick an output device, and press play — or save the result as an audio file.

## When would you use this?

- **You need text read aloud in someone else's voice, not your own.** Paste a message, an article, or a note and let the app speak it clearly — handy for narration, accessibility, language practice, or hands-free listening.
- **You need to "say" something out loud in a quiet place.** In a library, a meeting, or a late-night room, type what you want to communicate and route it to a **Bluetooth earphone or speaker** so it's delivered as speech without you having to talk.

## Features

- 🔊 **On-device synthesis** — runs the Supertonic 3 model locally; works fully offline after the one-time model download.
- 🎚️ **Choose your output device** — play through the phone speaker, a wired headset, a USB DAC, or a specific connected Bluetooth speaker/earphone.
- 💾 **Save to a file** — export the audio as **WAV, AAC (M4A), Opus (OGG), or MP3** to anywhere you like via the system file picker.
- 🌍 **Multilingual** — 31 languages plus an **Auto** mode that handles text without a language tag.
- 🗣️ **10 preset voices** — five male (M1–M5) and five female (F1–F5).
- 🎛️ **Tunable** — adjust **quality** (denoising steps) and **speech speed**.
- 🎨 Clean Material 3 (Jetpack Compose) interface.

| Synthesize & play | Choose the output device | Export formats | Advanced settings |
|:---:|:---:|:---:|:---:|
| <img src="docs/screenshot/voice-synth.jpg" alt="Synthesize and play"> | <img src="docs/screenshot/output-device.jpg" alt="Choose the output device"> | <img src="docs/screenshot/output-format.jpg" alt="Export formats"> | <img src="docs/screenshot/advanced-setting.jpg" alt="Advanced settings"> |

## Requirements

- **Android 9.0 (API 28) or newer.**
- An **arm64-v8a** device (virtually all modern phones).
- **~400 MB free storage** for the voice models, downloaded once on first launch.
- **Internet connection for the first run only** (to download the models); offline afterwards.

## How to use

1. **Install the app.** Download `supertonic-v0.1-arm64-v8a.apk` from the [Releases](https://github.com/ouor/supertonic-android/releases) page and open it. You may need to allow installation from unknown sources.
2. **Download the voices.** On first launch a dialog appears and fetches the model files (~398 MB). This happens only once.
3. **Type your text** in the text box. Use the **✕** button to clear it.
4. **Pick a voice** from the dropdown.
5. **Press Play** ▶ to hear it, or **Save** 💾 to export an audio file.
6. **Open “추가 설정 / Advanced settings”** to choose the:
   - **Output device** — where the audio plays (phone, Bluetooth, wired, USB…).
   - **Save format** — WAV / AAC / Opus / MP3.
   - **Language** — a specific language or **Auto**.
   - **Quality** and **Speed**.

> The provided APK is signed with a debug key for easy installation and testing. It is not yet intended for Play Store distribution.

## Technical overview

- **Model & inference.** Uses the [Supertonic 3](https://github.com/supertone-inc/supertonic) text-to-speech model via **ONNX Runtime** (`onnxruntime-android`). Synthesis is a four-model pipeline — duration predictor → text encoder → vector estimator (iterative denoising) → vocoder — producing 44.1 kHz mono float PCM. The Kotlin engine is a faithful port of the official reference implementation.
- **Model delivery.** The four ONNX models, voice styles, and config are downloaded from the [Hugging Face repo](https://huggingface.co/Supertone/supertonic-3) on first run and cached in app-private storage (resumable, integrity-checked downloads).
- **Playback & routing.** PCM is streamed through an `AudioTrack` (`ENCODING_PCM_FLOAT`); the chosen sink is enumerated via `AudioManager.getDevices()` and selected with `AudioTrack.setPreferredDevice()`.
- **Audio export.** Non-WAV formats are encoded with **FFmpegKit**; WAV is written directly. Output is saved through the Storage Access Framework.
- **UI.** Single-screen **Jetpack Compose** (Material 3) app backed by a `ViewModel` with Kotlin coroutines.
- **Build.** Kotlin 2.2 · AGP 9 · `minSdk 28` / `targetSdk 36`. Release APKs are split per ABI and shipped as **arm64-v8a** only.

## Credits & license

Built on the open-weight **[Supertonic](https://github.com/supertone-inc/supertonic)** model by Supertone Inc. The model is distributed under its own license (OpenRAIL-M) — see the [model card](https://huggingface.co/Supertone/supertonic-3). This app's source code is provided as-is for the Supertonic Android client.
