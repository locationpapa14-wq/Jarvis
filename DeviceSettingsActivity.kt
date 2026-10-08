package com.jarvis.assistant.ui.device

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.device.BackgroundAssistantService
import com.jarvis.assistant.device.CapState
import com.jarvis.assistant.device.ConfirmPolicy

/** Device Control & Permissions settings: background mode, wake word, edge lighting, confirmation policy. */
class DeviceSettingsActivity : AppCompatActivity() {

    private val app get() = JarvisApp.instance
    private val prefs get() = app.preferences
    private lateinit var root: LinearLayout
    private var bgSwitch: SwitchCompat? = null

    private val micLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startBackground() else { bgSwitch?.isChecked = false; toast("Microphone permission is required for background mode.") }
    }
    private val notifLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val fg = 0xFFE8F1FF.toInt()
    private val accent = 0xFF1FD1C4.toInt()
    private val muted = 0xFF9AA4B2.toInt()

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        title = "Device Control"
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 60, 40, 60) }
        setContentView(ScrollView(this).apply { addView(root, ViewGroup.LayoutParams(-1, -2)); setBackgroundColor(0xFF05070D.toInt()) })
        build()
    }

    override fun onResume() { super.onResume(); bgSwitch?.isChecked = BackgroundAssistantService.running.value }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    private fun header(t: String) = root.addView(TextView(this).apply {
        text = t; setTextColor(accent); textSize = 13f; setPadding(0, 40, 0, 8) })

    private fun note(t: String) = root.addView(TextView(this).apply { text = t; setTextColor(muted); textSize = 12f })

    private fun toggle(label: String, checked: Boolean, onChange: (SwitchCompat, Boolean) -> Unit): SwitchCompat {
        val sw = SwitchCompat(this).apply { text = label; setTextColor(fg); isChecked = checked; setPadding(0, 16, 0, 16) }
        sw.setOnCheckedChangeListener { b, c -> if (b.isPressed) onChange(sw, c) }
        root.addView(sw); return sw
    }

    private fun button(label: String, onClick: () -> Unit) = root.addView(Button(this).apply {
        text = label; isAllCaps = false; setOnClickListener { onClick() } })

    private fun seek(label: String, min: Int, max: Int, value: Int, unit: String, onChange: (Int) -> Unit) {
        val t = TextView(this).apply { setTextColor(fg); text = "$label: $value$unit"; setPadding(0, 16, 0, 0) }
        val sb = SeekBar(this).apply {
            this.max = max - min; progress = value - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { t.text = "$label: ${p + min}$unit"; if (u) onChange(p + min) }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        root.addView(t); root.addView(sb)
    }

    private fun build() {
        header("BACKGROUND ASSISTANT")
        bgSwitch = toggle("Run JARVIS in background", BackgroundAssistantService.running.value) { sw, on ->
            if (on) {
                if (app.router.perms.microphone() != CapState.ENABLED) micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                else startBackground()
                if (android.os.Build.VERSION.SDK_INT >= 33 && app.router.perms.notifications() != CapState.ENABLED)
                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else BackgroundAssistantService.stop(this)
        }
        note("Shows a notification while active. Some phones stop background apps: if so, exempt JARVIS from battery optimization.")
        button("Battery optimization settings") { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }

        header("WAKE WORD")
        toggle("Listen for wake phrase", prefs.wakeWordEnabled) { _, on -> prefs.wakeWordEnabled = on }
        val et = EditText(this).apply {
            setText(prefs.wakePhrase); setTextColor(fg); setHintTextColor(muted); hint = "hey jarvis"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        root.addView(et)
        button("Save wake phrase") {
            val p = et.text.toString().trim()
            if (p.length < 3) toast("Phrase is too short.") else {
                prefs.wakePhrase = p
                com.jarvis.assistant.device.BackgroundAssistantService.wakeRef?.setPhrase(p)
                toast("Saved: $p")
            }
        }
        note("Uses Android speech recognition as a fallback detector. It is not a local always-on engine and may be limited by your phone.")

        header("EDGE LIGHTING")
        toggle("Screen-edge RGB lighting on wake", prefs.edgeLightingEnabled) { _, on -> prefs.edgeLightingEnabled = on }
        seek("Intensity", 10, 100, prefs.edgeIntensity, "%") { prefs.edgeIntensity = it }
        seek("Auto-hide after (0 = when done)", 0, 30, prefs.edgeDurationSec, "s") { prefs.edgeDurationSec = it }
        button("Test edge lighting (3 seconds)") {
            val o = app.router.overlay
            o.edgeIntensity = prefs.edgeIntensity / 100f; o.edgeDurationMs = 3000
            val r = o.startEdge()
            if (!r.ok) { toast(r.reason); r.settingsIntentAction?.let { startActivity(Intent(it, Uri.parse("package:$packageName"))) } }
        }
        note("This is a software animation over the screen. Phones do not expose their physical LEDs to apps.")

        header("CONFIRMATION POLICY")
        val rg = RadioGroup(this)
        val opts = listOf(ConfirmPolicy.ASK_ALWAYS to "Ask always", ConfirmPolicy.ASK_SENSITIVE to "Ask for sensitive actions (calls, messages, camera, screen)", ConfirmPolicy.TRUSTED to "Trusted mode (no confirmations)")
        opts.forEachIndexed { i, (p, label) ->
            rg.addView(RadioButton(this).apply { id = 100 + i; text = label; setTextColor(fg); isChecked = prefs.confirmPolicy == p.name })
        }
        rg.setOnCheckedChangeListener { _, id -> prefs.confirmPolicy = opts[id - 100].first.name; app.router.policy = opts[id - 100].first }
        root.addView(rg)

        header("SYSTEM ACCESS")
        button("Permission center") { startActivity(Intent(this, DevicePermissionsActivity::class.java)) }
        button("Accessibility settings") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        button("Display over other apps") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        button("Start screen share (test)") { app.router.requestScreenShareConsent() }
        note("Android always shows its own consent dialog for screen sharing.")
    }

    private fun startBackground() {
        BackgroundAssistantService.start(this)
        toast("Background mode starting. Say your wake phrase.")
    }
}
