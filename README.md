# VoiceTextKeyboard
  A minimalist custom Android keyboard (IME) where press-and-hold = record, release = transcribe with Groq Whisper, then insert text at cursor. Built to demonstrate IME development, audio capture, API integration, robust UX, and privacy-first design.

### ✨ Features

- Single-button keyboard UI (zero distraction).
- Press & Hold Recording using the microphone.
- Release to Stop and auto-transcribe via Groq’s Whisper API.
- Insert transcribed text at the current cursor in the active app.
- Clear states & feedback: Idle → Recording → Processing → Complete/Error.
- Graceful error handling (network, permissions, cancellations).

### 🧱 Requirements

- Android Studio Ladybug or newer
- Min SDK 30, Target SDK 36
- Device/emulator with microphone
- Groq API key for Whisper endpoint

### 🚀 Build & Run

- Add `WHISPER_API_KEY` in ApiClient.kt.

- Install on device.

- Enable keyboard: Settings → System → Languages & input → On-screen keyboard → Manage keyboards → Voice-to-Text Keyboard (enable).

- Switch to keyboard in any text field, press & hold to dictate.

## 🗺️ Flow
  - `VoiceKeyboardService.kt` :  An InputMethodService that provides press-and-hold voice dictation inside a custom keyboard.
  #### Core flow:
  - The keyboard view contains a mic button (R.id.voice_input_button).
  - On press (ACTION_DOWN), audio capture begins using AudioRecord at 16,000Hz, mono, PCM 16-bit.
  - Raw PCM chunks are buffered in memory, then converted into a WAV file on stop (ACTION_UP/CANCEL).
  - The WAV file is uploaded to a Whisper transcription API; the returned text is inserted at the cursor.
 
  #### UI behavior:
  - While recording, the mic button background is tinted red; it resets when recording stops.
  - The mic button is disabled during the API upload and re-enabled when the call completes.
 
 #### Audio capture:
  - Uses [AudioRecord] with MIC source, CHANNEL_IN_MONO, ENCODING_PCM_16BIT.
  - Buffers audio chunks in [audioDataList] to avoid frequent file I/O during capture.
  - On stop, writes a proper 44-byte WAV header followed by buffered PCM data to a cache file.

 #### File format:
  - WAV, 16kHz, mono, 16-bit PCM little-endian.
  - Output path: ${cacheDir}/recorded_audio.wav
 
 #### Networking:
  - Uploads the WAV file via multipart/form-data to Whisper using [ApiClient.whisperApiService].
  - Request parameters include model, temperature, response format, and language.
  - On success, inserts transcription into the current input field via [currentInputConnection].

 #### Threading:
  - Recording runs on a background [Thread] reading from [AudioRecord].
  - UI changes (button state, hiding IME) are posted to the main thread via [runOnUiThread].
  - Network requests are asynchronous via Retrofit enqueue.
 
 #### Permissions:
  - Requires android.permission.RECORD_AUDIO at runtime; annotated with [@RequiresPermission].
    
 #### Error handling:
  - Logs initialization and read errors for AudioRecord.
  - Safely releases [AudioRecord] and [MediaPlayer] resources.
  - Guards against null states and failed responses.

### Screenshot
<img width="270" height="600" alt="Screenshot_20250821_235136" src="https://github.com/user-attachments/assets/eb1a7e88-056d-4bf5-b8e9-517d3ef0bd90" />
<img width="270" height="600" alt="Screenshot_20250821_234922" src="https://github.com/user-attachments/assets/96bc7537-f36c-4e85-a4d6-5f49d035a6ab" />



https://github.com/user-attachments/assets/84ba64a6-1b91-49a9-8d6e-d40659cbc74c


