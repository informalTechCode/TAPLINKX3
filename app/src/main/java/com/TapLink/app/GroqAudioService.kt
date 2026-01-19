package com.TapLinkX3.app

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class GroqAudioService(private val context: Context) {

    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var isRecording = false
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())

    interface TranscriptionListener {
        fun onTranscriptionResult(text: String)
        fun onError(message: String)
        fun onRecordingStart()
        fun onRecordingStop()
    }

    private var listener: TranscriptionListener? = null

    fun setListener(listener: TranscriptionListener) {
        this.listener = listener
    }

    fun hasApiKey(): Boolean {
        val prefs = context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
        val key = prefs.getString("groq_api_key", null)
        return !key.isNullOrBlank()
    }

    fun getApiKey(): String? {
        val prefs = context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
        return prefs.getString("groq_api_key", null)?.trim()
    }

    fun setApiKey(key: String) {
        val prefs = context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
        prefs.edit().putString("groq_api_key", key.trim()).apply()
    }

    fun startRecording() {
        if (isRecording) return

        try {
            outputFile = File.createTempFile("recording_", ".m4a", context.cacheDir)

            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            mediaRecorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(outputFile?.absolutePath)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)

                prepare()
                start()
            }

            isRecording = true
            mainHandler.post { listener?.onRecordingStart() }
            DebugLog.d(TAG, "Recording started")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            mainHandler.post { listener?.onError("Failed to start recording: ${e.message}") }
            releaseRecorder()
        }
    }

    fun stopRecording() {
        if (!isRecording) return

        try {
            mediaRecorder?.stop()
            DebugLog.d(TAG, "Recording stopped")
        } catch (e: RuntimeException) {
            // Can happen if stop is called immediately after start
            Log.e(TAG, "RuntimeException on stop", e)
        } finally {
            releaseRecorder()
            isRecording = false
            mainHandler.post { listener?.onRecordingStop() }

            // Transcribe immediately after stopping
            outputFile?.let { file ->
                if (file.exists() && file.length() > 0) {
                    transcribeAudio(file)
                } else {
                    mainHandler.post { listener?.onError("Recording failed: File empty") }
                }
            }
        }
    }

    private fun releaseRecorder() {
        mediaRecorder?.release()
        mediaRecorder = null
    }

    private fun transcribeAudio(file: File) {
        val apiKey = getApiKey()
        if (apiKey.isNullOrBlank()) {
            mainHandler.post { listener?.onError("No API Key found") }
            return
        }

        Thread {
            try {
                DebugLog.d(TAG, "Starting transcription...")
                DebugLog.d(TAG, "API Key length: ${apiKey.length}, starts with: ${apiKey.take(8)}...")

                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", file.name,
                        file.asRequestBody("audio/m4a".toMediaType()))
                    .addFormDataPart("model", "whisper-large-v3")
                    .addFormDataPart("response_format", "json")
                    .build()

                val request = Request.Builder()
                    .url("https://api.groq.com/openai/v1/audio/transcriptions")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .post(requestBody)
                    .build()

                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string()
                    DebugLog.d(TAG, "Response code: ${response.code}")
                    //Log.d(TAG, "Response body: $responseBody")

                    if (!response.isSuccessful) {
                        mainHandler.post { listener?.onError("API Error: ${response.code} - $responseBody") }
                        return@use
                    }

                    if (responseBody != null) {
                        try {
                            val json = JSONObject(responseBody)
                            val text = json.optString("text", "").trim()
                            if (text.isNotBlank()) {
                                mainHandler.post { listener?.onTranscriptionResult(text) }
                            } else {
                                mainHandler.post { listener?.onError("No text transcribed") }
                            }
                        } catch (e: Exception) {
                            mainHandler.post { listener?.onError("JSON Parse Error: ${e.message}") }
                        }
                    } else {
                        mainHandler.post { listener?.onError("Empty response from API") }
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Transcription failed", e)
                mainHandler.post { listener?.onError("Network Error: ${e.message}") }
            } finally {
                // Cleanup file
                try {
                    file.delete()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to delete temp file", e)
                }
            }
        }.start()
    }

    fun isRecording(): Boolean = isRecording

    companion object {
        private const val TAG = "GroqAudioService"
    }
}
