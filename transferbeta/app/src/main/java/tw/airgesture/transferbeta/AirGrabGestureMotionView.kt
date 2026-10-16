package tw.airgesture.transferbeta

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.animation.DecelerateInterpolator
import tw.airgesture.transferbeta.core.VisualPhase
import kotlin.math.max

/**
 * A one-shot, lightweight draw-on-top illusion:
 *  - grab: floating file shrinks into the user's closing palm;
 *  - release: fist opens and a file is released into the receiving phone.
 *
 * No camera frames are rendered/stored. No infinite animation or touch capture.
 */
class AirGrabGestureMotionView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hand = Path()
    private var phase = VisualPhase.CAPTURING
    private var fraction = 1f
    private var animator: ValueAnimator? = null
    private var accent = Color.rgb(13, 195, 123)

    fun display(next: VisualPhase, color: Int) {
        animator?.cancel()
        phase = next
        accent = color
        fraction = 0f
        val duration = when (next) {
            VisualPhase.CAPTURING -> 790L
            VisualPhase.OFFER_RECEIVED, VisualPhase.RECEIVING,
            VisualPhase.RECEIVE_COMPLETE -> 830L
            VisualPhase.SEND_COMPLETE -> 620L
            else -> 520L
        }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                fraction = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w < 2f || h < 2f) return
        val cx = w * 0.42f
        val cy = h * 0.57f
        val unit = max(1f, minOf(w / 110f, h / 90f))
        val grab = phase == VisualPhase.CAPTURING ||
            phase == VisualPhase.CONTENT_READY ||
            phase == VisualPhase.WAITING_FOR_RECEIVER ||
            phase == VisualPhase.SEND_COMPLETE
        val release = phase == VisualPhase.OFFER_RECEIVED ||
            phase == VisualPhase.RECEIVING ||
            phase == VisualPhase.VERIFYING ||
            phase == VisualPhase.RECEIVE_COMPLETE
        val opened = if (grab) 1f - fraction else if (release) fraction else 0.55f

        // Soft radial rings: one-time motion tied to the gesture, no perpetual pulse.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.3f * unit
        paint.color = accent
        paint.alpha = ((1f - fraction) * 115f).toInt().coerceIn(0, 255)
        val ring = (25f + fraction * 17f) * unit
        canvas.drawCircle(cx, cy, ring, paint)
        paint.alpha = 255

        // Stylised grasp: palm and four articulated fingers open -> close.
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(235, 19, 48, 71)
        val palm = RectF(cx - 15f * unit, cy - 7f * unit, cx + 18f * unit,
            cy + 23f * unit)
        canvas.drawRoundRect(palm, 11f * unit, 11f * unit, paint)
        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3.5f * unit
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        hand.reset()
        hand.moveTo(cx - 13f * unit, cy + 18f * unit)
        hand.quadTo(cx - 24f * unit, cy + 5f * unit, cx - 19f * unit, cy - 1f * unit)
        hand.quadTo(cx - 14f * unit, cy - 7f * unit, cx - 8f * unit, cy)
        hand.moveTo(cx - 8f * unit, cy - 1f * unit)
        for (finger in 0..3) {
            val x = cx + (-8f + finger * 9f) * unit
            val span = (9f + opened * (16f + (finger % 2) * 4f)) * unit
            hand.moveTo(x, cy - 1f * unit)
            hand.lineTo(x + opened * (finger - 1.5f) * 3f * unit,
                cy - span)
            hand.quadTo(x + (7f + opened * 3f) * unit,
                cy - (span + 1.5f * unit), x + (7f + opened * 3f) * unit,
                cy - 1f * unit)
        }
        hand.moveTo(cx + 18f * unit, cy)
        hand.quadTo(cx + 25f * unit, cy + 8f * unit, cx + 16f * unit, cy + 21f * unit)
        hand.quadTo(cx, cy + 26f * unit, cx - 13f * unit, cy + 18f * unit)
        canvas.drawPath(hand, paint)

        // A file tile is absorbed from the right, or released outward to the right.
        val fileMotion = when {
            phase == VisualPhase.CAPTURING -> fraction
            phase == VisualPhase.CONTENT_READY ||
                phase == VisualPhase.WAITING_FOR_RECEIVER -> 1f
            phase == VisualPhase.SEND_COMPLETE -> 1f
            phase == VisualPhase.OFFER_RECEIVED -> 0f
            release -> fraction
            else -> 0f
        }
        val fx = cx + (14f + 34f * (if (grab) 1f - fileMotion else fileMotion)) * unit
        val fy = cy - (9f + 5f * (if (grab) 1f - fileMotion else fileMotion)) * unit
        val scale = if (grab) (1f - fileMotion * 0.76f) else (0.24f + fileMotion * 0.76f)
        val docWidth = 21f * unit * scale
        val docHeight = 27f * unit * scale
        paint.style = Paint.Style.FILL
        paint.color = accent
        paint.alpha = if (phase == VisualPhase.SEND_COMPLETE) 0 else 235
        canvas.drawRoundRect(RectF(fx - docWidth / 2f, fy - docHeight / 2f,
            fx + docWidth / 2f, fy + docHeight / 2f), 2.5f * unit, 2.5f * unit, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.8f * unit * scale
        paint.color = Color.WHITE
        canvas.drawLine(fx - docWidth * .30f, fy - docHeight * .15f,
            fx + docWidth * .27f, fy - docHeight * .15f, paint)
        canvas.drawLine(fx - docWidth * .30f, fy + docHeight * .10f,
            fx + docWidth * .15f, fy + docHeight * .10f, paint)
        paint.alpha = 255
        paint.style = Paint.Style.FILL
    }
}
