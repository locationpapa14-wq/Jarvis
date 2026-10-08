package com.jarvis.assistant.device

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.ui.overlay.OverlayState
import com.jarvis.assistant.wakeword.WakeWordManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class BackgroundAssistantService : Service() {

    companion object {
        const val CH = "jarvis_background"
        const val ACTION_START = "com.jarvis.assistant.START_BG"
        const val ACTION_STOP = "com.jarvis.assistant.STOP_BG"
        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running
        @Volatile var wakeRef: WakeWordManager? = null

        fun start(c: Context) {
            val i = Intent(c, BackgroundAssistantService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
        }
        fun stop(c: Context) { c.startService(Intent(c, BackgroundAssistantService::class.java).setAction(ACTION_STOP)) }
    }

    private var wake: WakeWordManager? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as JarvisApp
        if (intent?.action == ACTION_STOP) { shutdown(); return START_NOT_STICKY }
        if (_running.value) return START_STICKY
        createChannel()
        val n = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(1001, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(1001, n)
        } catch (e: Exception) { stopSelf(); return START_NOT_STICKY }
        if (app.router.perms.microphone() != CapState.ENABLED) {
            app.router.overlay.removeAll(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY
        }
        app.preferences.backgroundMode = true
        if (app.preferences.wakeWordEnabled) {
            wake = WakeWordManager(this) { onWake(app) }.also {
                it.setPhrase(app.preferences.wakePhrase); wakeRef = it; it.start()
            }
        }
        if (app.router.overlay.canDraw()) app.router.overlay.showOrb()
        _running.value = true
        return START_STICKY
    }

    private fun onWake(app: JarvisApp) {
        val o = app.router.overlay
        o.edgeIntensity = app.preferences.edgeIntensity / 100f
        o.edgeDurationMs = app.preferences.edgeDurationSec * 1000L
        if (app.preferences.edgeLightingEnabled) o.startEdge()
        o.showOrb(); o.setState(OverlayState.LISTENING)
        app.session.start(true)
    }

    private fun shutdown() {
        val app = application as JarvisApp
        app.preferences.backgroundMode = false
        wake?.stop(); wake = null; wakeRef = null
        app.session.stop()
        app.router.overlay.removeAll()
        _running.value = false
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    override fun onDestroy() { wake?.stop(); wakeRef = null; _running.value = false; super.onDestroy() }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CH, "JARVIS background", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun buildNotification(): Notification {
        val stop = PendingIntent.getService(this, 0,
            Intent(this, BackgroundAssistantService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CH)
            .setContentTitle("JARVIS is listening for the wake word")
            .setContentText("Say your wake phrase. Tap Stop to turn this off.")
            .setSmallIcon(com.jarvis.assistant.R.drawable.ic_mic)
            .setOngoing(true).addAction(0, "Stop", stop).build()
    }
}
