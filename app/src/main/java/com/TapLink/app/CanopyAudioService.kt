package com.TapLinkX3.app

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

object CanopyAudioService {
    private const val TAG = "CanopyAudioService"
    private const val API_URL = "https://api-inference.huggingface.co/models/canopylabs/orpheus-v1-english"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var mediaPlayer: MediaPlayer? = null
    private var currentTempFile: File? = null

    fun speak(context: Context, text: String) {
        val prefs = context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("canopy_api_key", null)

        if (apiKey.isNullOrBlank()) {
            showToast(context, "TTS Error: API Key missing")
            return
        }

        Thread {
            try {
                val jsonBody = JSONObject()
                jsonBody.put("inputs", text)

                val requestBody = jsonBody.toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaType())

                val request = Request.Builder()
                    .url(API_URL)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .post(requestBody)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val errorBody = response.body?.string()
                        Log.e(TAG, "API Error: ${response.code} - $errorBody")
                        showToast(context, "TTS Error: ${response.code}")
                        return@use
                    }

                    val audioBytes = response.body?.bytes()
                    if (audioBytes != null && audioBytes.isNotEmpty()) {
                        playAudio(context, audioBytes)
                    } else {
                        showToast(context, "TTS Error: Empty response")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "TTS Failed", e)
                showToast(context, "TTS Failed: ${e.message}")
            }
        }.start()
    }

    private fun playAudio(context: Context, audioData: ByteArray) {
        try {
            val tempFile = File.createTempFile("tts_audio", ".mp3", context.cacheDir)
            FileOutputStream(tempFile).use { it.write(audioData) }

            mainHandler.post {
                try {
                    // Cleanup previous
                    if (mediaPlayer?.isPlaying == true) {
                        mediaPlayer?.stop()
                    }
                    mediaPlayer?.release()
                    mediaPlayer = null

                    try {
                        currentTempFile?.delete()
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to delete previous temp file", e)
                    }

                    currentTempFile = tempFile
                    mediaPlayer = MediaPlayer().apply {
                        setDataSource(tempFile.absolutePath)
                        prepare()
                        start()
                        setOnCompletionListener {
                            it.release()
                            mediaPlayer = null
                            try {
                                tempFile.delete()
                                if (currentTempFile == tempFile) {
                                    currentTempFile = null
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to delete temp file", e)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Playback failed", e)
                    showToast(context, "Playback Error")
                    try {
                        tempFile.delete()
                    } catch (ignored: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "File write failed", e)
        }
    }

    private fun showToast(context: Context, message: String) {
        mainHandler.post {
            (context as? MainActivity)?.dualWebViewGroup?.showToast(message)
        }
    }
}
