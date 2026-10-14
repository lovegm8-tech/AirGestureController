package tw.airgesture.controller.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlin.math.roundToInt

/**
 * Visual-only cursor, deliberately not touchable or focusable.
 * Uses the already authorized accessibility overlay: no draw-over-other-apps permission.
 */
internal class PointerOverlay(private val context: Context) {
    private val window = context.getSystemService(WindowManager::class.java)
    private val size = dp(60)
    private val screen = Point()
    private val cursor = CursorView(context)
    private val params = WindowManager.LayoutParams(
        size, size, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        title = "AirGesture finger cursor"
    }
    private var showing = false

    @Suppress("DEPRECATION")
    fun update(normX: Float, normY: Float, progress: Float, clicked: Boolean): Pair<Float, Float>? {
        if (normX !in 0f..1f || normY !in 0f..1f) return null
        window.defaultDisplay.getRealSize(screen)
        if (screen.x < 1 || screen.y < 1) return null
        val x = (normX.coerceIn(.035f, .965f) * screen.x)
        val y = (normY.coerceIn(.055f, .945f) * screen.y)
        params.x = (x - size * .5f).roundToInt()
        params.y = (y - size * .5f).roundToInt()
        cursor.update(progress, clicked)
        try {
            if (!showing) {
                window.addView(cursor, params)
                showing = true
            } else window.updateViewLayout(cursor, params)
        } catch (_: Exception) {
            showing = false
            return null
        }
        return x to y
    }

    fun hide() {
        if (!showing) return
        try { window.removeViewImmediate(cursor) } catch (_: Exception) {}
        showing = false
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density + .5f).toInt()

    private class CursorView(context: Context) : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private var progress = 0f
        private var clickFlashUntil = 0L

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        fun update(progress: Float, clicked: Boolean) {
            this.progress = progress.coerceIn(0f, 1f)
            if (clicked) clickFlashUntil = SystemClock.uptimeMillis() + 550L
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width * .5f
            val cy = height * .5f
            val r = minOf(width, height) * .19f
            val flashing = SystemClock.uptimeMillis() < clickFlashUntil

            p.style = Paint.Style.FILL
            p.color = if (flashing) 0xff2563eb.toInt() else 0xff14b8a6.toInt()
            canvas.drawCircle(cx, cy, r, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = width * .055f
            p.color = Color.WHITE
            canvas.drawCircle(cx, cy, r, p)
            p.strokeWidth = width * .065f
            p.color = 0xffffb703.toInt()
            canvas.drawArc(
                cx - width * .29f, cy - height * .29f,
                cx + width * .29f, cy + height * .29f,
                -90f, 360f * progress, false, p
            )
        }
    }
}
