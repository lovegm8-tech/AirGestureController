package tw.airgesture.transferbeta

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * Small, non-touchable, short-lived overlay after explicit hand actions.
 * Requires the separate SYSTEM_ALERT_WINDOW approval; notifications remain
 * available if overlay access is absent or denied.
 */
class GestureStatusOverlay(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var current: TextView? = null
    private var removal: Runnable? = null

    fun show(message: String, success: Boolean = false) {
        if (!Settings.canDrawOverlays(context)) return
        handler.post {
            try {
                dismiss()
                val view = TextView(context).apply {
                    text = message.take(85)
                    textSize = 17f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    setPadding(30, 24, 30, 24)
                    background = GradientDrawable().apply {
                        cornerRadius = 40f
                        setColor(if (success) 0xff1a684a.toInt() else 0xdd192a40.toInt())
                    }
                    alpha = 0f
                    scaleX = 0.85f
                    scaleY = 0.85f
                }
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
                    y = (context.resources.displayMetrics.density * 68).toInt()
                }
                wm.addView(view, params)
                current = view
                view.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(240).start()
                val remove = Runnable { if (current === view) dismiss() }
                removal = remove
                handler.postDelayed(remove, 1700)
            } catch (_: Exception) {
                dismiss()
            }
        }
    }

    fun dismiss() {
        removal?.let { handler.removeCallbacks(it) }
        removal = null
        val view = current ?: return
        current = null
        try { wm.removeView(view) } catch (_: Exception) {}
    }
}
