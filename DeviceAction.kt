package com.jarvis.assistant.device

/** Typed actions Gemini may request. Nothing else can ever be executed. */
sealed class DeviceAction(val sensitive: Boolean = false) {
    data class CallContact(val name: String) : DeviceAction(true)
    data class CallNumber(val number: String) : DeviceAction(true)
    data class OpenApp(val appName: String) : DeviceAction()
    data class SendWhatsApp(val recipient: String, val message: String) : DeviceAction(true)
    data class Tap(val x: Float, val y: Float) : DeviceAction()
    data class Click(val target: String) : DeviceAction()
    data class LongPress(val target: String) : DeviceAction()
    data class Swipe(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val durationMs: Long = 300) : DeviceAction()
    data class Scroll(val forward: Boolean) : DeviceAction()
    object Back : DeviceAction()
    object Home : DeviceAction()
    object Recents : DeviceAction()
    object FocusTextField : DeviceAction()
    data class TypeText(val text: String) : DeviceAction()
    object Screenshot : DeviceAction(true)
    object CameraFront : DeviceAction(true)
    object CameraBack : DeviceAction(true)
    object CameraCapture : DeviceAction(true)
    object CameraStop : DeviceAction()
    object StartScreenShare : DeviceAction(true)
    object StopScreenShare : DeviceAction()
    object OpenSettings : DeviceAction()
    object StartBackgroundMode : DeviceAction()
    object StopBackgroundMode : DeviceAction()
    object ShowOverlay : DeviceAction()
    object HideOverlay : DeviceAction()
    object StartEdgeLighting : DeviceAction()
    object StopEdgeLighting : DeviceAction()

    companion object {
        /** Parses a Gemini function-call (name + args) into a typed action, or null if unknown. */
        fun fromCall(name: String, args: Map<String, Any?>): DeviceAction? {
            fun s(k: String) = args[k]?.toString()?.trim().orEmpty()
            fun f(k: String) = (args[k] as? Number)?.toFloat() ?: s(k).toFloatOrNull() ?: 0f
            return when (name) {
                "ACTION_CALL_CONTACT" -> CallContact(s("name"))
                "ACTION_CALL_NUMBER" -> CallNumber(s("number"))
                "ACTION_OPEN_APP" -> OpenApp(s("app"))
                "ACTION_SEND_WHATSAPP_MESSAGE" -> SendWhatsApp(s("recipient"), s("message"))
                "ACTION_TAP" -> Tap(f("x"), f("y"))
                "ACTION_CLICK" -> Click(s("target"))
                "ACTION_LONG_PRESS" -> LongPress(s("target"))
                "ACTION_SWIPE" -> Swipe(f("x1"), f("y1"), f("x2"), f("y2"))
                "ACTION_SCROLL" -> Scroll(s("direction") != "backward")
                "ACTION_BACK" -> Back
                "ACTION_HOME" -> Home
                "ACTION_RECENTS" -> Recents
                "ACTION_FOCUS_TEXT_FIELD" -> FocusTextField
                "ACTION_TYPE_TEXT" -> TypeText(s("text"))
                "ACTION_SCREENSHOT" -> Screenshot
                "ACTION_CAMERA_FRONT" -> CameraFront
                "ACTION_CAMERA_BACK" -> CameraBack
                "ACTION_CAMERA_CAPTURE" -> CameraCapture
                "ACTION_CAMERA_STOP" -> CameraStop
                "ACTION_START_SCREEN_SHARE" -> StartScreenShare
                "ACTION_STOP_SCREEN_SHARE" -> StopScreenShare
                "ACTION_OPEN_SETTINGS" -> OpenSettings
                "ACTION_START_BACKGROUND_MODE" -> StartBackgroundMode
                "ACTION_STOP_BACKGROUND_MODE" -> StopBackgroundMode
                "ACTION_SHOW_OVERLAY" -> ShowOverlay
                "ACTION_HIDE_OVERLAY" -> HideOverlay
                "ACTION_START_EDGE_LIGHTING" -> StartEdgeLighting
                "ACTION_STOP_EDGE_LIGHTING" -> StopEdgeLighting
                else -> null
            }
        }
    }
}
