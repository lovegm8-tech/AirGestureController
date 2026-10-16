package tw.airgesture.controller.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import tw.airgesture.controller.core.HandVisualState

/**
 * Animated, non-touchable accessibility HUD. It never reads the screen and
 * never changes gesture classification. Camera sees a hand -> chip expands;
 * idle -> old tiny dot. No screen-overlay permission needed.
 */
internal class HandIndicator(
    private val context: Context,
    private val animated: Boolean = true
) {
    private val main = Handler(Looper.getMainLooper())
    private val windows = context.getSystemService(WindowManager::class.java)
    private var root: FrameLayout? = null
    private var dot: View? = null
    private var chip: LinearLayout? = null
    private var symbol: TextView? = null
    private var label: TextView? = null
    private var state = HandVisualState.NO_HAND
    private var renderedKey = ""
    private var actionUntil = 0L
    private var idleScheduled = false
    private val restore = Runnable { render(state) }
    private val toIdle = Runnable {
        idleScheduled = false
        state = HandVisualState.NO_HAND
        render(state)
    }

    fun show() {
        if (root != null) return
        val width = if (animated) 184 else 22
        val host = FrameLayout(context).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            contentDescription = null
            isClickable = false
            isFocusable = false
            clipChildren = false
        }
        val indicator = View(context).apply {
            background = circle(0xff777777.toInt())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        host.addView(indicator, FrameLayout.LayoutParams(dp(22), dp(22),
            Gravity.END or Gravity.CENTER_VERTICAL))
        if (animated) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), 0, dp(8), 0)
                background = bubble(0xff64748b.toInt())
                alpha = 0f
                visibility = View.INVISIBLE
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val hand = TextView(context).apply {
                gravity = Gravity.CENTER
                textSize = 23f
                text = "●"
                setTextColor(Color.WHITE)
                includeFontPadding = false
            }
            row.addView(hand, LinearLayout.LayoutParams(dp(34), ViewGroup.LayoutParams.MATCH_PARENT))
            val wording = TextView(context).apply {
                text = "已偵測到手"
                textSize = 13.5f
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER_VERTICAL
                includeFontPadding = false
                maxLines = 1
            }
            row.addView(wording, LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            host.addView(row, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT))
            chip = row
            symbol = hand
            label = wording
        }

        val params = WindowManager.LayoutParams(
            dp(width), dp(if (animated) 44 else 22),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(16)
            y = dp(78)
            title = "AirGesture hand feedback"
        }
        try {
            windows.addView(host, params)
            root = host
            dot = indicator
            state = HandVisualState.NO_HAND
            actionUntil = 0L
            renderedKey = ""
            render(state)
        } catch (_: Exception) {
            dot = null
            chip = null
            symbol = null
            label = null
            root = null
        }
    }

    /** Called only on the accessibility service's main thread. */
    fun update(next: HandVisualState) {
        if (root == null) return
        if (next == HandVisualState.NO_HAND) {
            // Short gaps in MediaPipe detection should not cause HUD flicker.
            if (state != HandVisualState.NO_HAND && !idleScheduled) {
                idleScheduled = true
                main.postDelayed(toIdle, 520L)
            }
            return
        }
        main.removeCallbacks(toIdle)
        idleScheduled = false
        if (next == state) return
        state = next
        if (SystemClock.uptimeMillis() >= actionUntil) render(state)
    }

    fun flashAction() {
        if (root == null) return
        actionUntil = SystemClock.uptimeMillis() + 420L
        main.removeCallbacks(restore)
        renderKey("ACTION", "✓", "已執行操作", 0xff2563eb.toInt())
        main.postDelayed(restore, 440L)
    }

    fun hide() {
        main.removeCallbacks(toIdle)
        main.removeCallbacks(restore)
        idleScheduled = false
        chip?.animate()?.cancel()
        dot?.animate()?.cancel()
        symbol?.animate()?.cancel()
        root?.let { try { windows.removeViewImmediate(it) } catch (_: Exception) {} }
        root = null
        dot = null
        chip = null
        symbol = null
        label = null
        state = HandVisualState.NO_HAND
        renderedKey = ""
    }

    private fun render(next: HandVisualState) {
        when (next) {
            HandVisualState.NO_HAND -> renderKey("IDLE", "", "", 0xff777777.toInt())
            HandVisualState.HAND_DETECTED -> renderKey(
                "HAND", "◉", "已偵測到手", 0xffd97706.toInt())
            HandVisualState.OPEN_PALM -> renderKey(
                "PALM", "✋", "張掌已辨識", 0xff16a34a.toInt())
            HandVisualState.CLOSED_FIST -> renderKey(
                "FIST", "✊", "握拳已辨識", 0xff2563eb.toInt())
            HandVisualState.INDEX_FINGER -> renderKey(
                "INDEX", "☝", "食指游標辨識", 0xff0891b2.toInt())
        }
    }

    private fun renderKey(key: String, icon: String, message: String, color: Int) {
        if (root == null || renderedKey == key) return
        renderedKey = key
        if (!animated) {
            // Preserve the original small gray/green/action-blue dot if requested.
            val dotColor = when (key) {
                "PALM" -> 0xff16a34a.toInt()
                "ACTION" -> 0xff2563eb.toInt()
                else -> 0xff777777.toInt()
            }
            dot?.background = circle(dotColor)
            return
        }
        if (key == "IDLE") {
            chip?.animate()?.cancel()
            chip?.animate()?.alpha(0f)?.setDuration(180L)?.withEndAction {
                chip?.visibility = View.INVISIBLE
            }?.start()
            dot?.animate()?.alpha(1f)?.setDuration(180L)?.start()
            return
        }
        val row = chip ?: return
        val hand = symbol ?: return
        hand.text = icon
        label?.text = message
        row.background = bubble(color)
        row.animate().cancel()
        row.visibility = View.VISIBLE
        row.animate().alpha(1f).setDuration(160L).start()
        dot?.animate()?.alpha(0f)?.setDuration(130L)?.start()
        hand.animate().cancel()
        hand.scaleX = .80f
        hand.scaleY = .80f
        hand.animate().scaleX(1.13f).scaleY(1.13f).setDuration(145L)
            .withEndAction {
                hand.animate().scaleX(1f).scaleY(1f).setDuration(150L).start()
            }.start()
    }

    private fun bubble(accent: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(22).toFloat()
        setColor(0xee0f172a.toInt())
        setStroke(dp(2), accent)
    }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(dp(2), Color.WHITE)
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()
}
