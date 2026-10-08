package com.jarvis.assistant.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.data.model.ConversationState
import com.jarvis.assistant.device.BackgroundAssistantService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Thin view-model: the real live session is app-scoped (JarvisSession) so background mode can reuse it. */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as JarvisApp
    private val session = app.session
    private val preferences = app.preferences

    val isSessionOn: StateFlow<Boolean> = session.isSessionOn
    val conversationState: StateFlow<ConversationState> = session.conversationState
    val isMicMuted: StateFlow<Boolean> = session.isMicMuted
    val connectionStatus: StateFlow<String> = session.connectionStatus
    val audioLevel: StateFlow<Float> = session.audioLevel
    val eventFlow: SharedFlow<String> = session.events

    private val _liveTime = MutableStateFlow("")
    val liveTime: StateFlow<String> = _liveTime.asStateFlow()

    private val _personalityName = MutableStateFlow(preferences.personality)
    val personalityName: StateFlow<String> = _personalityName.asStateFlow()

    private var timeClockJob: Job? = null

    init {
        timeClockJob = viewModelScope.launch(Dispatchers.Default) {
            val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
            while (isActive) { _liveTime.value = sdf.format(Date()); delay(1000) }
        }
    }

    fun refreshSettings() {
        _personalityName.value = preferences.personality
        session.refreshSettings()
    }

    fun toggleSession() = session.toggle()
    fun toggleMicMute() = session.toggleMicMute()

    override fun onCleared() {
        super.onCleared()
        timeClockJob?.cancel()
        // A manual (non-background) session cannot keep the mic alive without a foreground service.
        if (session.isSessionOn.value && !BackgroundAssistantService.running.value) session.stop()
    }
}
