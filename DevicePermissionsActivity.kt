package com.jarvis.assistant.ui.device

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.jarvis.assistant.device.CapState
import com.jarvis.assistant.device.PermissionManager

class DevicePermissionsActivity : AppCompatActivity() {
    private lateinit var pm: PermissionManager
    private lateinit var list: LinearLayout
    private val runtime = registerForActivityResult(ActivityResultContracts.RequestPermission()) { render() }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        pm = PermissionManager(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 60, 40, 40) }
        setContentView(ScrollView(this).apply { addView(list, ViewGroup.LayoutParams(-1, -2)); setBackgroundColor(0xFF05070D.toInt()) })
        title = "Device Control & Permissions"
    }

    override fun onResume() { super.onResume(); render() }

    private fun render() {
        list.removeAllViews()
        row("Microphone", pm.microphone()) { runtime.launch(Manifest.permission.RECORD_AUDIO) }
        row("Camera", pm.camera()) { runtime.launch(Manifest.permission.CAMERA) }
        row("Contacts", pm.contacts()) { runtime.launch(Manifest.permission.READ_CONTACTS) }
        row("Phone (direct calls)", pm.phone()) { runtime.launch(Manifest.permission.CALL_PHONE) }
        row("Notifications", pm.notifications()) {
            if (Build.VERSION.SDK_INT >= 33) runtime.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        row("Display over other apps", pm.overlay()) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        row("Accessibility service", pm.accessibility()) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        row("Battery optimization (background)", CapState.NEEDS_SYSTEM_ENABLEMENT) {
            startActivity(Intent(pm.batteryOptimizationAction()))
        }
        val note = TextView(this).apply {
            text = "Screen share: Android shows its own consent dialog each time you start it. It cannot be pre-approved."
            setTextColor(0xFF9AA4B2.toInt()); setPadding(0, 40, 0, 0)
        }
        list.addView(note)
    }

    private fun row(label: String, st: CapState, action: () -> Unit) {
        val color = when (st) { CapState.ENABLED -> 0xFF00E676; CapState.NEEDS_PERMISSION -> 0xFFFFC400; else -> 0xFFFF5252 }.toInt()
        val txt = when (st) { CapState.ENABLED -> "ENABLED"; CapState.NEEDS_PERMISSION -> "NEEDS PERMISSION"; else -> "NEEDS SYSTEM ENABLEMENT" }
        val r = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 24, 0, 24) }
        r.addView(TextView(this).apply { text = label; textSize = 17f; setTextColor(0xFFFFFFFF.toInt()) })
        r.addView(TextView(this).apply { text = txt; setTextColor(color) })
        if (st != CapState.ENABLED) r.addView(Button(this).apply { text = "Open"; setOnClickListener { action() }; gravity = Gravity.START })
        list.addView(r)
    }
}
