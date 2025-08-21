package com.dictation.voicetextkeyboard.service

import android.Manifest
import android.annotation.SuppressLint
import android.inputmethodservice.InputMethodService
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.annotation.RequiresPermission
import com.dictation.voicetextkeyboard.R
import com.dictation.voicetextkeyboard.network.ApiClient
import com.dictation.voicetextkeyboard.network.WhisperResponse
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder


/**
 * An InputMethodService that provides press-and-hold voice dictation inside a custom keyboard.
 *
 * Core flow:
 * - The keyboard view contains a mic button (R.id.voice_input_button).
 * - On press (ACTION_DOWN), audio capture begins using AudioRecord at 16,000Hz, mono, PCM 16-bit.
 * - Raw PCM chunks are buffered in memory, then converted into a WAV file on stop (ACTION_UP/CANCEL).
 * - The WAV file is uploaded to a Whisper transcription API; the returned text is inserted at the cursor.
 *
 * UI behavior:
 * - While recording, the mic button background is tinted red; it resets when recording stops.
 * - The mic button is disabled during the API upload and re-enabled when the call completes.
 *
 * Audio capture:
 * - Uses [AudioRecord] with MIC source, CHANNEL_IN_MONO, ENCODING_PCM_16BIT.
 * - Buffers audio chunks in [audioDataList] to avoid frequent file I/O during capture.
 * - On stop, writes a proper 44-byte WAV header followed by buffered PCM data to a cache file.
 *
 * File format:
 * - WAV, 16kHz, mono, 16-bit PCM little-endian.
 * - Output path: ${cacheDir}/recorded_audio.wav
 *
 * Networking:
 * - Uploads the WAV file via multipart/form-data to Whisper using [ApiClient.whisperApiService].
 * - Request parameters include model, temperature, response format, and language.
 * - On success, inserts transcription into the current input field via [currentInputConnection].
 *
 * Threading:
 * - Recording runs on a background [Thread] reading from [AudioRecord].
 * - UI changes (button state, hiding IME) are posted to the main thread via [runOnUiThread].
 * - Network requests are asynchronous via Retrofit enqueue.
 *
 * Permissions:
 * - Requires android.permission.RECORD_AUDIO at runtime; annotated with [@RequiresPermission].
 *
 * Error handling:
 * - Logs initialization and read errors for AudioRecord.
 * - Safely releases [AudioRecord] and [MediaPlayer] resources.
 * - Guards against null states and failed responses.
 *
 * Notes and limitations:
 * - Audio is kept in memory during capture; very long presses increase memory usage.
 * - Only one active recording is supported at a time.
 * - Playback helper [playAudioFile] is available but not invoked after recording by default.
 *
 * Entry points:
 * - [onCreateInputView]: Inflates keyboard layout and wires mic touch listener.
 * - [onTouch]: Delegates press/hold recording lifecycle.
 * - [startRecording]/[stopRecording]: Manage AudioRecord lifecycle and file generation.
 * - [uploadAudioToWhisperApi]: Sends WAV to Whisper and commits returned text.
 */


class VoiceKeyboardService : InputMethodService(), View.OnTouchListener {

    private lateinit var recordButton: Button

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private val sampleRate = 16000
    private val audioBufferSize = AudioRecord.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    )
    private var recordingThread: Thread? = null
    private var audioFilePath: String? = null
    private var mediaPlayer: MediaPlayer? = null

    // Buffer to store raw PCM data
    private val audioDataList = mutableListOf<ByteArray>()

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreateInputView(): View? {
        val keyboardView = layoutInflater.inflate(R.layout.keyboard_layout, null)
        recordButton = keyboardView.findViewById(R.id.voice_input_button)
        recordButton.setOnTouchListener(this)
        return keyboardView
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun onTouch(v: View?, event: MotionEvent?): Boolean {
        when (event?.action) {
            MotionEvent.ACTION_DOWN -> {
                startRecording()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                stopRecording()
                return true
            }

            else -> return false
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startRecording() {
        if (!canRecord()) {
            Toast.makeText(applicationContext, "Record audio permission is missing", Toast.LENGTH_SHORT).show()
            // Notify user to grant permission in app settings/activity
            // e.g., show a toast or a small in-IME message
            return
        }
        // Clear previous recording data
        audioDataList.clear()

        // Create WAV file path instead of MP3
        audioFilePath = "${cacheDir.absolutePath}/recorded_audio.wav"

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            audioBufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e("VoiceKeyboardService", "AudioRecord initialization failed")
            return
        }

        audioRecord?.startRecording()
        isRecording = true

        recordButton.setBackgroundColor(resources.getColor(R.color.red,null))
        recordButton.text = resources.getString(R.string.recording)

        recordingThread = Thread {
            writeAudioDataToBuffer()
        }
        recordingThread?.start()
    }

    private fun writeAudioDataToBuffer() {

        val data = ByteArray(audioBufferSize)
        try {
            while (isRecording && audioRecord != null) {
                val read = audioRecord?.read(data, 0, data.size) ?: 0
                if (read > 0) {
                    // Store the audio data in memory
                    val audioChunk = ByteArray(read)
                    System.arraycopy(data, 0, audioChunk, 0, read)
                    synchronized(audioDataList) {
                        audioDataList.add(audioChunk)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("VoiceKeyboardService", "Error reading audio data", e)
        }
    }

    private fun stopRecording() {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        recordingThread = null

        recordButton.setBackgroundColor(resources.getColor(R.color.red,null))
        recordButton.text = resources.getString(R.string.processing)

        // Convert raw PCM data to WAV file
        audioFilePath?.let { filePath ->
            try {
                createWavFile(filePath)
                Log.d("VoiceKeyboardService", "WAV file created: $filePath")
                uploadAudioToWhisperApi(filePath)
            } catch (e: IOException) {
                Log.e("VoiceKeyboardService", "Error creating WAV file", e)
            }
        }
    }

    @Throws(IOException::class)
    private fun createWavFile(filePath: String) {
        val file = File(filePath)
        val fileOutputStream = FileOutputStream(file)

        // Calculate total audio data size
        var totalAudioSize = 0
        synchronized(audioDataList) {
            for (chunk in audioDataList) {
                totalAudioSize += chunk.size
            }
        }

        // Write WAV header
        writeWavHeaderWithBuffer(fileOutputStream, totalAudioSize)

        // Write audio data
        synchronized(audioDataList) {
            for (chunk in audioDataList) {
                fileOutputStream.write(chunk)
            }
        }

        fileOutputStream.close()
    }

    @Throws(IOException::class)
    private fun writeWavHeaderWithBuffer(out: FileOutputStream, audioDataSize: Int) {
        val channels = 1 // Mono
        val bitDepth = 16 // 16-bit
        val byteRate = sampleRate * channels * bitDepth / 8
        val blockAlign = channels * bitDepth / 8
        val fileSize = audioDataSize + 36

        val buffer = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF chunk descriptor
        buffer.put("RIFF".toByteArray())
        buffer.putInt(fileSize)
        buffer.put("WAVE".toByteArray())

        // fmt subchunk
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16) // Subchunk1Size
        buffer.putShort(1) // AudioFormat (PCM)
        buffer.putShort(channels.toShort()) // NumChannels
        buffer.putInt(sampleRate) // SampleRate
        buffer.putInt(byteRate) // ByteRate
        buffer.putShort(blockAlign.toShort()) // BlockAlign
        buffer.putShort(bitDepth.toShort()) // BitsPerSample

        // data subchunk
        buffer.put("data".toByteArray())
        buffer.putInt(audioDataSize)

        out.write(buffer.array())
    }

    private fun uploadAudioToWhisperApi(audioFilePath: String) {

        val file = File(audioFilePath)
        if (!file.exists()) return

        Log.d("VoiceKeyboardService", "uploadAudioToWhisperApi: $audioFilePath")

        // Now the file is a proper WAV file that can be uploaded
        val requestFile = RequestBody.create("audio/wav".toMediaTypeOrNull(), file)
        val body = MultipartBody.Part.createFormData("file", file.name, requestFile)

        val model = RequestBody.create("text/plain".toMediaTypeOrNull(), "whisper-large-v3-turbo")
        val temperature = RequestBody.create("text/plain".toMediaTypeOrNull(), "0")
        val responseFormat = RequestBody.create("text/plain".toMediaTypeOrNull(), "verbose_json")
        val language = RequestBody.create("text/plain".toMediaTypeOrNull(), "en")

        runOnUiThread {
            recordButton.isEnabled = false
            recordButton.setBackgroundColor(resources.getColor(R.color.red,null))
            recordButton.text = resources.getString(R.string.analyzing)
        }

        val call = ApiClient.whisperApiService.uploadAudio(
            body,
            model,
            temperature,
            responseFormat,
            language
        )

        call.enqueue(object : Callback<WhisperResponse> {
            override fun onResponse(
                call: Call<WhisperResponse>,
                response: Response<WhisperResponse>
            ) {
                runOnUiThread {
                    recordButton.isEnabled = true
                    recordButton.setBackgroundColor(resources.getColor(R.color.purple_500,null))
                    recordButton.text = resources.getString(R.string.hold_to_speak)
                }
                if (response.isSuccessful) {
                    val transcription = response.body()?.text ?: ""
                    insertTextAtCursor(transcription)
                    runOnUiThread {
                        requestHideSelf(0)
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(applicationContext, "Unable to translate", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            override fun onFailure(call: Call<WhisperResponse>, t: Throwable) {
                runOnUiThread {
                    recordButton.isEnabled = true
                    recordButton.setBackgroundColor(resources.getColor(R.color.purple_500,null))
                    recordButton.text = resources.getString(R.string.hold_to_speak)

                    Toast.makeText(applicationContext, "Unable to translate", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun insertTextAtCursor(text: String) {
        currentInputConnection.commitText(text, 1)
    }

    private fun runOnUiThread(runnable: Runnable) {
        Handler(Looper.getMainLooper()).post(runnable)
    }

    private fun canRecord(): Boolean {
        return androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}
