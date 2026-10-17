package tw.airgesture.controller.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import tw.airgesture.controller.core.PalmPositionTracker

/**
 * A transparent, visual-only palm-position marker. It never reads view content,
 * intercepts touches, or requests a gesture or volume adjustment.
 */
internal class PalmPositionOverlay(private val context: Context) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var overlay: PalmMarker? = null

    fun update(snapshot: PalmPositionTracker.Snapshot?) {
        if (snapshot == null) {
            hide()
            return
        }
        val marker = overlay ?: try {
            PalmMarker(context).also { candidate ->
                val layout = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    title = "AirGesture palm-position visual feedback"
                }
                windowManager.addView(candidate, layout)
                overlay = candidate
            }
        } catch (_: Exception) { return }
        marker.update(snapshot)
    }

    fun hide() {
        val existing = overlay ?: return
        overlay = null
        try { windowManager.removeViewImmediate(existing) } catch (_: Exception) {}
    }

    private class PalmMarker(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val trailPath = Path()
        private val density = resources.displayMetrics.density
        private var current: PalmPositionTracker.Snapshot? = null

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            isFocusable = false
            isClickable = false
        }

        fun update(snapshot: PalmPositionTracker.Snapshot) {
            current = snapshot
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val reading = current ?: return
            val centerX = reading.x * width
            val centerY = reading.y * height
            val radius = 34f * density
            val cyan = Color.rgb(60, 218, 255)

            val trail = reading.trail
            if (trail.size >= 2) {
                trailPath.reset()
                trailPath.moveTo(trail[0].x * width, trail[0].y * height)
                for (i in 1 until trail.size) {
                    trailPath.lineTo(trail[i].x * width, trail[i].y * height)
                }
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 3.1f * density
                paint.strokeJoin = Paint.Join.ROUND
                paint.strokeCap = Paint.Cap.ROUND
                paint.color = Color.argb(160, 100, 222, 255)
                canvas.drawPath(trailPath, paint)
            }

            paint.style = Paint.Style.FILL
            paint.color = Color.argb(60, 12, 44, 94)
            canvas.drawCircle(centerX, centerY, radius + 8f * density, paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f * density
            paint.color = cyan
            canvas.drawCircle(centerX, centerY, radius, paint)
            paint.strokeWidth = 1.1f * density
            paint.color = Color.argb(160, 160, 237, 255)
            canvas.drawCircle(centerX, centerY, radius - 9f * density, paint)

            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            canvas.drawCircle(centerX, centerY, 4.5f * density, paint)
        }
    }
}
