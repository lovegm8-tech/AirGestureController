package tw.airgesture.controller.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import tw.airgesture.controller.core.PalmCircleEngine
import kotlin.math.min

/**
 * Full-display transparent camera trajectory feedback. TYPE_ACCESSIBILITY_OVERLAY
 * does not steal taps, focus, accessibility content, or alter gestures.
 * Coordinates mirror the front camera and follow the actual palm center.
 */
internal class CircleVolumeOverlay(private val context: Context) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private var view: Feedback? = null

    fun update(result: PalmCircleEngine.Result) {
        if (result.phase == PalmCircleEngine.Phase.IDLE ||
            result.phase == PalmCircleEngine.Phase.STRAIGHT) {
            hide()
            return
        }
        val target = view ?: try {
            Feedback(context).also { canvas ->
                val flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    flags, PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    title = "AirGesture palm-circle trajectory"
                }
                wm.addView(canvas, params)
                view = canvas
            }
        } catch (_: Exception) { return }
        target.update(result)
    }

    fun hide() {
        val old = view ?: return
        view = null
        try { wm.removeViewImmediate(old) } catch (_: Exception) {}
    }

    private class Feedback(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        private var result: PalmCircleEngine.Result? = null
        private val density = resources.displayMetrics.density

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            isFocusable = false
            isClickable = false
            setWillNotDraw(false)
        }

        fun update(latest: PalmCircleEngine.Result) {
            result = latest
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val reading = result ?: return
            val x = (reading.x ?: return) * width
            val y = (reading.y ?: return) * height
            val color = when (reading.phase) {
                PalmCircleEngine.Phase.VOLUME_UP -> Color.rgb(44, 233, 150)
                PalmCircleEngine.Phase.VOLUME_DOWN -> Color.rgb(255, 179, 79)
                else -> Color.rgb(66, 207, 255)
            }
            val radius = 37f * density
            // An anchored camera-following ring. It will visibly move with the
            // user's actual hand and the arc will fill as they turn.
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(105, 6, 24, 53)
            canvas.drawCircle(x, y, radius + 8f * density, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.1f * density
            paint.color = Color.argb(200, 180, 219, 252)
            canvas.drawCircle(x, y, radius, paint)

            val progress = if (reading.sweepDegrees < 240f)
                reading.sweepDegrees / 240f else 1f
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 5f * density
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = color
            val from = if (reading.direction < 0) -90f - (progress * 330f) else -90f
            val arc = if (reading.direction < 0) -progress * 330f else progress * 330f
            val rect = RectF(x - radius, y - radius, x + radius, y + radius)
            canvas.drawArc(rect, from, arc, false, paint)

            val trail = reading.trail
            if (trail.size > 1) {
                path.reset()
                path.moveTo(trail[0].x * width, trail[0].y * height)
                for (i in 1 until trail.size) {
                    path.lineTo(trail[i].x * width, trail[i].y * height)
                }
                paint.style = Paint.Style.STROKE
                paint.color = Color.argb(180, 95, 220, 255)
                paint.strokeWidth = 3.5f * density
                paint.strokeJoin = Paint.Join.ROUND
                canvas.drawPath(path, paint)
            }
            paint.style = Paint.Style.FILL
            paint.color = color
            canvas.drawCircle(x, y, 6f * density, paint)

            val text = when (reading.phase) {
                PalmCircleEngine.Phase.VOLUME_UP -> "音量增加 +1"
                PalmCircleEngine.Phase.VOLUME_DOWN -> "音量降低 -1"
                PalmCircleEngine.Phase.READY -> "開始畫圈"
                PalmCircleEngine.Phase.DRAWING ->
                    if (reading.direction > 0) "順時針 ↻"
                    else if (reading.direction < 0) "逆時針 ↺" else "畫圈辨識中"
                else -> ""
            }
            paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
            paint.textSize = 15f * density
            paint.color = Color.WHITE
            paint.textAlign = Paint.Align.CENTER
            paint.style = Paint.Style.FILL
            val textY = if (y + 67f * density < height) y + 66f * density
                else y - 56f * density
            val size = paint.measureText(text)
            val label = RectF(
                (x - size / 2f - 12f * density).coerceAtLeast(0f),
                textY - 20f * density,
                (x + size / 2f + 12f * density).coerceAtMost(width.toFloat()),
                textY + 8f * density
            )
            paint.color = Color.argb(218, 10, 28, 52)
            canvas.drawRoundRect(label, 13f * density, 13f * density, paint)
            paint.color = Color.WHITE
            canvas.drawText(text, min(width.toFloat() - size / 2f, maxOf(size / 2f, x)),
                textY, paint)
        }
    }
}
