package com.jarvis.assistant.device

import org.json.JSONArray
import org.json.JSONObject

/**
 * Add the result of toolsJson() to the Gemini Live `setup` message:  "tools": [ { "functionDeclarations": [...] } ]
 * Then, on `toolCall` messages from the server, parse each functionCall with DeviceAction.fromCall(name, args),
 * run router.execute(), and reply with a `toolResponse` containing DeviceActionResult.status + reason.
 */
object GeminiToolDeclarations {
    private fun fn(name: String, desc: String, vararg props: Pair<String, String>): JSONObject {
        val o = JSONObject().put("name", name).put("description", desc)
        if (props.isNotEmpty()) {
            val p = JSONObject(); props.forEach { p.put(it.first, JSONObject().put("type", it.second)) }
            o.put("parameters", JSONObject().put("type", "OBJECT").put("properties", p)
                .put("required", JSONArray(props.map { it.first })))
        }
        return o
    }

    fun toolsJson(): JSONArray {
        val d = JSONArray()
        d.put(fn("ACTION_OPEN_APP", "Open an installed app by name", "app" to "STRING"))
        d.put(fn("ACTION_CALL_CONTACT", "Call a saved contact", "name" to "STRING"))
        d.put(fn("ACTION_CALL_NUMBER", "Call a phone number", "number" to "STRING"))
        d.put(fn("ACTION_SEND_WHATSAPP_MESSAGE", "Send a WhatsApp message", "recipient" to "STRING", "message" to "STRING"))
        d.put(fn("ACTION_CLICK", "Click a visible on-screen element by its text", "target" to "STRING"))
        d.put(fn("ACTION_LONG_PRESS", "Long-press a visible element", "target" to "STRING"))
        d.put(fn("ACTION_TAP", "Tap screen coordinates", "x" to "NUMBER", "y" to "NUMBER"))
        d.put(fn("ACTION_SWIPE", "Swipe between coordinates", "x1" to "NUMBER", "y1" to "NUMBER", "x2" to "NUMBER", "y2" to "NUMBER"))
        d.put(fn("ACTION_SCROLL", "Scroll the screen; direction forward or backward", "direction" to "STRING"))
        d.put(fn("ACTION_TYPE_TEXT", "Type text in the focused field", "text" to "STRING"))
        d.put(fn("CONFIRM_PENDING_ACTION", "Call this after the user answers yes or no to a confirmation question about a pending action", "confirmed" to "BOOLEAN"))
        listOf("ACTION_BACK", "ACTION_HOME", "ACTION_RECENTS", "ACTION_FOCUS_TEXT_FIELD", "ACTION_SCREENSHOT",
            "ACTION_CAMERA_FRONT", "ACTION_CAMERA_BACK", "ACTION_CAMERA_CAPTURE", "ACTION_CAMERA_STOP",
            "ACTION_START_SCREEN_SHARE", "ACTION_STOP_SCREEN_SHARE", "ACTION_OPEN_SETTINGS",
            "ACTION_START_BACKGROUND_MODE", "ACTION_STOP_BACKGROUND_MODE", "ACTION_SHOW_OVERLAY", "ACTION_HIDE_OVERLAY",
            "ACTION_START_EDGE_LIGHTING", "ACTION_STOP_EDGE_LIGHTING"
        ).forEach { d.put(fn(it, it.removePrefix("ACTION_").lowercase().replace('_', ' '))) }
        return JSONArray().put(JSONObject().put("functionDeclarations", d))
    }

    fun toolResponse(callId: String, name: String, r: DeviceActionResult): JSONObject =
        JSONObject().put("toolResponse", JSONObject().put("functionResponses", JSONArray().put(
            JSONObject().put("id", callId).put("name", name)
                .put("response", JSONObject().put("status", r.status.name).put("reason", r.reason)))))
}
