package com.jarvis.assistant.network

import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.jarvis.assistant.data.model.GeminiConstants
import com.jarvis.assistant.device.DeviceActionResult
import com.jarvis.assistant.device.GeminiToolDeclarations
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class GeminiLiveWebSocket(
    private val apiKey: String,
    private val model: String,
    private val voiceName: String,
    private val systemPrompt: String,
    private val listener: Listener
) {
    interface Listener {
        fun onConnectionStateChanged(status: String)
        fun onAudioDataReceived(pcmData: ByteArray)
        fun onAssistantTextReceived(textChunk: String)
        fun onInputTranscript(text: String)
        fun onOutputTranscript(text: String)
        fun onToolCall(id: String, name: String, args: Map<String, Any?>)
        fun onInterrupted()
        fun onTurnCompleted()
        fun onError(message: String)
    }

    companion object { private const val TAG = "GeminiLiveWebSocket" }

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .pingInterval(GeminiConstants.KEEPALIVE_INTERVAL_SEC, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile private var webSocket: WebSocket? = null
    private val isConnected = AtomicBoolean(false)
    private val isManuallyStopped = AtomicBoolean(false)
    private val generation = AtomicInteger(0)
    private var coroutineScope: CoroutineScope? = null
    private var sessionRenewalJob: Job? = null
    private var reconnectJob: Job? = null

    fun connect(scope: CoroutineScope) {
        coroutineScope = scope
        isManuallyStopped.set(false)
        initiateConnection()
    }

    private fun initiateConnection() {
        if (apiKey.isBlank()) {
            listener.onError("Gemini API Key is missing. Please configure it in Settings.")
            return
        }
        listener.onConnectionStateChanged("CONNECTING...")
        try {
            val request = Request.Builder().url("${GeminiConstants.WS_BASE_URL}?key=$apiKey").build()
            val gen = generation.incrementAndGet()
            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                private fun stale(ws: WebSocket) = gen != generation.get()

                override fun onOpen(ws: WebSocket, response: Response) {
                    if (stale(ws)) return
                    isConnected.set(true)
                    sendSetupMessage(ws)
                    startSessionRenewal()
                }

                override fun onMessage(ws: WebSocket, text: String) { if (!stale(ws)) handleIncomingMessage(text) }
                override fun onMessage(ws: WebSocket, bytes: ByteString) { if (!stale(ws)) handleIncomingMessage(bytes.utf8()) }

                override fun onClosing(ws: WebSocket, code: Int, reason: String) { ws.close(1000, null) }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    if (stale(ws)) return
                    isConnected.set(false); stopTimers()
                    if (code == 1008) {
                        listener.onError("Model not supported or invalid key: $reason")
                        listener.onConnectionStateChanged("OFFLINE")
                    } else if (!isManuallyStopped.get()) {
                        listener.onConnectionStateChanged("RECONNECTING...")
                        scheduleReconnect()
                    } else listener.onConnectionStateChanged("OFFLINE")
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    if (stale(ws)) return
                    Log.e(TAG, "WebSocket failure: ${t.message}", t)
                    isConnected.set(false); stopTimers()
                    if (!isManuallyStopped.get()) {
                        listener.onError("Connection lost: ${t.message ?: "unknown"}. Retrying...")
                        listener.onConnectionStateChanged("RECONNECTING...")
                        scheduleReconnect()
                    } else listener.onConnectionStateChanged("OFFLINE")
                }
            })
        } catch (e: Exception) {
            listener.onError("Connection failed: ${e.message}")
            listener.onConnectionStateChanged("OFFLINE")
        }
    }

    private fun sendSetupMessage(ws: WebSocket) {
        val setup = JsonObject().apply {
            addProperty("model", model)
            add("generationConfig", JsonObject().apply {
                add("responseModalities", JsonArray().apply { add("AUDIO") })
                add("speechConfig", JsonObject().apply {
                    add("voiceConfig", JsonObject().apply {
                        add("prebuiltVoiceConfig", JsonObject().apply { addProperty("voiceName", voiceName) })
                    })
                })
            })
            add("systemInstruction", JsonObject().apply {
                add("parts", JsonArray().apply { add(JsonObject().apply { addProperty("text", systemPrompt) }) })
            })
            add("tools", JsonParser.parseString(GeminiToolDeclarations.toolsJson().toString()))
            add("inputAudioTranscription", JsonObject())
            add("outputAudioTranscription", JsonObject())
        }
        ws.send(JsonObject().apply { add("setup", setup) }.toString())
    }

    private fun handleIncomingMessage(jsonText: String) {
        try {
            val root = JsonParser.parseString(jsonText).asJsonObject

            if (root.has("toolCall")) {
                val calls = root.getAsJsonObject("toolCall").getAsJsonArray("functionCalls")
                if (calls != null) for (c in calls) {
                    val o = c.asJsonObject
                    val id = o.get("id")?.asString ?: ""
                    val name = o.get("name")?.asString ?: continue
                    @Suppress("UNCHECKED_CAST")
                    val args = (if (o.has("args")) gson.fromJson(o.get("args"), Map::class.java) else emptyMap<String, Any?>()) as Map<String, Any?>
                    listener.onToolCall(id, name, args)
                }
            }

            if (root.has("serverContent")) {
                val sc = root.getAsJsonObject("serverContent")
                if (sc.has("interrupted") && sc.get("interrupted").asBoolean) listener.onInterrupted()

                if (sc.has("inputTranscription")) {
                    sc.getAsJsonObject("inputTranscription").get("text")?.asString?.let { if (it.isNotEmpty()) listener.onInputTranscript(it) }
                }
                if (sc.has("outputTranscription")) {
                    sc.getAsJsonObject("outputTranscription").get("text")?.asString?.let { if (it.isNotEmpty()) listener.onOutputTranscript(it) }
                }

                if (sc.has("modelTurn")) {
                    val parts = sc.getAsJsonObject("modelTurn").getAsJsonArray("parts")
                    if (parts != null) for (p in parts) {
                        val part = p.asJsonObject
                        if (part.has("text")) {
                            val t = part.get("text").asString
                            if (!t.isNullOrBlank()) listener.onAssistantTextReceived(t)
                        }
                        if (part.has("inlineData")) {
                            val d = part.getAsJsonObject("inlineData")
                            if (d.has("data")) listener.onAudioDataReceived(Base64.decode(d.get("data").asString, Base64.DEFAULT))
                        }
                    }
                }
                if (sc.has("turnComplete") && sc.get("turnComplete").asBoolean) listener.onTurnCompleted()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing server JSON: ${e.message}", e)
        }
    }

    fun sendAudioChunk(pcm16k: ByteArray) {
        if (!isConnected.get() || pcm16k.isEmpty()) return
        try {
            val audio = JsonObject().apply {
                addProperty("mimeType", "audio/pcm;rate=16000")
                addProperty("data", Base64.encodeToString(pcm16k, Base64.NO_WRAP))
            }
            webSocket?.send(JsonObject().apply { add("realtimeInput", JsonObject().apply { add("audio", audio) }) }.toString())
        } catch (e: Exception) { Log.e(TAG, "Error sending audio: ${e.message}") }
    }

    /** Sends a JPEG frame (camera or screen) so Gemini can see it. */
    fun sendVideoFrame(jpeg: ByteArray) {
        if (!isConnected.get() || jpeg.isEmpty()) return
        try {
            val video = JsonObject().apply {
                addProperty("mimeType", "image/jpeg")
                addProperty("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))
            }
            webSocket?.send(JsonObject().apply { add("realtimeInput", JsonObject().apply { add("video", video) }) }.toString())
        } catch (e: Exception) { Log.e(TAG, "Error sending frame: ${e.message}") }
    }

    fun sendToolResponse(callId: String, name: String, r: DeviceActionResult) {
        if (!isConnected.get()) return
        webSocket?.send(GeminiToolDeclarations.toolResponse(callId, name, r).toString())
    }

    private fun startSessionRenewal() {
        sessionRenewalJob?.cancel()
        sessionRenewalJob = coroutineScope?.launch(Dispatchers.IO) {
            delay(GeminiConstants.SESSION_RENEWAL_MS)
            if (isActive && !isManuallyStopped.get()) {
                val old = webSocket
                initiateConnection()
                try { old?.close(1000, "Session renewal") } catch (_: Exception) {}
            }
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = coroutineScope?.launch(Dispatchers.IO) {
            delay(3000)
            if (!isManuallyStopped.get()) initiateConnection()
        }
    }

    private fun stopTimers() { sessionRenewalJob?.cancel(); sessionRenewalJob = null }

    fun disconnect() {
        isManuallyStopped.set(true)
        generation.incrementAndGet()
        stopTimers(); reconnectJob?.cancel(); reconnectJob = null
        isConnected.set(false)
        try { webSocket?.close(1000, "Client stopped session") } catch (e: Exception) { Log.e(TAG, "close: ${e.message}") }
        webSocket = null
        listener.onConnectionStateChanged("OFFLINE")
    }
}
