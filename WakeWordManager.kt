package com.jarvis.assistant.wakeword

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * FALLBACK wake-word: repeatedly uses Android's SpeechRecognizer and matches the phrase in text.
 * This is not a local always-on engine; it may beep/restart and is subject to OEM limits.
 * Gemini Live is not touched until the phrase is detected.
 */
class WakeWordManager(private val ctx: Context, private val onWake: () -> Unit) {
    private val _state = MutableStateFlow(WakeWordState.DISABLED)
    val state: StateFlow<WakeWordState> = _state
    var phrases = listOf("hey jarvis", "jarvis")
    private val main = Handler(Looper.getMainLooper())
    private var rec: SpeechRecognizer? = null
    private var enabled = false

    fun setPhrase(p: String) { phrases = listOf(p.lowercase().trim()).filter { it.isNotEmpty() }.ifEmpty { listOf("jarvis") } }

    fun start() {
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) { _state.value = WakeWordState.ERROR; return }
        enabled = true; _state.value = WakeWordState.ARMED
        main.post { listen() }
    }

    fun stop() {
        enabled = false; _state.value = WakeWordState.DISABLED
        main.post { rec?.destroy(); rec = null }
    }

    /** Call after a command finishes so the wake listener re-arms (and the mic is free for Gemini meanwhile). */
    fun pause() { main.post { rec?.cancel() }; if (enabled) _state.value = WakeWordState.COMMAND_ACTIVE }
    fun resume() { if (enabled) { _state.value = WakeWordState.ARMED; main.postDelayed({ listen() }, 400) } }

    private fun listen() {
        if (!enabled || _state.value == WakeWordState.COMMAND_ACTIVE) return
        rec?.destroy()
        rec = SpeechRecognizer.createSpeechRecognizer(ctx).also { r ->
            r.setRecognitionListener(object : RecognitionListener {
                override fun onResults(b: Bundle?) { check(b); again() }
                override fun onPartialResults(b: Bundle?) { check(b) }
                override fun onError(e: Int) {
                    if (e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) { _state.value = WakeWordState.ERROR; enabled = false }
                    else again()
                }
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(t: Int, b: Bundle?) {}
            })
            r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            })
        }
    }

    private fun again() { if (enabled && _state.value == WakeWordState.ARMED) main.postDelayed({ listen() }, 300) }

    private fun check(b: Bundle?) {
        if (_state.value != WakeWordState.ARMED) return
        val texts = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
        if (texts.any { t -> phrases.any { t.lowercase().contains(it) } }) {
            _state.value = WakeWordState.DETECTING
            rec?.cancel()
            _state.value = WakeWordState.COMMAND_ACTIVE
            onWake()
        }
    }
}
