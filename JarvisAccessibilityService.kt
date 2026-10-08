package com.jarvis.assistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.jarvis.assistant.device.DeviceActionResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: JarvisAccessibilityService? = null
        val isConnected get() = instance != null
    }

    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onUnbind(intent: android.content.Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onDestroy() { instance = null; super.onDestroy() }

    // ---------- global actions ----------
    fun back() = global(GLOBAL_ACTION_BACK, "Back")
    fun home() = global(GLOBAL_ACTION_HOME, "Home")
    fun recents() = global(GLOBAL_ACTION_RECENTS, "Recents")
    fun screenshot(): DeviceActionResult =
        if (android.os.Build.VERSION.SDK_INT >= 28) global(GLOBAL_ACTION_TAKE_SCREENSHOT, "Screenshot")
        else DeviceActionResult.unsupported("Screenshot via accessibility needs Android 9 or newer.")

    private fun global(a: Int, label: String) =
        if (performGlobalAction(a)) DeviceActionResult.success("$label done.")
        else DeviceActionResult.failed("$label could not be performed.")

    // ---------- node search ----------
    private fun root(): AccessibilityNodeInfo? = rootInActiveWindow

    private fun findByText(text: String): AccessibilityNodeInfo? {
        val r = root() ?: return null
        val q = text.trim()
        r.findAccessibilityNodeInfosByText(q).firstOrNull { it.isVisibleToUser }?.let { return it }
        return walk(r) { n -> n.isVisibleToUser &&
            (n.contentDescription?.toString()?.contains(q, true) == true ||
             n.viewIdResourceName?.contains(q, true) == true) }
    }

    private fun walk(n: AccessibilityNodeInfo?, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (n == null) return null
        if (pred(n)) return n
        for (i in 0 until n.childCount) walk(n.getChild(i), pred)?.let { return it }
        return null
    }

    private fun clickableAncestor(n: AccessibilityNodeInfo?, long: Boolean): AccessibilityNodeInfo? {
        var c = n
        while (c != null) {
            if (if (long) c.isLongClickable else c.isClickable) return c
            c = c.parent
        }
        return null
    }

    fun click(target: String): DeviceActionResult {
        val node = findByText(target) ?: return DeviceActionResult.notFound("I cannot see \"$target\" on the screen.")
        val c = clickableAncestor(node, false)
        if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return DeviceActionResult.success("Clicked $target.")
        val r = Rect(); node.getBoundsInScreen(r)
        return if (r.width() > 0) tap(r.exactCenterX(), r.exactCenterY()) else DeviceActionResult.failed("Could not click $target.")
    }

    fun longPress(target: String): DeviceActionResult {
        val node = findByText(target) ?: return DeviceActionResult.notFound("I cannot see \"$target\" on the screen.")
        val c = clickableAncestor(node, true)
        return if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) DeviceActionResult.success("Long-pressed $target.")
        else DeviceActionResult.failed("$target does not support long press.")
    }

    // ---------- gestures (coordinate fallback) ----------
    private fun gesture(path: Path, duration: Long): DeviceActionResult {
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration.coerceAtLeast(1))).build()
        return if (dispatchGesture(g, null, null)) DeviceActionResult.success("Gesture sent.")
        else DeviceActionResult.failed("Gesture was rejected by the system.")
    }

    fun tap(x: Float, y: Float) = gesture(Path().apply { moveTo(x, y) }, 50)
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long) =
        gesture(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, ms)

    fun scroll(forward: Boolean): DeviceActionResult {
        val r = root() ?: return DeviceActionResult.failed("No active window.")
        val scrollable = walk(r) { it.isScrollable }
        val act = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return if (scrollable?.performAction(act) == true) DeviceActionResult.success(if (forward) "Scrolled down." else "Scrolled up.")
        else DeviceActionResult.failed("Nothing scrollable on this screen.")
    }

    // ---------- text ----------
    fun focusTextField(): DeviceActionResult {
        val r = root() ?: return DeviceActionResult.failed("No active window.")
        val f = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: walk(r) { it.isEditable && it.isVisibleToUser }
            ?: return DeviceActionResult.notFound("No text field is visible.")
        f.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        f.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        return DeviceActionResult.success("Text field focused.")
    }

    fun typeText(text: String): DeviceActionResult {
        val r = root() ?: return DeviceActionResult.failed("No active window.")
        val f = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
            ?: walk(r) { it.isEditable && it.isVisibleToUser }
            ?: return DeviceActionResult.notFound("No text field to type into.")
        if (f.isPassword) return DeviceActionResult.unsupported("I will not type into password fields.")
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return if (f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) DeviceActionResult.success("Typed the text.")
        else DeviceActionResult.failed("The field rejected the text.")
    }

    // ---------- WhatsApp ----------
    /** Types the message in the open chat. Does not send. */
    suspend fun whatsappCompose(message: String): DeviceActionResult {
        repeat(20) {
            val r = root()
            val field = r?.findAccessibilityNodeInfosByViewId("com.whatsapp:id/entry")?.firstOrNull()
                ?: r?.let { n -> walk(n) { it.isEditable && it.packageName?.toString()?.startsWith("com.whatsapp") == true } }
            if (field != null) {
                val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message) }
                return if (field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
                    DeviceActionResult.success("Message typed.") else DeviceActionResult.failed("WhatsApp did not accept the text.")
            }
            delay(400)
        }
        return DeviceActionResult.failed("I could not find the WhatsApp message box. The chat screen may not have loaded.")
    }

    /** Taps Send, then verifies that the composer was cleared. */
    suspend fun whatsappSendAndVerify(message: String): DeviceActionResult {
        val r = root() ?: return DeviceActionResult.failed("No active window.")
        val send = r.findAccessibilityNodeInfosByViewId("com.whatsapp:id/send").firstOrNull()
            ?: walk(r) { it.contentDescription?.toString()?.equals("Send", true) == true }
            ?: return DeviceActionResult.notFound("I cannot find WhatsApp's Send button.")
        if (clickableAncestor(send, false)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) != true)
            return DeviceActionResult.failed("Pressing Send failed.")
        delay(900)
        val after = root()
        val field = after?.findAccessibilityNodeInfosByViewId("com.whatsapp:id/entry")?.firstOrNull()
        val stillThere = field?.text?.toString()?.trim() == message.trim()
        return if (!stillThere) DeviceActionResult.success("Message sent.")
        else DeviceActionResult.failed("The message is still in the box, so I cannot confirm it was sent.")
    }
}
