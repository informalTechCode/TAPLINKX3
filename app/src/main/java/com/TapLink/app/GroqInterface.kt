package com.TapLinkX3.app

import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.annotation.Keep
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

@Keep
class GroqInterface(private val context: Context, private val webView: WebView) {
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
    fun getActivePageUrl(): String {
        return try {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                resolveActivePageUrl().orEmpty()
            } else {
                val result = AtomicReference<String?>()
                val latch = CountDownLatch(1)
                mainHandler.post {
                    result.set(resolveActivePageUrl())
                    latch.countDown()
                }
                latch.await(500, TimeUnit.MILLISECONDS)
                result.get().orEmpty()
            }
        } catch (e: Exception) {
            Log.e("GroqInterface", "Failed to read active page URL", e)
            ""
        }
    }

    @JavascriptInterface
    @Keep
    fun chatWithGroq(message: String, historyJson: String) {
        Log.d("GroqInterface", "chatWithGroq called: $message")
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
                        systemMsg.put(
                                "content",
                                """Your name is TapLink AI. You are a helpful AI assistant built into the TapLink X3 web browser for RayNeo X3 Pro glasses.
The documentation for the web browser can be found here: https://github.com/informalTechCode/TAPLINKX3/tree/main/docs
Information about the glasses it lives on can be found here: https://www.rayneo.com/products/x3-pro-ai-display-glasses
The creator of the TapLink X3 browser is Informal Tech. Tech-tuber that makes awesome tech videos on YouTube. He is found at youtube.com/@informal-tech.
Answer questions concisely and keep all responses human readable."""
                        )
                        messages.put(systemMsg)

                        // Add history
                        for (i in 0 until history.length()) {
                            val item = history.getJSONObject(i)
                            // Fix role for API compatibility
                            if (item.optString("role") == "ai") {
                                item.put("role", "assistant")
                            }
                            messages.put(item)
                        }

                        // Add current user message
                        val userMsg = JSONObject()
                        userMsg.put("role", "user")
                        userMsg.put("content", message)
                        messages.put(userMsg)

                        val jsonBody = JSONObject()
                        jsonBody.put("model", "groq/compound")
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
                        Log.e("GroqInterface", "Chat failed", e)
                        postResponse("Error: ${e.message}")
                    }
                }
                .start()
    }

    private fun postResponse(text: String) {
        mainHandler.post {
            // Escape single quotes and backslashes for JS string
            val escapedText = text.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
            webView.evaluateJavascript("receiveGroqResponse('$escapedText')", null)
        }
    }

    private fun resolveActivePageUrl(): String? {
        val activity = findMainActivity(context) ?: return null
        val currentUrl = activity.getActiveWebViewUrlOrNull()?.trim()
        if (currentUrl.isNullOrEmpty() || currentUrl == "about:blank") return null
        if (currentUrl.startsWith(DASHBOARD_URL, ignoreCase = true)) return null
        return currentUrl
    }

    private fun findMainActivity(context: Context): MainActivity? {
        var ctx = context
        while (ctx is ContextWrapper) {
            if (ctx is MainActivity) return ctx
            ctx = ctx.baseContext
        }
        return ctx as? MainActivity
    }

    private companion object {
        private const val DASHBOARD_URL = "file:///android_asset/AR_Dashboard_Landscape_Sidebar.html"
    }
}
