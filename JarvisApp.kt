package com.jarvis.assistant

import android.app.Application
import android.content.Intent
import com.jarvis.assistant.data.preferences.AppPreferences
import com.jarvis.assistant.data.repository.ChatRepository
import com.jarvis.assistant.device.BackgroundAssistantService
import com.jarvis.assistant.device.DeviceActionResult
import com.jarvis.assistant.device.DeviceCommandRouter
import com.jarvis.assistant.device.ScreenShareConsentActivity
import com.jarvis.assistant.session.JarvisSession

class JarvisApp : Application() {

    lateinit var preferences: AppPreferences
        private set
    lateinit var chatRepository: ChatRepository
        private set

    val router: DeviceCommandRouter by lazy {
        DeviceCommandRouter(this).also { r ->
            r.requestScreenShareConsent = {
                startActivity(Intent(this, ScreenShareConsentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            r.startBackground = {
                BackgroundAssistantService.start(this)
                DeviceActionResult.success("Background mode starting.")
            }
            r.stopBackground = {
                BackgroundAssistantService.stop(this)
                DeviceActionResult.success("Background mode stopped.")
            }
        }
    }

    val session: JarvisSession by lazy { JarvisSession(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        preferences = AppPreferences(this)
        chatRepository = ChatRepository(preferences)
    }

    companion object {
        lateinit var instance: JarvisApp
            private set
    }
}
