package com.TapLinkX3.app

import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.annotation.Keep
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

@Keep
class WebAppInterface(private val context: Context, private val webView: WebView) {
    private val client =
            OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .build()
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    @Keep
    fun ping(): String {
        return "pong"
    }

    @JavascriptInterface
    @Keep
    fun chatWithGroq(message: String, historyJson: String) {
        Log.d("WebAppInterface", "chatWithGroq called: $message")
        Thread {
                    try {
                        val prefs =
                                context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                        val apiKey = prefs.getString("groq_api_key", null)

                        if (apiKey.isNullOrBlank()) {
                            postResponse("Error: API Key not found. Please set it in Settings.")
                            return@Thread
                        }

                        val history =
                                try {
                                    org.json.JSONArray(historyJson)
                                } catch (e: Exception) {
                                    org.json.JSONArray()
                                }

                        val messages = org.json.JSONArray()
                        // Add system prompt
                        val systemMsg = JSONObject()
                        systemMsg.put("role", "system")

                        var systemContent = "You are a helpful AI assistant integrated into the TapLinkX3 dashboard."
                        val activity = findMainActivity(context)
                        val location = activity?.getLastLocation()
                        if (location != null) {
                            systemContent += "\nCurrent Location: ${location.first}, ${location.second}"
                        }

                        systemMsg.put("content", systemContent)
                        messages.put(systemMsg)

                        // Add history
                        for (i in 0 until history.length()) {
                            messages.put(history.get(i))
                        }

                        // Add current user message
                        val userMsg = JSONObject()
                        userMsg.put("role", "user")
                        userMsg.put("content", message)
                        messages.put(userMsg)

                        val jsonBody = JSONObject()
                        jsonBody.put("model", "llama3-70b-8192")
                        jsonBody.put("messages", messages)

                        val requestBody =
                                jsonBody.toString()
                                        .toRequestBody(
                                                "application/json; charset=utf-8".toMediaType()
                                        )

                        val request =
                                Request.Builder()
                                        .url("https://api.groq.com/openai/v1/chat/completions")
                                        .addHeader("Authorization", "Bearer $apiKey")
                                        .post(requestBody)
                                        .build()

                        client.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) {
                                postResponse("Error: ${response.code} - ${response.message}")
                                return@use
                            }

                            val responseBody = response.body?.string()
                            if (responseBody != null) {
                                val json = JSONObject(responseBody)
                                val choices = json.optJSONArray("choices")
                                if (choices != null && choices.length() > 0) {
                                    val content =
                                            choices.getJSONObject(0)
                                                    .getJSONObject("message")
                                                    .getString("content")
                                    postResponse(content)
                                } else {
                                    postResponse("Error: No response from AI.")
                                }
                            } else {
                                postResponse("Error: Empty response body.")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("GroqChat", "Chat failed", e)
                        postResponse("Error: ${e.message}")
                    }
                }
                .start()
    }

    private fun postResponse(text: String) {
        mainHandler.post {
            // Escape single quotes and backslashes for JS string
            val escapedText = text.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")

            // Log.d("WebAppInterface", "Posting response to WebView: $escapedText")
            webView.evaluateJavascript("receiveGroqResponse('$escapedText')", null)
        }
    }

    private fun findMainActivity(context: Context): MainActivity? {
        var ctx = context
        while (ctx is ContextWrapper) {
            if (ctx is MainActivity) return ctx
            ctx = ctx.baseContext
        }
        return ctx as? MainActivity
    }
}
