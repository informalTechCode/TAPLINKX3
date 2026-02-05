package com.TapLinkX3.app

import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
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
            DebugLog.e("GroqInterface", "Failed to read active page URL", e)
            ""
        }
    }

    @JavascriptInterface
    @Keep
    fun chatWithGroq(message: String, historyJson: String, ttsEnabled: Boolean) {
        DebugLog.d("GroqInterface", "chatWithGroq called: $message, ttsEnabled: $ttsEnabled")
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

                        var systemContent =
                                """You are TapLink AI, the in-browser assistant for the TapLink X3 web browser on RayNeo X3 Pro glasses.

Primary behavior:
- Give direct, useful answers in plain language.
- Keep responses concise by default (1-4 short paragraphs or brief bullets).
- Prioritize actionable steps when the user asks for help.
- Ask a short clarifying question when the request is ambiguous.

Constraints:
- Do not claim to perform actions you cannot perform.
- You cannot directly control hardware or system settings of the glasses.
- Do not add a "How this was determined" section unless the user explicitly asks for it.
- Do not include internal reasoning traces or chain-of-thought.

When relevant:
- For TapLink X3 browser feature questions, use this reference: https://github.com/informalTechCode/TAPLINKX3/blob/main/docs/USER_GUIDE.md
- For device context, RayNeo X3 Pro: https://www.rayneo.com/products/x3-pro-ai-display-glasses

Style:
- Be accurate, neutral, and practical.
- If uncertain, say so briefly and suggest the next best step."""

                        val activity = findMainActivity(context)
                        val location = activity?.getLastLocation()
                        if (location != null) {
                            systemContent +=
                                    "\nCurrent Location: ${location.first}, ${location.second}"
                        }

                        systemMsg.put("content", systemContent)
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

                                    // If TTS is enabled, fetch audio before responding
                                    if (ttsEnabled) {
                                        val ttsAudio = fetchTtsAudio(content, apiKey)
                                        postResponseWithTts(
                                                content,
                                                ttsAudio?.first,
                                                ttsAudio?.second
                                        )
                                    } else {
                                        postResponse(content)
                                    }
                                } else {
                                    postResponse("Error: No response from AI.")
                                }
                            } else {
                                postResponse("Error: Empty response body.")
                            }
                        }
                    } catch (e: Exception) {
                        DebugLog.e("GroqInterface", "Chat failed", e)
                        postResponse("Error: ${e.message}")
                    }
                }
                .start()
    }

    @JavascriptInterface
    @Keep
    fun speakWithOrpheus(text: String) {
        Thread {
                    try {
                        val prefs =
                                context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                        val apiKey = prefs.getString("groq_api_key", null)

                        if (apiKey.isNullOrBlank()) {
                            postTtsError("Error: API Key not found. Please set it in Settings.")
                            return@Thread
                        }

                        val jsonBody = JSONObject()
                        jsonBody.put("model", "canopylabs/orpheus-v1-english")
                        jsonBody.put("input", text)
                        jsonBody.put("voice", "hannah")
                        jsonBody.put("response_format", "wav")

                        val requestBody =
                                jsonBody.toString()
                                        .toRequestBody(
                                                "application/json; charset=utf-8".toMediaType()
                                        )

                        val request =
                                Request.Builder()
                                        .url("https://api.groq.com/openai/v1/audio/speech")
                                        .addHeader("Authorization", "Bearer $apiKey")
                                        .post(requestBody)
                                        .build()

                        client.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) {
                                postTtsError("Error: ${response.code} - ${response.message}")
                                return@use
                            }

                            val bytes = response.body?.bytes()
                            if (bytes != null && bytes.isNotEmpty()) {
                                val base64Audio = Base64.encodeToString(bytes, Base64.NO_WRAP)
                                postTtsAudio(base64Audio, "audio/wav")
                            } else {
                                postTtsError("Error: Empty TTS response body.")
                            }
                        }
                    } catch (e: Exception) {
                        DebugLog.e("GroqInterface", "TTS failed", e)
                        postTtsError("Error: ${e.message}")
                    }
                }
                .start()
    }

    @JavascriptInterface
    @Keep
    fun openUrlInNewTab(url: String) {
        val activity = findMainActivity(context) ?: return
        activity.runOnUiThread { activity.openUrlInNewTab(url) }
    }

    private fun postResponse(text: String) {
        mainHandler.post {
            // Escape single quotes and backslashes for JS string
            val escapedText = text.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
            webView.evaluateJavascript("receiveGroqResponse('$escapedText')", null)
        }
    }

    private fun postTtsAudio(base64Audio: String, mimeType: String) {
        mainHandler.post {
            findMainActivity(context)?.prepareAudioForTtsPlayback()
            val quotedAudio = JSONObject.quote(base64Audio)
            val quotedMime = JSONObject.quote(mimeType)
            webView.evaluateJavascript("receiveGroqTtsAudio($quotedAudio, $quotedMime)", null)
        }
    }

    private fun postTtsError(message: String) {
        mainHandler.post {
            val quotedMessage = JSONObject.quote(message)
            webView.evaluateJavascript("receiveGroqTtsError($quotedMessage)", null)
        }
    }

    /**
     * Fetches TTS audio synchronously. Must be called from a background thread. Returns
     * Pair(base64Audio, mimeType) or null on failure.
     */
    private fun fetchTtsAudio(text: String, apiKey: String): Pair<String, String>? {
        return try {
            val jsonBody = JSONObject()
            jsonBody.put("model", "canopylabs/orpheus-v1-english")
            jsonBody.put("input", text)
            jsonBody.put("voice", "hannah")
            jsonBody.put("response_format", "wav")

            val requestBody =
                    jsonBody.toString()
                            .toRequestBody("application/json; charset=utf-8".toMediaType())

            val request =
                    Request.Builder()
                            .url("https://api.groq.com/openai/v1/audio/speech")
                            .addHeader("Authorization", "Bearer $apiKey")
                            .post(requestBody)
                            .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    DebugLog.e("GroqInterface", "TTS request failed: ${response.code}")
                    mainHandler.post {
                        Toast.makeText(context, "TTS failed: ${response.code}", Toast.LENGTH_SHORT)
                                .show()
                    }
                    return null
                }

                val bytes = response.body?.bytes()
                if (bytes != null && bytes.isNotEmpty()) {
                    val base64Audio = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    Pair(base64Audio, "audio/wav")
                } else {
                    DebugLog.e("GroqInterface", "TTS response body empty")
                    mainHandler.post {
                        Toast.makeText(context, "TTS failed: Empty response", Toast.LENGTH_SHORT)
                                .show()
                    }
                    null
                }
            }
        } catch (e: Exception) {
            DebugLog.e("GroqInterface", "TTS fetch failed", e)
            mainHandler.post {
                Toast.makeText(context, "TTS failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
            null
        }
    }

    private fun postResponseWithTts(text: String, base64Audio: String?, mimeType: String?) {
        mainHandler.post {
            if (base64Audio != null) {
                findMainActivity(context)?.prepareAudioForTtsPlayback()
            }
            val escapedText = text.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
            val audioArg = if (base64Audio != null) JSONObject.quote(base64Audio) else "null"
            val mimeArg = if (mimeType != null) JSONObject.quote(mimeType) else "null"
            webView.evaluateJavascript(
                    "receiveGroqResponseWithTts('$escapedText', $audioArg, $mimeArg)",
                    null
            )
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
        private const val DASHBOARD_URL =
                "file:///android_asset/AR_Dashboard_Landscape_Sidebar.html"
    }
}
