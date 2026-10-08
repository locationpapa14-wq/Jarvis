package com.jarvis.assistant.device

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings

class AppLauncher(private val ctx: Context) {

    private val aliases = mapOf(
        "youtube" to "com.google.android.youtube",
        "whatsapp" to "com.whatsapp",
        "chrome" to "com.android.chrome",
        "browser" to "com.android.chrome",
        "maps" to "com.google.android.apps.maps",
        "gmail" to "com.google.android.gm",
        "camera" to "",
        "settings" to ""
    )

    fun isInstalled(pkg: String) = try {
        ctx.packageManager.getPackageInfo(pkg, 0); true
    } catch (e: PackageManager.NameNotFoundException) { false }

    fun resolvePackage(spoken: String): String? {
        val q = spoken.lowercase().trim()
        aliases[q]?.takeIf { it.isNotEmpty() && ctx.packageManager.getLaunchIntentForPackage(it) != null }?.let { return it }
        val pm = ctx.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(main, 0)
        return apps.firstOrNull { pm.getApplicationLabel(it.activityInfo.applicationInfo).toString().lowercase() == q }
            ?.activityInfo?.packageName
            ?: apps.firstOrNull { pm.getApplicationLabel(it.activityInfo.applicationInfo).toString().lowercase().contains(q) }
                ?.activityInfo?.packageName
    }

    fun open(spoken: String): DeviceActionResult {
        val q = spoken.lowercase().trim()
        if (q == "settings") return openSettings()
        if (q == "camera") return try {
            ctx.startActivity(Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            DeviceActionResult.success("Camera opened.")
        } catch (e: Exception) { DeviceActionResult.failed("No camera app available.") }
        val pkg = resolvePackage(q)
            ?: return DeviceActionResult.notFound("$spoken is not installed. I can search the Play Store for it.")
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
            ?: return DeviceActionResult.failed("$spoken has no launchable screen.")
        return try {
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            DeviceActionResult.success("Opened $spoken.")
        } catch (e: Exception) { DeviceActionResult.failed("Could not open $spoken: ${e.message}") }
    }

    fun openStoreSearch(name: String): DeviceActionResult = try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=${Uri.encode(name)}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        DeviceActionResult.success("Opened the store search for $name.")
    } catch (e: Exception) { DeviceActionResult.failed("No store app available.") }

    fun openSettings(): DeviceActionResult = try {
        ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        DeviceActionResult.success("Settings opened.")
    } catch (e: Exception) { DeviceActionResult.failed("Could not open settings.") }

    /** Deep-links straight into a WhatsApp chat for a number (digits only, with country code). */
    fun openWhatsAppChat(number: String): DeviceActionResult {
        if (!isInstalled("com.whatsapp") && !isInstalled("com.whatsapp.w4b"))
            return DeviceActionResult.notFound("WhatsApp is not installed.")
        val digits = number.filter { it.isDigit() }
        return try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits")).setPackage(
                if (isInstalled("com.whatsapp")) "com.whatsapp" else "com.whatsapp.w4b"
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
            DeviceActionResult.success("WhatsApp chat opened.")
        } catch (e: Exception) { DeviceActionResult.failed("Could not open WhatsApp chat: ${e.message}") }
    }
}
