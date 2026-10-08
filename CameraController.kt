package com.jarvis.assistant.device

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import java.io.File

/** Camera2 wrapper. Never starts on its own: only on an explicit action, after CAMERA permission. */
class CameraController(private val ctx: Context, private val perms: PermissionManager) {
    private val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    var facing = CameraCharacteristics.LENS_FACING_FRONT; private set
    val isOpen get() = device != null
    var lastCapture: File? = null; private set

    private fun cameraId(f: Int) = mgr.cameraIdList.firstOrNull {
        mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == f
    }

    @SuppressLint("MissingPermission")
    fun start(front: Boolean, onResult: (DeviceActionResult) -> Unit) {
        if (perms.camera() != CapState.ENABLED)
            return onResult(DeviceActionResult.needsPermission("Camera permission is required.", android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS))
        stop()
        val want = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        val id = cameraId(want) ?: return onResult(DeviceActionResult.notFound("This phone has no ${if (front) "front" else "rear"} camera."))
        facing = want
        thread = HandlerThread("jarvis-cam").also { it.start() }
        handler = Handler(thread!!.looper)
        reader = ImageReader.newInstance(1280, 720, ImageFormat.JPEG, 2)
        try {
            mgr.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    device = cam
                    @Suppress("DEPRECATION")
                    cam.createCaptureSession(listOf(reader!!.surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) { session = s
                            onResult(DeviceActionResult.success("${if (front) "Front" else "Rear"} camera started.")) }
                        override fun onConfigureFailed(s: CameraCaptureSession) { stop(); onResult(DeviceActionResult.failed("Camera session failed.")) }
                    }, handler)
                }
                override fun onDisconnected(cam: CameraDevice) { stop() }
                override fun onError(cam: CameraDevice, e: Int) { stop(); onResult(DeviceActionResult.failed("Camera error $e.")) }
            }, handler)
        } catch (e: Exception) { stop(); onResult(DeviceActionResult.failed("Could not open camera: ${e.message}")) }
    }

    fun capture(onResult: (DeviceActionResult) -> Unit) {
        val cam = device; val s = session; val r = reader
        if (cam == null || s == null || r == null) return onResult(DeviceActionResult.failed("Camera is not running. Start the front or rear camera first."))
        r.setOnImageAvailableListener({ ir ->
            ir.acquireLatestImage()?.use { img ->
                val buf = img.planes[0].buffer; val bytes = ByteArray(buf.remaining()); buf.get(bytes)
                val f = File(ctx.getExternalFilesDir("captures"), "jarvis_${System.currentTimeMillis()}.jpg")
                f.writeBytes(bytes); lastCapture = f
                onResult(DeviceActionResult.success("Photo saved: ${f.name}"))
            }
        }, handler)
        try {
            val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply { addTarget(r.surface) }
            s.capture(req.build(), null, handler)
        } catch (e: Exception) { onResult(DeviceActionResult.failed("Capture failed: ${e.message}")) }
    }

    fun stop() {
        runCatching { session?.close() }; runCatching { device?.close() }; runCatching { reader?.close() }
        runCatching { thread?.quitSafely() }
        session = null; device = null; reader = null; thread = null; handler = null
    }
}
