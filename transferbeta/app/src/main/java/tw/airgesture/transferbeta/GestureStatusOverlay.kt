package tw.airgesture.transferbeta

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import tw.airgesture.transferbeta.core.TransferAnimationPolicy
import tw.airgesture.transferbeta.core.VisualEvent
import tw.airgesture.transferbeta.core.VisualPhase

/** Animated air-grab over any app: a hand absorbs or releases a document.
 * No continuously looping animation; waiting uses a 1-second countdown only. */
class GestureStatusOverlay(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: LinearLayout? = null
    private var title: TextView? = null
    private var hand: TextView? = null
    private var payload: TextView? = null
    private var bar: ProgressBar? = null
    private var countdown: TextView? = null
    private var phase: VisualPhase? = null
    private var removal: Runnable? = null
    private var tick: Runnable? = null
    private var offerDeadline = 0L

    fun showState(event: VisualEvent) {
        if (!Settings.canDrawOverlays(context)) return
        main.post {
            val accent = when {
                event.phase == VisualPhase.FAILURE -> Color.rgb(217, 72, 73)
                TransferAnimationPolicy.isReceiver(event) -> Color.rgb(28, 142, 255)
                else -> Color.rgb(13, 195, 123)
            }
            val keep = phase == event.phase && root != null &&
                (event.phase == VisualPhase.RECEIVING ||
                    TransferAnimationPolicy.hasReceiveCountdown(event.phase))
            if (!keep) {
                dismiss()
                try { buildOverlay(accent) } catch (_: Exception) {
                    dismiss()
                    return@post
                }
            }
            title?.text = event.label.take(72)
            val progress = TransferAnimationPolicy.displayProgress(event)
            bar?.apply {
                visibility = if (progress == null) View.GONE else View.VISIBLE
                if (progress != null) {
                    isIndeterminate = false
                    setProgress(progress, true)
                }
            }
            phase = event.phase
            if (!keep) {
                root?.apply {
                    alpha = 0f
                    scaleX = .9f
                    scaleY = .9f
                    animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).start()
                }
                animateGesture(event)
            }
            if (TransferAnimationPolicy.hasReceiveCountdown(event.phase)) {
                if (!keep) offerDeadline = SystemClock.uptimeMillis() + 30_000L
                updateCountdown()
            } else {
                tick?.let { main.removeCallbacks(it) }
                tick = null
                countdown?.visibility = View.GONE
            }
            removal?.let { main.removeCallbacks(it) }
            val task = Runnable { dismiss() }
            removal = task
            val remaining = if (TransferAnimationPolicy.hasReceiveCountdown(event.phase))
                (offerDeadline - SystemClock.uptimeMillis()).coerceAtLeast(0L)
            else TransferAnimationPolicy.durationMs(event)
            main.postDelayed(task, remaining)
        }
    }

    private fun animateGesture(event: VisualEvent) {
        val palm = hand ?: return
        val doc = payload ?: return
        val d = context.resources.displayMetrics.density
        palm.animate().cancel()
        doc.animate().cancel()
        when (event.phase) {
            VisualPhase.CAPTURING -> {
                palm.text = "✋"
                doc.alpha = .5f
                palm.scaleX = .88f
                palm.scaleY = .88f
                palm.animate().scaleX(1.07f).scaleY(1.07f)
                    .setDuration(330).withEndAction {
                        if (hand === palm) palm.animate()
                            .scaleX(1f).scaleY(1f).setDuration(180).start()
                    }.start()
            }
            VisualPhase.CONTENT_READY, VisualPhase.WAITING_FOR_RECEIVER -> {
                // Content is ready: shrink the file into the closing hand.
                palm.text = "✋"
                doc.alpha = 1f
                doc.animate().translationX(-35f * d).scaleX(.18f).scaleY(.18f)
                    .alpha(.15f).setDuration(570).start()
                main.postDelayed({
                    if (hand === palm && phase == event.phase) palm.text = "✊"
                }, 340)
            }
            VisualPhase.OFFER_RECEIVED -> {
                palm.text = "✊"
                doc.alpha = .8f
                doc.scaleX = .75f
                doc.scaleY = .75f
            }
            VisualPhase.RECEIVING, VisualPhase.RECEIVE_COMPLETE -> {
                // Receiver opens its fist; the document expands outward.
                palm.text = "✊"
                doc.translationX = -28f * d
                doc.scaleX = .18f
                doc.scaleY = .18f
                doc.alpha = .18f
                main.postDelayed({
                    if (hand === palm && phase == event.phase) palm.text = "✋"
                }, 170)
                doc.animate().translationX(0f).scaleX(1f).scaleY(1f)
                    .alpha(1f).setStartDelay(170).setDuration(490).start()
            }
            VisualPhase.VERIFYING -> {
                palm.text = "✋"
                doc.scaleX = .88f
                doc.scaleY = .88f
                doc.animate().scaleX(1f).scaleY(1f).setDuration(400).start()
            }
            VisualPhase.SEND_COMPLETE -> {
                palm.text = "✊"
                doc.text = "✓"
                doc.alpha = 0f
                doc.scaleX = .45f
                doc.scaleY = .45f
                doc.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(420).start()
            }
            VisualPhase.FAILURE -> {
                palm.text = "!"
                doc.visibility = View.GONE
            }
        }
    }

    private fun updateCountdown() {
        tick?.let { main.removeCallbacks(it) }
        val remaining = (offerDeadline - SystemClock.uptimeMillis()).coerceAtLeast(0L)
        countdown?.apply {
            visibility = View.VISIBLE
            text = "等待接收 · " + ((remaining + 999L) / 1000L) + " 秒"
        }
        if (remaining > 0) {
            val next = Runnable {
                if (root != null && phase?.let {
                        TransferAnimationPolicy.hasReceiveCountdown(it) } == true) {
                    updateCountdown()
                }
            }
            tick = next
            main.postDelayed(next, 1000L.coerceAtMost(remaining))
        }
    }

    private fun buildOverlay(accent: Int) {
        val density = context.resources.displayMetrics.density
        fun dp(x: Int) = (x * density).toInt()
        val wrap = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(9))
            background = GradientDrawable().apply {
                cornerRadius = dp(17).toFloat()
                setColor(0xf20a1728.toInt())
                setStroke(dp(1), accent)
            }
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val stage = FrameLayout(context)
        val palm = TextView(context).apply {
            gravity = Gravity.CENTER
            textSize = 32f
            text = "✋"
        }
        stage.addView(palm, FrameLayout.LayoutParams(
            dp(48), dp(56), Gravity.START or Gravity.CENTER_VERTICAL))
        val doc = TextView(context).apply {
            text = "▤"
            textSize = 25f
            setTextColor(accent)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(0xff183052.toInt())
                cornerRadius = dp(7).toFloat()
                setStroke(dp(1), accent)
            }
        }
        stage.addView(doc, FrameLayout.LayoutParams(
            dp(34), dp(40), Gravity.END or Gravity.CENTER_VERTICAL))
        content.addView(stage, LinearLayout.LayoutParams(dp(96), dp(56)))
        val words = TextView(context).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(dp(7), 0, 0, 0)
            maxLines = 2
        }
        content.addView(words, LinearLayout.LayoutParams(dp(170), -2))
        wrap.addView(content)
        val timer = TextView(context).apply {
            textSize = 13f
            setTextColor(0xffbed7ee.toInt())
            visibility = View.GONE
        }
        wrap.addView(timer)
        val progress = ProgressBar(context, null,
            android.R.attr.progressBarStyleHorizontal).apply {
            visibility = View.GONE
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(accent)
        }
        wrap.addView(progress, LinearLayout.LayoutParams(-1, dp(4)).apply {
            topMargin = dp(7)
        })
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(60)
        }
        wm.addView(wrap, params)
        root = wrap
        title = words
        hand = palm
        payload = doc
        countdown = timer
        bar = progress
    }

    fun dismiss() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { dismiss() }
            return
        }
        removal?.let { main.removeCallbacks(it) }
        removal = null
        tick?.let { main.removeCallbacks(it) }
        tick = null
        val active = root
        root = null
        title = null
        hand = null
        payload = null
        bar = null
        countdown = null
        phase = null
        if (active != null) try { wm.removeView(active) } catch (_: Exception) {}
    }
}
