package com.jarvis.assistant.device

import android.content.Context
import android.provider.Settings
import com.jarvis.assistant.accessibility.JarvisAccessibilityService
import kotlinx.coroutines.delay

enum class ConfirmPolicy { ASK_ALWAYS, ASK_SENSITIVE, TRUSTED }

/** The ONLY path from Gemini to the OS. Validates, checks capability, applies confirmation, executes. */
class DeviceCommandRouter(
    private val ctx: Context,
    val perms: PermissionManager = PermissionManager(ctx),
    val overlay: OverlayController = OverlayController(ctx),
    val camera: CameraController = CameraController(ctx, perms),
    val screen: ScreenCaptureController = ScreenCaptureController(ctx),
    private val launcher: AppLauncher = AppLauncher(ctx),
    private val contacts: ContactResolver = ContactResolver(ctx),
    private val calls: CallController = CallController(ctx, perms)
) {
    var policy: ConfirmPolicy = ConfirmPolicy.ASK_SENSITIVE
    /** Provided by the service/activity layer. */
    var requestScreenShareConsent: () -> Unit = {}
    var startBackground: () -> DeviceActionResult = { DeviceActionResult.unsupported("Background service not bound.") }
    var stopBackground: () -> DeviceActionResult = { DeviceActionResult.unsupported("Background service not bound.") }

    private var pending: DeviceAction? = null

    private fun needsConfirm(a: DeviceAction) = when (policy) {
        ConfirmPolicy.ASK_ALWAYS -> a !is DeviceAction.Back && a !is DeviceAction.Home && a !is DeviceAction.Recents
        ConfirmPolicy.ASK_SENSITIVE -> a.sensitive
        ConfirmPolicy.TRUSTED -> false
    }

    /** User said yes/no to a pending confirmation. */
    suspend fun confirm(yes: Boolean): DeviceActionResult {
        val a = pending ?: return DeviceActionResult.failed("Nothing is waiting for confirmation.")
        pending = null
        return if (yes) run(a) else DeviceActionResult.failed("Cancelled.")
    }

    suspend fun execute(a: DeviceAction): DeviceActionResult {
        if (needsConfirm(a)) { pending = a; return DeviceActionResult.confirm("Please confirm: ${describe(a)}") }
        return run(a)
    }

    private fun describe(a: DeviceAction) = when (a) {
        is DeviceAction.CallContact -> "call ${a.name}"
        is DeviceAction.CallNumber -> "call ${a.number}"
        is DeviceAction.SendWhatsApp -> "send \"${a.message}\" to ${a.recipient} on WhatsApp"
        DeviceAction.CameraFront -> "start the front camera"
        DeviceAction.CameraBack -> "start the rear camera"
        DeviceAction.CameraCapture -> "take a photo"
        DeviceAction.StartScreenShare -> "start screen sharing"
        DeviceAction.Screenshot -> "take a screenshot"
        else -> a::class.java.simpleName
    }

    private fun acc(): JarvisAccessibilityService? = JarvisAccessibilityService.instance
    private val noAcc get() = DeviceActionResult.needsEnablement(
        "Please enable JARVIS in Accessibility settings.", Settings.ACTION_ACCESSIBILITY_SETTINGS)

    private suspend fun run(a: DeviceAction): DeviceActionResult = when (a) {
        is DeviceAction.OpenApp -> launcher.open(a.appName).let {
            if (it.status == ResultStatus.NOT_FOUND) it else it }
        DeviceAction.OpenSettings -> launcher.openSettings()
        is DeviceAction.CallNumber -> calls.call(a.number)
        is DeviceAction.CallContact -> callContact(a.name)
        is DeviceAction.SendWhatsApp -> whatsapp(a)
        is DeviceAction.Tap -> acc()?.tap(a.x, a.y) ?: noAcc
        is DeviceAction.Click -> acc()?.click(a.target) ?: noAcc
        is DeviceAction.LongPress -> acc()?.longPress(a.target) ?: noAcc
        is DeviceAction.Swipe -> acc()?.swipe(a.x1, a.y1, a.x2, a.y2, a.durationMs) ?: noAcc
        is DeviceAction.Scroll -> acc()?.scroll(a.forward) ?: noAcc
        DeviceAction.Back -> acc()?.back() ?: noAcc
        DeviceAction.Home -> acc()?.home() ?: noAcc
        DeviceAction.Recents -> acc()?.recents() ?: noAcc
        DeviceAction.FocusTextField -> acc()?.focusTextField() ?: noAcc
        is DeviceAction.TypeText -> acc()?.typeText(a.text) ?: noAcc
        DeviceAction.Screenshot -> acc()?.screenshot() ?: noAcc
        DeviceAction.CameraFront -> cam(true)
        DeviceAction.CameraBack -> cam(false)
        DeviceAction.CameraCapture -> camCapture()
        DeviceAction.CameraStop -> { camera.stop(); DeviceActionResult.success("Camera stopped.") }
        DeviceAction.StartScreenShare -> { requestScreenShareConsent()
            DeviceActionResult.success("The system will now ask for your permission to share the screen.") }
        DeviceAction.StopScreenShare -> screen.stop()
        DeviceAction.StartBackgroundMode -> startBackground()
        DeviceAction.StopBackgroundMode -> stopBackground()
        DeviceAction.ShowOverlay -> overlay.showOrb()
        DeviceAction.HideOverlay -> overlay.hideOrb()
        DeviceAction.StartEdgeLighting -> overlay.startEdge()
        DeviceAction.StopEdgeLighting -> overlay.stopEdge()
    }

    private suspend fun cam(front: Boolean): DeviceActionResult {
        val d = kotlinx.coroutines.CompletableDeferred<DeviceActionResult>()
        camera.start(front) { d.complete(it) }
        return d.await()
    }

    private suspend fun camCapture(): DeviceActionResult {
        val d = kotlinx.coroutines.CompletableDeferred<DeviceActionResult>()
        camera.capture { d.complete(it) }
        return d.await()
    }

    private suspend fun callContact(name: String): DeviceActionResult {
        if (perms.contacts() != CapState.ENABLED)
            return DeviceActionResult.needsPermission("Contacts permission is needed to find $name.", Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        return when (val r = contacts.resolve(name)) {
            ContactLookup.None -> DeviceActionResult.notFound("I could not find $name in your contacts.")
            is ContactLookup.Multiple -> DeviceActionResult.ambiguous("Which one: " + r.matches.take(4).joinToString(" or ") { it.name } + "?")
            is ContactLookup.Unique -> calls.call(r.match.number)
        }
    }

    private suspend fun whatsapp(a: DeviceAction.SendWhatsApp): DeviceActionResult {
        if (a.message.isBlank()) return DeviceActionResult.failed("The message is empty.")
        if (perms.contacts() != CapState.ENABLED)
            return DeviceActionResult.needsPermission("Contacts permission is needed to find ${a.recipient}.", Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        val number = when (val r = contacts.resolve(a.recipient)) {
            ContactLookup.None -> return DeviceActionResult.notFound("I could not find ${a.recipient} in your contacts.")
            is ContactLookup.Multiple -> return DeviceActionResult.ambiguous("Which one: " + r.matches.take(4).joinToString(" or ") { it.name } + "?")
            is ContactLookup.Unique -> r.match.number
        }
        val opened = launcher.openWhatsAppChat(number)
        if (!opened.ok) return opened
        val service = acc() ?: return DeviceActionResult.needsEnablement(
            "WhatsApp is open on the chat, but I need Accessibility enabled to type and send.", Settings.ACTION_ACCESSIBILITY_SETTINGS)
        delay(1200)
        val typed = service.whatsappCompose(a.message)
        if (!typed.ok) return typed
        return service.whatsappSendAndVerify(a.message)
    }
}
