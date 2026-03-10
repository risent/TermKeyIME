package com.termkey.ime

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class VolcengineVoiceInputClient(
    private val config: Config,
    private val listener: Listener,
) {
    companion object {
        private const val TAG = "TermKeyVoice"
    }

    data class Config(
        val appKey: String,
        val accessToken: String,
        val resourceId: String,
        val language: String,
        val endpoint: String = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async",
        val sampleRate: Int = 16000,
    )

    interface Listener {
        fun onListeningChanged(isListening: Boolean)
        fun onPartialResult(text: String)
        fun onFinalResult(text: String)
        fun onError(message: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val httpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    @Volatile
    private var isStopping = false

    @Volatile
    private var isClosed = false

    @Volatile
    private var latestText = ""

    @Volatile
    private var audioSequence = 2

    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    private var webSocket: WebSocket? = null

    fun start() {
        Log.d(TAG, "Starting voice client endpoint=${config.endpoint} resourceId=${config.resourceId} language=${config.language}")

        Log.d(TAG, "Config appkey=${config.appKey} resourceId=${config.resourceId} accessTokenPresent=${config.accessToken.isNotBlank()}")
        val request = Request.Builder()
            .url(config.endpoint)
            .addHeader("X-Api-App-Key", config.appKey)
            .addHeader("X-Api-Resource-Id", config.resourceId)
            .addHeader("X-Api-Access-Key", config.accessToken)
            .addHeader("X-Api-Connect-Id", UUID.randomUUID().toString())
            .build()

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(
                    TAG,
                    "WebSocket opened code=${response.code} connectId=${response.header("X-Api-Connect-Id")} logId=${response.header("X-Tt-Logid")}",
                )
                webSocket.send(ByteString.of(*buildFullRequestPacket()))
                notifyListening(true)
                startAudioStreaming(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleServerPacket(bytes.toByteArray())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing code=$code reason=$reason")
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed code=$code reason=$reason")
                closeInternal(notifyFinal = false)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (isClosed) return
                val logId = response?.header("X-Tt-Logid").orEmpty()
                val responseBody = runCatching { response?.body?.string().orEmpty() }.getOrDefault("")
                Log.e(
                    TAG,
                    "WebSocket failure code=${response?.code} logId=$logId body=$responseBody message=${t.message}",
                    t,
                )
                val message = buildString {
                    append(t.message ?: "WebSocket failure")
                    if (response?.code != null) {
                        append(" (HTTP ")
                        append(response.code)
                        append(")")
                    }
                    if (logId.isNotBlank()) {
                        append(" logid=")
                        append(logId)
                    }
                }
                postError(message)
                closeInternal(notifyFinal = true)
            }
        })
    }

    fun stop() {
        if (isStopping || isClosed) return
        Log.d(TAG, "Stopping voice client")
        isStopping = true
    }

    fun cancel() {
        if (isClosed) return
        Log.d(TAG, "Cancelling voice client")
        closeInternal(notifyFinal = false)
    }

    private fun startAudioStreaming(socket: WebSocket) {
        val chunkBytes = (config.sampleRate / 10) * 2
        val minBuffer = AudioRecord.getMinBufferSize(
            config.sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = maxOf(minBuffer, chunkBytes * 2)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            config.sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord initialization failed state=${recorder.state}")
            postError("AudioRecord initialization failed")
            closeInternal(notifyFinal = true)
            return
        }

        Log.d(TAG, "AudioRecord initialized sampleRate=${config.sampleRate} bufferSize=$bufferSize chunkBytes=$chunkBytes")
        audioRecord = recorder
        audioThread = Thread {
            val audioBuffer = ByteArray(chunkBytes)
            try {
                recorder.startRecording()
                while (!isClosed) {
                    val bytesRead = recorder.read(audioBuffer, 0, audioBuffer.size)
                    if (bytesRead < 0) {
                        postError("Audio capture failed: $bytesRead")
                        break
                    }
                    if (bytesRead == 0) {
                        continue
                    }

                    val packet = audioBuffer.copyOf(bytesRead)
                    val isLastPacket = isStopping
                    socket.send(ByteString.of(*buildAudioPacket(packet, isLastPacket)))
                    if (isLastPacket) {
                        Log.d(TAG, "Sent final audio packet sequence=${audioSequence - 1} bytes=$bytesRead")
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Audio capture exception", e)
                postError(e.message ?: "Audio capture exception")
            } finally {
                try {
                    recorder.stop()
                } catch (_: Exception) {
                }
                recorder.release()
                audioRecord = null
            }
        }.apply {
            name = "volc-voice-input"
            start()
        }
    }

    private fun buildFullRequestPacket(): ByteArray {
        val requestJson = JsonObject().apply {
            add("user", JsonObject().apply {
                addProperty("uid", UUID.randomUUID().toString())
            })
            add("audio", JsonObject().apply {
                addProperty("format", "pcm")
                addProperty("sample_rate", config.sampleRate)
                addProperty("bits", 16)
                addProperty("channel", 1)
                addProperty("codec", "raw")
                addProperty("language", config.language)
            })
            add("request", JsonObject().apply {
                addProperty("model_name", "bigmodel")
                addProperty("enable_itn", true)
                addProperty("enable_punc", true)
                addProperty("enable_ddc", false)
                addProperty("show_utterances", false)
            })
        }

        val payload = gzip(requestJson.toString().toByteArray(Charsets.UTF_8))
        val buffer = ByteArrayOutputStream()
        buffer.write(byteArrayOf(0x11, 0x10, 0x11, 0x00))
        writeInt(buffer, payload.size)
        buffer.write(payload)
        return buffer.toByteArray()
    }

    private fun buildAudioPacket(audio: ByteArray, isLastPacket: Boolean): ByteArray {
        val payload = gzip(audio)
        val sequence = if (isLastPacket) -audioSequence else audioSequence
        val flags = if (isLastPacket) 0x03 else 0x01
        val buffer = ByteArrayOutputStream()
        buffer.write(byteArrayOf(0x11, ((0x2 shl 4) or flags).toByte(), 0x01, 0x00))
        writeInt(buffer, sequence)
        writeInt(buffer, payload.size)
        buffer.write(payload)
        audioSequence += 1
        return buffer.toByteArray()
    }

    private fun handleServerPacket(packet: ByteArray) {
        if (packet.size < 4) return

        val headerSize = (packet[0].toInt() and 0x0F) * 4
        if (packet.size < headerSize) return

        val messageType = (packet[1].toInt() ushr 4) and 0x0F
        val messageFlags = packet[1].toInt() and 0x0F
        val compression = packet[2].toInt() and 0x0F
        var offset = headerSize

        when (messageType) {
            0x9 -> {
                if (packet.size < offset + 8) return
                offset += 4 // sequence number
                val payloadSize = readInt(packet, offset)
                offset += 4
                if (packet.size < offset + payloadSize) return
                val payloadBytes = packet.copyOfRange(offset, offset + payloadSize)
                val payload = if (compression == 0x1) gunzip(payloadBytes) else payloadBytes
                handleRecognitionJson(payload.toString(Charsets.UTF_8), messageFlags == 0x3)
            }
            0xF -> {
                if (packet.size < offset + 8) return
                val errorCode = readInt(packet, offset)
                offset += 4
                val payloadSize = readInt(packet, offset)
                offset += 4
                val errorBody = if (packet.size >= offset + payloadSize) {
                    packet.copyOfRange(offset, offset + payloadSize).toString(Charsets.UTF_8)
                } else {
                    ""
                }
                postError("[$errorCode] ${errorBody.ifBlank { "service error" }}")
                closeInternal(notifyFinal = true)
            }
        }
    }

    private fun handleRecognitionJson(json: String, isFinalPacket: Boolean) {
        Log.d(TAG, "Received recognition payload final=$isFinalPacket json=$json")
        val root = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull() ?: return
        val text = extractText(root).trim()
        if (text.isNotBlank()) {
            latestText = text
            postToMain { listener.onPartialResult(text) }
        }
        if (isFinalPacket) {
            val finalText = latestText
            closeInternal(notifyFinal = false)
            postToMain {
                listener.onListeningChanged(false)
                listener.onFinalResult(finalText)
            }
        }
    }

    private fun extractText(root: JsonObject): String {
        val result = root.get("result") ?: return ""
        return when {
            result.isJsonObject -> result.asJsonObject.get("text")?.asString.orEmpty()
            result.isJsonArray -> result.asJsonArray.firstOrNull()?.asJsonObject?.get("text")?.asString.orEmpty()
            else -> ""
        }
    }

    private fun closeInternal(notifyFinal: Boolean) {
        if (isClosed) return
        Log.d(TAG, "Closing voice client notifyFinal=$notifyFinal latestTextLength=${latestText.length}")
        isClosed = true
        isStopping = true
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null
        audioThread = null
        webSocket?.cancel()
        webSocket = null
        httpClient.dispatcher.executorService.shutdown()
        if (notifyFinal) {
            postToMain { listener.onListeningChanged(false) }
        }
    }

    private fun notifyListening(isListening: Boolean) {
        postToMain { listener.onListeningChanged(isListening) }
    }

    private fun postError(message: String) {
        Log.e(TAG, "Voice client error: $message")
        postToMain { listener.onError(message) }
    }

    private fun postToMain(block: () -> Unit) {
        mainHandler.post(block)
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { gzip ->
            gzip.write(bytes)
        }
        return output.toByteArray()
    }

    private fun gunzip(bytes: ByteArray): ByteArray {
        return GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
            input.readBytes()
        }
    }

    private fun writeInt(output: ByteArrayOutputStream, value: Int) {
        output.write((value ushr 24) and 0xFF)
        output.write((value ushr 16) and 0xFF)
        output.write((value ushr 8) and 0xFF)
        output.write(value and 0xFF)
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }
}
