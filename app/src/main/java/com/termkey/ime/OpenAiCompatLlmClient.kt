package com.termkey.ime

import android.os.Handler
import android.os.Looper
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

internal class OpenAiCompatLlmClient(
    private val config: Config,
    private val listener: Listener,
) {
    data class Config(
        val baseUrl: String,
        val apiKey: String,
        val model: String,
        val timeoutSeconds: Long = 30L,
    )

    data class Message(
        val role: String,
        val content: String,
    )

    interface Listener {
        fun onSuccess(texts: List<String>)
        fun onError(message: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var activeCall: Call? = null

    fun run(messages: List<Message>) {
        val requestBody = buildRequestJson(config, messages)
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val url = config.baseUrl.trimEnd('/') + "/chat/completions"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()

        activeCall = httpClient.newCall(request).also { call ->
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (call.isCanceled()) return
                    postError(e.message ?: "Network request failed")
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { rawResponse ->
                        val body = rawResponse.body?.string().orEmpty()
                        if (!rawResponse.isSuccessful) {
                            postError(parseErrorMessage(body, rawResponse.code))
                            return
                        }
                        val texts = parseResponseTexts(body)
                        if (texts.isEmpty()) {
                            postError("Model returned an empty response")
                        } else {
                            postSuccess(texts)
                        }
                    }
                }
            })
        }
    }

    fun cancel() {
        activeCall?.cancel()
        activeCall = null
    }

    private fun postSuccess(texts: List<String>) {
        mainHandler.post {
            activeCall = null
            listener.onSuccess(texts)
        }
    }

    private fun postError(message: String) {
        mainHandler.post {
            activeCall = null
            listener.onError(message)
        }
    }

    companion object {
        internal fun buildRequestJson(config: Config, messages: List<Message>): String {
            val payload = JsonObject().apply {
                addProperty("model", config.model)
                addProperty("stream", false)
                add(
                    "messages",
                    JsonArray().apply {
                        messages.forEach { message ->
                            add(
                                JsonObject().apply {
                                    addProperty("role", message.role)
                                    addProperty("content", message.content)
                                },
                            )
                        }
                    },
                )
            }
            return Gson().toJson(payload)
        }

        internal fun parseResponseTexts(body: String): List<String> {
            val root = JsonParser.parseString(body).asJsonObject
            val choices = root.getAsJsonArray("choices") ?: return emptyList()
            return choices.mapNotNull { choice ->
                parseChoiceContent(choice.asJsonObject.getAsJsonObject("message")?.get("content"))
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            }
        }

        internal fun parseErrorMessage(body: String, code: Int): String {
            val parsed = runCatching {
                JsonParser.parseString(body).asJsonObject
                    .getAsJsonObject("error")
                    ?.get("message")
                    ?.asString
            }.getOrNull()
            val suffix = parsed?.takeIf { it.isNotBlank() } ?: "HTTP $code"
            return "LLM request failed: $suffix"
        }

        private fun parseChoiceContent(content: com.google.gson.JsonElement?): String? {
            if (content == null) return null
            if (content.isJsonPrimitive) {
                return content.asString
            }
            if (!content.isJsonArray) return null

            return content.asJsonArray.mapNotNull { part ->
                val partObject = part as? JsonObject ?: return@mapNotNull null
                when {
                    partObject.has("text") -> partObject.get("text").asString
                    partObject.get("type")?.asString == "output_text" && partObject.has("text") -> partObject.get("text").asString
                    else -> null
                }
            }.joinToString("").takeIf { it.isNotBlank() }
        }
    }
}
