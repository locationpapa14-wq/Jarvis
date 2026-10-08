package com.jarvis.assistant.device

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

class CallController(private val ctx: Context, private val perms: PermissionManager) {

    fun call(number: String): DeviceActionResult {
        val clean = number.filter { it.isDigit() || it == '+' }
        if (clean.length < 3) return DeviceActionResult.failed("That does not look like a valid phone number.")
        return try {
            if (perms.phone() == CapState.ENABLED) {
                ctx.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$clean")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                DeviceActionResult.success("Calling $clean.")
            } else {
                ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$clean")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                DeviceActionResult.success("Phone permission is off, so I opened the dialer. Please press call.")
            }
        } catch (e: SecurityException) {
            DeviceActionResult.needsPermission("Phone permission is required to place calls.", Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        } catch (e: Exception) {
            DeviceActionResult.failed("Could not start the call: ${e.message}")
        }
    }
}
