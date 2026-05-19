package com.TapLinkX3.controller

import android.os.Handler
import android.os.Looper
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class PhoneGroqClient {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val client =
            OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .build()
    private val history = JSONArray()

    fun ask(apiKey: String, message: String, callback: (Result<String>) -> Unit) {
        val trimmedKey = apiKey.trim()
        val trimmedMessage = message.trim()
        if (trimmedKey.isBlank()) {
            callback(Result.failure(IllegalArgumentException("Groq API key is required.")))
            return
        }
        if (trimmedMessage.isBlank()) {
            callback(Result.failure(IllegalArgumentException("Ask a question first.")))
            return
        }

        Thread {
                    try {
                        val messages = JSONArray().apply {
                            put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                            val historyStart =
                                    (history.length() - MAX_HISTORY_ITEMS).coerceAtLeast(0)
                            for (i in historyStart until history.length()) {
                                val item = history.getJSONObject(i)
                                item.put(
                                        "content",
                                        truncateForRequest(
                                                item.optString("content"),
                                                MAX_HISTORY_MESSAGE_CHARS
                                        )
                                )
                                put(item)
                            }
                            put(
                                    JSONObject()
                                            .put("role", "user")
                                            .put(
                                                    "content",
                                                    truncateForRequest(
                                                            trimmedMessage,
                                                            MAX_CURRENT_MESSAGE_CHARS
                                                    )
                                            )
                            )
                        }

                        val body =
                                JSONObject()
                                        .put("model", "groq/compound")
                                        .put("messages", messages)
                                        .toString()
                                        .toRequestBody(
                                                "application/json; charset=utf-8".toMediaType()
                                        )

                        val request =
                                Request.Builder()
                                        .url("https://api.groq.com/openai/v1/chat/completions")
                                        .addHeader("Authorization", "Bearer $trimmedKey")
                                        .post(body)
                                        .build()

                        var actualResponse = client.newCall(request).execute()
                        if (!actualResponse.isSuccessful) {
                            val failedCode = actualResponse.code
                            actualResponse.close()

                            val fallbackMessages =
                                    if (failedCode == 413 ||
                                                    messages.toString().length >
                                                            MAX_REQUEST_BODY_CHARS
                                    ) {
                                        compactMessages(trimmedMessage)
                                    } else {
                                        messages
                                    }

                            val fallbackBody =
                                    JSONObject()
                                            .put("model", "llama-3.3-70b-versatile")
                                            .put("messages", fallbackMessages)
                                            .toString()
                                            .toRequestBody(
                                                    "application/json; charset=utf-8".toMediaType()
                                            )
                            val fallbackRequest =
                                    Request.Builder()
                                            .url("https://api.groq.com/openai/v1/chat/completions")
                                            .addHeader("Authorization", "Bearer $trimmedKey")
                                            .post(fallbackBody)
                                            .build()

                            actualResponse = client.newCall(fallbackRequest).execute()
                        }

                        actualResponse.use { response ->
                            val responseBody = response.body?.string()
                            if (!response.isSuccessful) {
                                throw IllegalStateException(
                                        "Groq error ${response.code}: ${responseBody ?: response.message}"
                                )
                            }

                            val content =
                                    JSONObject(responseBody ?: "")
                                            .getJSONArray("choices")
                                            .getJSONObject(0)
                                            .getJSONObject("message")
                                            .getString("content")
                                            .trim()
                            history.put(JSONObject().put("role", "user").put("content", trimmedMessage))
                            history.put(JSONObject().put("role", "assistant").put("content", content))
                            trimHistory()
                            mainHandler.post { callback(Result.success(content)) }
                        }
                    } catch (e: Exception) {
                        mainHandler.post { callback(Result.failure(e)) }
                    }
                }
                .start()
    }

    fun clearHistory() {
        while (history.length() > 0) {
            history.remove(0)
        }
    }

    private fun trimHistory() {
        while (history.length() > MAX_HISTORY_ITEMS) {
            history.remove(0)
        }
    }

    private fun compactMessages(message: String): JSONArray {
        return JSONArray()
                .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                .put(
                        JSONObject()
                                .put("role", "user")
                                .put(
                                        "content",
                                        truncateForRequest(message, MAX_COMPACT_MESSAGE_CHARS)
                                )
                )
    }

    private fun truncateForRequest(text: String, maxChars: Int): String {
        val value = text.trim()
        if (value.length <= maxChars) return value
        return value.take(maxChars) + "\n[Truncated to keep AI request under size limits.]"
    }

    companion object {
        private const val MAX_HISTORY_ITEMS = 8
        private const val MAX_HISTORY_MESSAGE_CHARS = 1800
        private const val MAX_CURRENT_MESSAGE_CHARS = 6000
        private const val MAX_COMPACT_MESSAGE_CHARS = 4000
        private const val MAX_REQUEST_BODY_CHARS = 18000
        private const val SYSTEM_PROMPT =
                """You are TapLink AI, the phone-side assistant for TapLink X3 on RayNeo X3 Pro glasses.

Primary behavior:
- Give direct, useful answers in plain language.
- Keep responses concise by default.
- Prioritize actionable steps.
- Ask a short clarifying question when needed.

Context:
- The user may be controlling TapLink X3 from an Android phone companion app.
- TapLink X3 is a browser and XR input shell for RayNeo X3 Pro glasses.
- You cannot directly control the glasses unless the app explicitly exposes a control.

Style:
- Be accurate, neutral, and practical.
- If uncertain, say so briefly and suggest the next best step."""
    }
}
