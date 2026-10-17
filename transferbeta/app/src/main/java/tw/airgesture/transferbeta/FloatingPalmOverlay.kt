package tw.airgesture.transferbeta

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Point
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import tw.airgesture.transferbeta.core.PalmPositionTracker
import kotlin.math.roundToInt

/**
 * Optional visual-only marker, updated at most 4 times/second by the camera
 * service. A small movable overlay replaces the expensive full-screen redraw.
 */
internal class FloatingPalmOverlay(private val context: Context) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density + .5f).toInt()
    private val size = dp(230)
    private val screen = Point()
    private var marker: PalmMarker? = null
    private val layout = WindowManager.LayoutParams(
        size, size,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        title = "Floating Control hand marker"
    }

    @Suppress("DEPRECATION")
    fun update(snapshot: PalmPositionTracker.Snapshot?) {
        if (snapshot == null) {
            hide()
            return
        }
        wm.defaultDisplay.getRealSize(screen)
        if (screen.x <= 0 || screen.y <= 0) return
        val x = snapshot.x * screen.x
        val y = snapshot.y * screen.y
        layout.x = (x - size / 2f).roundToInt()
            .coerceIn(0, (screen.x - size).coerceAtLeast(0))
        layout.y = (y - size / 2f).roundToInt()
            .coerceIn(0, (screen.y - size).coerceAtLeast(0))
        val current = marker ?: PalmMarker(context).also { marker = it }
        current.update(snapshot, screen.x.toFloat(), screen.y.toFloat(),
            layout.x.toFloat(), layout.y.toFloat())
        try {
            if (current.parent == null) wm.addView(current, layout)
            else wm.updateViewLayout(current, layout)
        } catch (_: Exception) {
            // A failed overlay must never interfere with the gesture stream.
            hide()
        }
    }

    fun hide() {
        val current = marker ?: return
        marker = null
        if (current.parent != null) {
            try { wm.removeViewImmediate(current) } catch (_: Exception) {}
        }
    }

    private class PalmMarker(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        private val density = resources.displayMetrics.density
        private var reading: PalmPositionTracker.Snapshot? = null
        private var screenW = 1f
        private var screenH = 1f
        private var offsetX = 0f
        private var offsetY = 0f

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            isFocusable = false
            isClickable = false
        }

        fun update(
            snapshot: PalmPositionTracker.Snapshot,
            displayWidth: Float, displayHeight: Float,
            left: Float, top: Float
        ) {
            reading = snapshot
            screenW = displayWidth
            screenH = displayHeight
            offsetX = left
            offsetY = top
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val state = reading ?: return
            val x = state.x * screenW - offsetX
            val y = state.y * screenH - offsetY
            val radius = 34f * density
            if (state.trail.size >= 2) {
                path.reset()
                path.moveTo(state.trail.first().x * screenW - offsetX,
                    state.trail.first().y * screenH - offsetY)
                for (i in 1 until state.trail.size) {
                    val pt = state.trail[i]
                    path.lineTo(pt.x * screenW - offsetX, pt.y * screenH - offsetY)
                }
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 3f * density
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeJoin = Paint.Join.ROUND
                paint.color = Color.argb(150, 90, 220, 255)
                canvas.drawPath(path, paint)
            }
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(50, 12, 44, 94)
            canvas.drawCircle(x, y, radius + 8f * density, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f * density
            paint.color = Color.rgb(60, 218, 255)
            canvas.drawCircle(x, y, radius, paint)
            paint.strokeWidth = 1.1f * density
            paint.color = Color.argb(160, 160, 237, 255)
            canvas.drawCircle(x, y, radius - 9f * density, paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            canvas.drawCircle(x, y, 4.5f * density, paint)
        }
    }
}
