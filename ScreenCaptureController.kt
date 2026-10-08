package com.jarvis.assistant.device

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream

/**
 * MediaProjection wrapper. The consent Intent from createConsentIntent() MUST be launched by an
 * Activity; its result is passed to onConsentResult(). The system dialog is never bypassed.
 */
class ScreenCaptureController(private val ctx: Context) {
    private val mpm = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    @Volatile private var latest: Bitmap? = null
    val isSharing get() = projection != null

    fun createConsentIntent(): Intent = mpm.createScreenCaptureIntent()

    /** Call from a foreground service of type mediaProjection (required on Android 10+/14). */
    fun onConsentResult(resultCode: Int, data: Intent?): DeviceActionResult {
        if (resultCode != Activity.RESULT_OK || data == null) return DeviceActionResult.failed("Screen share was not allowed.")
        stop()
        val p = mpm.getMediaProjection(resultCode, data)
        val dm = ctx.resources.displayMetrics
        val scale = 0.5f
        val w = (dm.widthPixels * scale).toInt(); val h = (dm.heightPixels * scale).toInt()
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        p.registerCallback(object : MediaProjection.Callback() { override fun onStop() { stop() } }, Handler(Looper.getMainLooper()))
        display = p.createVirtualDisplay("jarvis-capture", w, h, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null)
        r.setOnImageAvailableListener({ ir ->
            ir.acquireLatestImage()?.use { img ->
                val pl = img.planes[0]
                val rowPad = pl.rowStride - pl.pixelStride * w
                val bmp = Bitmap.createBitmap(w + rowPad / pl.pixelStride, h, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(pl.buffer)
                latest = Bitmap.createBitmap(bmp, 0, 0, w, h)
            }
        }, Handler(Looper.getMainLooper()))
        projection = p; reader = r
        return DeviceActionResult.success("Screen sharing started.")
    }

    /** Latest frame as JPEG for sending to Gemini as realtimeInput video. */
    fun latestJpeg(quality: Int = 60): ByteArray? = latest?.let {
        ByteArrayOutputStream().also { o -> it.compress(Bitmap.CompressFormat.JPEG, quality, o) }.toByteArray()
    }

    fun stop(): DeviceActionResult {
        runCatching { display?.release() }; runCatching { reader?.close() }; runCatching { projection?.stop() }
        display = null; reader = null; projection = null; latest = null
        return DeviceActionResult.success("Screen sharing stopped.")
    }
}
