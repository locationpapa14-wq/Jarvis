package com.jarvis.assistant.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import androidx.core.content.ContextCompat
import com.jarvis.assistant.accessibility.JarvisAccessibilityService

enum class CapState { ENABLED, NEEDS_PERMISSION, NEEDS_SYSTEM_ENABLEMENT }

class PermissionManager(private val ctx: Context) {

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    fun microphone() = if (granted(Manifest.permission.RECORD_AUDIO)) CapState.ENABLED else CapState.NEEDS_PERMISSION
    fun camera() = if (granted(Manifest.permission.CAMERA)) CapState.ENABLED else CapState.NEEDS_PERMISSION
    fun contacts() = if (granted(Manifest.permission.READ_CONTACTS)) CapState.ENABLED else CapState.NEEDS_PERMISSION
    fun phone() = if (granted(Manifest.permission.CALL_PHONE)) CapState.ENABLED else CapState.NEEDS_PERMISSION
    fun notifications() =
        if (Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)) CapState.ENABLED
        else CapState.NEEDS_PERMISSION

    fun overlay() = if (Settings.canDrawOverlays(ctx)) CapState.ENABLED else CapState.NEEDS_SYSTEM_ENABLEMENT

    fun accessibility(): CapState {
        val expected = "${ctx.packageName}/${JarvisAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        if (!enabled) return CapState.NEEDS_SYSTEM_ENABLEMENT
        val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return CapState.NEEDS_SYSTEM_ENABLEMENT
        val sp = TextUtils.SimpleStringSplitter(':').apply { setString(list) }
        while (sp.hasNext()) {
            val s = sp.next()
            if (s.equals(expected, true) || s.equals("${ctx.packageName}/.accessibility.JarvisAccessibilityService", true))
                return CapState.ENABLED
        }
        return CapState.NEEDS_SYSTEM_ENABLEMENT
    }

    fun batteryOptimizationAction() = Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
}
