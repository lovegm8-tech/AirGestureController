package tw.airgesture.transferbeta

import android.animation.ValueAnimator
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
import android.view.animation.LinearInterpolator
import tw.airgesture.transferbeta.core.PalmPositionTracker
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Lightweight optional palm overlay:
 * - a 230 dp surface instead of full-screen repainting
 * - translate the window only if the hand nears the edge of the surface
 * - interpolate the ring position within the surface between ML samples
 * - never increases background/camera FPS on its own
 */
internal class FloatingPalmOverlay(private val context: Context) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density + .5f).toInt()
    private val size = dp(230)
    private val safeMargin = dp(55)
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
        title = "Floating Control palm visualization"
    }

    @Suppress("DEPRECATION")
    fun update(snapshot: PalmPositionTracker.Snapshot?) {
        if (snapshot == null) {
            hide()
            return
        }
        wm.defaultDisplay.getRealSize(screen)
        if (screen.x <= 0 || screen.y <= 0) return
        val targetX = snapshot.x * screen.x
        val targetY = snapshot.y * screen.y
        val markerView = marker ?: PalmMarker(context).also { marker = it }
        val wasAdded = markerView.parent != null
        // WindowManager moves are much more expensive than a tiny view's
        // invalidate(). Recenter only when the dot would reach a window edge.
        val outside = !wasAdded ||
            targetX < layout.x + safeMargin ||
            targetX > layout.x + size - safeMargin ||
            targetY < layout.y + safeMargin ||
            targetY > layout.y + size - safeMargin
        val originalX = layout.x
        val originalY = layout.y
        if (outside) {
            layout.x = (targetX - size * .5f).roundToInt()
                .coerceIn(0, (screen.x - size).coerceAtLeast(0))
            layout.y = (targetY - size * .5f).roundToInt()
                .coerceIn(0, (screen.y - size).coerceAtLeast(0))
        }
        try {
            if (!wasAdded) wm.addView(markerView, layout)
            else if (layout.x != originalX || layout.y != originalY)
                wm.updateViewLayout(markerView, layout)
            markerView.update(snapshot, screen.x.toFloat(), screen.y.toFloat(),
                layout.x.toFloat(), layout.y.toFloat())
        } catch (_: Exception) {
            // Visual feedback must never interrupt click/swipe or file transfer.
            hide()
        }
    }

    fun hide() {
        val current = marker ?: return
        marker = null
        current.release()
        if (current.parent != null) {
            try { wm.removeViewImmediate(current) } catch (_: Exception) {}
        }
    }

    private class PalmMarker(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val trailPath = Path()
        private val density = resources.displayMetrics.density
        private var reading: PalmPositionTracker.Snapshot? = null
        private var screenW = 1f
        private var screenH = 1f
        private var offsetX = 0f
        private var offsetY = 0f
        private var shownX = Float.NaN
        private var shownY = Float.NaN
        private var movement: ValueAnimator? = null
        private var lastTargetAt = 0L

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
            val toX = snapshot.x * displayWidth
            val toY = snapshot.y * displayHeight
            val fromX = shownX
            val fromY = shownY
            movement?.cancel()
            if (!fromX.isFinite() || !fromY.isFinite() ||
                (abs(toX - fromX) < density && abs(toY - fromY) < density)) {
                shownX = toX
                shownY = toY
                invalidate()
                return
            }
            // Small-surface animation fills the gap between camera/MediaPipe
            // updates; avoid high-frequency WindowManager layout transactions.
            val now = android.os.SystemClock.uptimeMillis()
            val interval = if (lastTargetAt == 0L) 115L
                else (now - lastTargetAt).coerceIn(80L, 160L)
            lastTargetAt = now
            movement = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = interval
                interpolator = LinearInterpolator()
                addUpdateListener { animator ->
                    val t = animator.animatedFraction
                    shownX = fromX + (toX - fromX) * t
                    shownY = fromY + (toY - fromY) * t
                    invalidate()
                }
                start()
            }
        }

        fun release() {
            movement?.cancel()
            movement = null
            reading = null
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val snap = reading ?: return
            if (!shownX.isFinite() || !shownY.isFinite()) return
            val centerX = shownX - offsetX
            val centerY = shownY - offsetY
            val radius = 22f * density

            if (snap.trail.size >= 2) {
                trailPath.reset()
                trailPath.moveTo(snap.trail.first().x * screenW - offsetX,
                    snap.trail.first().y * screenH - offsetY)
                for (i in 1 until snap.trail.size) {
                    val pt = snap.trail[i]
                    trailPath.lineTo(pt.x * screenW - offsetX,
                        pt.y * screenH - offsetY)
                }
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2.3f * density
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeJoin = Paint.Join.ROUND
                paint.color = Color.argb(150, 90, 220, 255)
                canvas.drawPath(trailPath, paint)
            }
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(37, 12, 44, 94)
            canvas.drawCircle(centerX, centerY, radius + 8f * density, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.5f * density
            paint.color = Color.rgb(60, 218, 255)
            canvas.drawCircle(centerX, centerY, radius, paint)
            paint.strokeWidth = 1.1f * density
            paint.color = Color.argb(160, 160, 237, 255)
            canvas.drawCircle(centerX, centerY, radius - 7f * density, paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            canvas.drawCircle(centerX, centerY, 3.5f * density, paint)
        }
    }
}
