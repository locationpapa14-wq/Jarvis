package com.jarvis.assistant.session

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.audio.AudioPlayer
import com.jarvis.assistant.audio.AudioRecorder
import com.jarvis.assistant.data.model.ConversationState
import com.jarvis.assistant.device.BackgroundAssistantService
import com.jarvis.assistant.device.ConfirmPolicy
import com.jarvis.assistant.device.DeviceAction
import com.jarvis.assistant.device.DeviceActionResult
import com.jarvis.assistant.device.ResultStatus
import com.jarvis.assistant.device.ScreenShareService
import com.jarvis.assistant.network.GeminiLiveWebSocket
import com.jarvis.assistant.ui.overlay.OverlayState
import com.jarvis.assistant.util.PromptGenerator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File

/** App-scoped live session: used by the home screen AND by the background wake-word flow. */
class JarvisSession(private val app: JarvisApp) {

    private val prefs get() = app.preferences
    private val chatRepository get() = app.chatRepository
    private val router get() = app.router
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _isSessionOn = MutableStateFlow(false)
    val isSessionOn: StateFlow<Boolean> = _isSessionOn.asStateFlow()
    private val _conversationState = MutableStateFlow(ConversationState.IDLE)
    val conversationState: StateFlow<ConversationState> = _conversationState.asStateFlow()
    private val _isMicMuted = MutableStateFlow(prefs.isMicMuted)
    val isMicMuted: StateFlow<Boolean> = _isMicMuted.asStateFlow()
    private val _connectionStatus = MutableStateFlow("READY")
    val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()
    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val events: SharedFlow<String> = _events.asSharedFlow()

    private var audioRecorder: AudioRecorder? = null
    private var audioPlayer: AudioPlayer? = null
    private var ws: GeminiLiveWebSocket? = null
    private val assistantText = StringBuilder()
    private val userText = StringBuilder()
    private var lastSpeech = 0L
    private var lastActivity = 0L
    private var wakeSession = false
    private var idleJob: Job? = null
    private var cameraJob: Job? = null
    private var screenJob: Job? = null

    init {
        scope.launch {
            conversationState.collect { s ->
                router.overlay.setState(when (s) {
                    ConversationState.LISTENING -> OverlayState.LISTENING
                    ConversationState.THINKING -> OverlayState.THINKING
                    ConversationState.SPEAKING -> OverlayState.SPEAKING
                    ConversationState.IDLE -> OverlayState.IDLE
                })
            }
        }
    }

    fun refreshSettings() {
        _isMicMuted.value = prefs.isMicMuted
        audioRecorder?.setMuted(prefs.isMicMuted)
        router.policy = runCatching { ConfirmPolicy.valueOf(prefs.confirmPolicy) }.getOrDefault(ConfirmPolicy.ASK_SENSITIVE)
    }

    fun toggle() { if (_isSessionOn.value) stop() else start(false) }

    fun toggleMicMute() {
        val m = !_isMicMuted.value
        _isMicMuted.value = m; prefs.isMicMuted = m; audioRecorder?.setMuted(m)
        _events.tryEmit(if (m) "Microphone Muted" else "Microphone Unmuted")
    }

    fun start(fromWake: Boolean) {
        if (_isSessionOn.value) return
        val apiKey = prefs.apiKey.trim()
        if (apiKey.isBlank()) { _events.tryEmit("Please configure your Gemini API Key in Settings first."); return }
        if (app.router.perms.microphone() != com.jarvis.assistant.device.CapState.ENABLED) {
            _events.tryEmit("Microphone permission is required."); return
        }
        refreshSettings()
        wakeSession = fromWake
        _isSessionOn.value = true
        _conversationState.value = ConversationState.IDLE
        assistantText.clear(); userText.clear()
        lastActivity = System.currentTimeMillis()

        audioPlayer = AudioPlayer(
            onPlaybackStateChanged = { playing ->
                if (playing) _conversationState.value = ConversationState.SPEAKING
                else { finalizeTurn(); if (_isSessionOn.value) _conversationState.value = ConversationState.LISTENING }
            },
            onPlaybackAmplitude = { amp -> if (_conversationState.value == ConversationState.SPEAKING) _audioLevel.value = amp }
        ).apply { start() }

        val prompt = PromptGenerator.generateSystemPrompt(personality = prefs.personality, userName = prefs.userName)
        ws = GeminiLiveWebSocket(apiKey, prefs.aiModel, prefs.voice, prompt, object : GeminiLiveWebSocket.Listener {
            override fun onConnectionStateChanged(status: String) {
                _connectionStatus.value = status
                if (status == "LIVE" && _conversationState.value == ConversationState.IDLE) _conversationState.value = ConversationState.LISTENING
            }
            override fun onAudioDataReceived(pcmData: ByteArray) {
                lastActivity = System.currentTimeMillis()
                _conversationState.value = ConversationState.SPEAKING
                audioPlayer?.enqueueAudio(pcmData)
            }
            override fun onAssistantTextReceived(textChunk: String) { assistantText.append(textChunk) }
            override fun onInputTranscript(text: String) { lastActivity = System.currentTimeMillis(); userText.append(text) }
            override fun onOutputTranscript(text: String) { assistantText.append(text) }
            override fun onToolCall(id: String, name: String, args: Map<String, Any?>) {
                lastActivity = System.currentTimeMillis()
                scope.launch { handleTool(id, name, args) }
            }
            override fun onInterrupted() {
                audioPlayer?.flush(); assistantText.clear()
                _conversationState.value = ConversationState.LISTENING
            }
            override fun onTurnCompleted() { finalizeTurn() }
            override fun onError(message: String) { _events.tryEmit(message) }
        }).apply { connect(scope) }

        audioRecorder = AudioRecorder { chunk, amplitude ->
            if (_isSessionOn.value) {
                ws?.sendAudioChunk(chunk)
                if (amplitude > 0.08f) {
                    lastSpeech = System.currentTimeMillis(); lastActivity = lastSpeech
                    if (_conversationState.value != ConversationState.SPEAKING) {
                        _conversationState.value = ConversationState.LISTENING; _audioLevel.value = amplitude
                    }
                } else if (_conversationState.value == ConversationState.LISTENING) {
                    _audioLevel.value = amplitude
                    if (lastSpeech > 0 && System.currentTimeMillis() - lastSpeech > 1500) _conversationState.value = ConversationState.THINKING
                }
            }
        }.apply { setMuted(prefs.isMicMuted); start(scope) }

        if (fromWake) startIdleWatcher()
    }

    private fun startIdleWatcher() {
        idleJob?.cancel()
        idleJob = scope.launch {
            while (isActive) {
                delay(1000)
                val idle = System.currentTimeMillis() - lastActivity
                if (idle > 15000 && _conversationState.value != ConversationState.SPEAKING) { stop(); break }
            }
        }
    }

    private suspend fun handleTool(id: String, name: String, args: Map<String, Any?>) {
        router.overlay.setState(OverlayState.ACTION)
        val result: DeviceActionResult = try {
            if (name == "CONFIRM_PENDING_ACTION") {
                val yes = args["confirmed"]?.toString()?.equals("true", true) == true
                router.confirm(yes).also { if (yes) afterAction(null, it) }
            } else {
                val a = DeviceAction.fromCall(name, args)
                if (a == null) DeviceActionResult.unsupported("Unknown action $name")
                else router.execute(a).also { afterAction(a, it) }
            }
        } catch (e: Exception) { DeviceActionResult.failed(e.message ?: "Action crashed") }
        openSettingsIfNeeded(result)
        ws?.sendToolResponse(id, name, result)
    }

    private fun afterAction(a: DeviceAction?, r: DeviceActionResult) {
        if (!r.ok) return
        when (a) {
            DeviceAction.CameraFront, DeviceAction.CameraBack -> startCameraLoop()
            DeviceAction.CameraStop -> cameraJob?.cancel()
            DeviceAction.CameraCapture -> router.camera.lastCapture?.let { sendFile(it) }
            DeviceAction.StopScreenShare -> { screenJob?.cancel(); ScreenShareService.stop(app) }
            else -> {}
        }
    }

    private fun sendFile(f: File) { scope.launch(Dispatchers.IO) { runCatching { ws?.sendVideoFrame(f.readBytes()) } } }

    private fun startCameraLoop() {
        cameraJob?.cancel()
        cameraJob = scope.launch {
            while (isActive && router.camera.isOpen) {
                delay(2000)
                val d = CompletableDeferred<DeviceActionResult>()
                router.camera.capture { d.complete(it) }
                if (d.await().ok) router.camera.lastCapture?.let { f ->
                    withContext(Dispatchers.IO) { runCatching { ws?.sendVideoFrame(f.readBytes()); f.delete() } }
                }
            }
        }
    }

    /** Called by ScreenShareService once MediaProjection is running. */
    fun onScreenShareStarted() {
        screenJob?.cancel()
        screenJob = scope.launch(Dispatchers.Default) {
            while (isActive && router.screen.isSharing) {
                router.screen.latestJpeg()?.let { ws?.sendVideoFrame(it) }
                delay(1000)
            }
        }
    }

    private fun openSettingsIfNeeded(r: DeviceActionResult) {
        val action = r.settingsIntentAction ?: return
        if (r.status != ResultStatus.NEEDS_PERMISSION && r.status != ResultStatus.NEEDS_USER_ENABLEMENT) return
        try {
            val i = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS || action == Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                i.data = Uri.parse("package:${app.packageName}")
            app.startActivity(i)
        } catch (_: Exception) {}
    }

    private fun finalizeTurn() {
        val reply = assistantText.toString().trim()
        val user = userText.toString().trim()
        if (reply.isNotEmpty() || user.isNotEmpty()) {
            chatRepository.addTurn(userText = if (user.isEmpty()) "Spoken user query" else user, jarvisText = reply)
        }
        assistantText.clear(); userText.clear()
    }

    fun stop() {
        if (!_isSessionOn.value) return
        _isSessionOn.value = false
        idleJob?.cancel(); cameraJob?.cancel(); screenJob?.cancel()
        finalizeTurn()
        audioRecorder?.stop(); audioRecorder = null
        audioPlayer?.flush(); audioPlayer?.release(); audioPlayer = null
        ws?.disconnect(); ws = null
        router.camera.stop()
        if (router.screen.isSharing) { ScreenShareService.stop(app) }
        _conversationState.value = ConversationState.IDLE
        _connectionStatus.value = "READY"
        _audioLevel.value = 0f
        router.overlay.stopEdge()
        if (!BackgroundAssistantService.running.value) router.overlay.hideOrb()
        BackgroundAssistantService.wakeRef?.resume()
    }
}
