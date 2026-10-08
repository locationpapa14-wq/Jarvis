package com.jarvis.assistant.device

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.jarvis.assistant.JarvisApp

/** Foreground service of type mediaProjection (required by Android 10+/14) that owns the capture. */
class ScreenShareService : Service() {
    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "com.jarvis.assistant.STOP_SHARE"
        fun stop(c: Context) { c.startService(Intent(c, ScreenShareService::class.java).setAction(ACTION_STOP)) }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as JarvisApp
        if (intent?.action == ACTION_STOP) {
            app.router.screen.stop(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY
        }
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)
        if (data == null) { stopSelf(); return START_NOT_STICKY }

        if (Build.VERSION.SDK_INT >= 26)
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel("jarvis_share", "JARVIS screen share", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 1,
            Intent(this, ScreenShareService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, "jarvis_share")
            .setContentTitle("JARVIS is sharing your screen")
            .setSmallIcon(com.jarvis.assistant.R.drawable.ic_mic)
            .setOngoing(true).addAction(0, "Stop", stop).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(2001, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(2001, n)

        val r = app.router.screen.onConsentResult(code, data)
        if (!r.ok) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
        app.session.onScreenShareStarted()
        return START_NOT_STICKY
    }

    override fun onDestroy() { (application as JarvisApp).router.screen.stop(); super.onDestroy() }
}
