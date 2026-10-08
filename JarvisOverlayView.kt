package com.jarvis.assistant.ui.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator

enum class OverlayState { IDLE, LISTENING, THINKING, SPEAKING, ACTION }

/** Full-screen, touch-transparent edge glow + small orb. Software edge lighting, not physical LEDs. */
class JarvisEdgeView(ctx: Context) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var phase = 0f
    var intensity = 0.8f
    var state = OverlayState.LISTENING
        set(v) { field = v; invalidate() }

    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2400; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val thick = when (state) { OverlayState.SPEAKING -> 26f; OverlayState.THINKING -> 18f; else -> 20f } * intensity
        paint.strokeWidth = thick
        val colors = intArrayOf(Color.CYAN, Color.MAGENTA, Color.BLUE, Color.GREEN, Color.YELLOW, Color.CYAN)
        val sg = SweepGradient(w / 2, h / 2, colors, null)
        val m = Matrix().apply { setRotate(phase * 360f, w / 2, h / 2) }
        sg.setLocalMatrix(m)
        paint.shader = sg
        paint.alpha = (255 * intensity).toInt().coerceIn(40, 255)
        paint.maskFilter = BlurMaskFilter(thick, BlurMaskFilter.Blur.NORMAL)
        c.drawRect(thick / 2, thick / 2, w - thick / 2, h - thick / 2, paint)
    }
}

/** Small draggable orb. Tap = toggle, drag = move. */
class JarvisOrbView(ctx: Context, private val onTap: () -> Unit, private val onMove: (Int, Int) -> Unit) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    var state = OverlayState.IDLE
        set(v) { field = v; invalidate() }
    private var downX = 0f; private var downY = 0f; private var moved = false
    private var startRawX = 0f; private var startRawY = 0f

    override fun onDraw(c: Canvas) {
        val col = when (state) {
            OverlayState.LISTENING -> Color.CYAN; OverlayState.THINKING -> Color.YELLOW
            OverlayState.SPEAKING -> Color.MAGENTA; OverlayState.ACTION -> Color.GREEN; else -> Color.GRAY
        }
        val r = width / 2f
        p.shader = RadialGradient(r, r, r, intArrayOf(Color.WHITE, col, Color.TRANSPARENT), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(r, r, r, p)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; startRawX = e.rawX; startRawY = e.rawY; moved = false }
            MotionEvent.ACTION_MOVE -> {
                if (Math.abs(e.rawX - startRawX) > 12 || Math.abs(e.rawY - startRawY) > 12) moved = true
                if (moved) onMove((e.rawX - downX).toInt(), (e.rawY - downY).toInt())
            }
            MotionEvent.ACTION_UP -> if (!moved) onTap()
        }
        return true
    }
}
