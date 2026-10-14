package tw.airgesture.controller.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/** Minimal non-touchable accessibility overlay. No SYSTEM_ALERT_WINDOW required. */
internal class HandIndicator(private val context: Context) {
    enum class State { IDLE, PALM, ACTION }

    private val main = Handler(Looper.getMainLooper())
    private val windows = context.getSystemService(WindowManager::class.java)
    private var dot: View? = null
    private var current = State.IDLE
    private var actionUntil = 0L
    private val restore = Runnable { render(current) }

    fun show() {
        if (dot != null) return
        val size = dp(22)
        val view = View(context).apply {
            background = circle(0xff777777.toInt())
            contentDescription = "隔空手勢辨識指示燈"
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val params = WindowManager.LayoutParams(
            size, size, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(16)
            y = dp(78)
            title = "AirGesture indicator"
        }
        try {
            windows.addView(view, params)
            dot = view
            current = State.IDLE
            actionUntil = 0L
        } catch (_: Exception) { dot = null }
    }

    fun update(handIsOpen: Boolean) {
        current = if (handIsOpen) State.PALM else State.IDLE
        if (android.os.SystemClock.uptimeMillis() < actionUntil) return
        render(current)
    }

    fun flashAction() {
        if (dot == null) return
        actionUntil = android.os.SystemClock.uptimeMillis() + 420L
        render(State.ACTION)
        main.removeCallbacks(restore)
        main.postDelayed(restore, 440L)
    }

    fun hide() {
        main.removeCallbacks(restore)
        dot?.let { try { windows.removeViewImmediate(it) } catch (_: Exception) {} }
        dot = null
    }

    private fun render(state: State) {
        dot?.background = circle(when (state) {
            State.IDLE -> 0xff777777.toInt()
            State.PALM -> 0xff16a34a.toInt()
            State.ACTION -> 0xff2563eb.toInt()
        })
    }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(dp(2), Color.WHITE)
    }
    private fun dp(n: Int) = (n * context.resources.displayMetrics.density + .5f).toInt()
}
