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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import tw.airgesture.transferbeta.core.TransferAnimationPolicy
import tw.airgesture.transferbeta.core.VisualEvent
import tw.airgesture.transferbeta.core.VisualPhase

/**
 * Small cross-app visual feedback shown only after a deliberate hand gesture or
 * transfer event. Does not receive touches and does not run idle animations.
 * The launcher preview is branding; actual overlay uses inexpensive Android Views.
 */
class GestureStatusOverlay(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: LinearLayout? = null
    private var title: TextView? = null
    private var circle: TextView? = null
    private var bar: ProgressBar? = null
    private var shownPhase: VisualPhase? = null
    private var removal: Runnable? = null

    fun showState(event: VisualEvent) {
        if (!Settings.canDrawOverlays(context)) return
        main.post {
            val receiver = TransferAnimationPolicy.isReceiver(event)
            val color = when {
                event.phase == VisualPhase.FAILURE -> Color.rgb(217, 72, 73)
                receiver -> Color.rgb(28, 142, 255)
                else -> Color.rgb(13, 195, 123)
            }
            val isProgressUpdate = shownPhase == event.phase &&
                event.phase == VisualPhase.RECEIVING && root != null
            if (!isProgressUpdate) {
                dismiss()
                try { buildOverlay(color) } catch (_: Exception) { dismiss(); return@post }
            }
            title?.text = event.label.take(80)
            circle?.text = TransferAnimationPolicy.symbol(event)
            bar?.apply {
                val progressValue = TransferAnimationPolicy.displayProgress(event)
                visibility = if (progressValue == null) android.view.View.GONE
                    else android.view.View.VISIBLE
                if (progressValue != null) {
                    isIndeterminate = false
                    progress = progressValue
                }
            }
            shownPhase = event.phase
            if (!isProgressUpdate) {
                root?.apply {
                    alpha = 0f
                    scaleX = .86f
                    scaleY = .86f
                    animate().alpha(1f).scaleX(1f).scaleY(1f)
                        .setDuration(260).start()
                }
                circle?.apply {
                    scaleX = .48f
                    scaleY = .48f
                    animate().scaleX(1f).scaleY(1f).setDuration(420).start()
                }
            }
            removal?.let { main.removeCallbacks(it) }
            val remove = Runnable { dismiss() }
            removal = remove
            main.postDelayed(remove, TransferAnimationPolicy.durationMs(event))
        }
    }

    private fun buildOverlay(accent: Int) {
        val density = context.resources.displayMetrics.density
        fun dp(x: Int) = (x * density).toInt()
        val wrap = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0xf00a1728.toInt())
                setStroke(dp(1), accent)
            }
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val glyph = TextView(context).apply {
            gravity = Gravity.CENTER
            textSize = 24f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(accent)
            }
        }
        content.addView(glyph, LinearLayout.LayoutParams(dp(42), dp(42)))
        val words = TextView(context).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(dp(11), 0, 0, 0)
            maxLines = 2
        }
        content.addView(words, LinearLayout.LayoutParams(dp(208), -2))
        wrap.addView(content)
        val indicator = ProgressBar(context, null,
            android.R.attr.progressBarStyleHorizontal).apply {
            visibility = android.view.View.GONE
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(accent)
        }
        wrap.addView(indicator, LinearLayout.LayoutParams(-1, dp(3)).apply {
            topMargin = dp(8)
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
            y = dp(62)
        }
        wm.addView(wrap, params)
        root = wrap
        circle = glyph
        title = words
        bar = indicator
    }

    fun dismiss() {
        removal?.let { main.removeCallbacks(it) }
        removal = null
        val view = root
        root = null
        circle = null
        title = null
        bar = null
        shownPhase = null
        if (view != null) try { wm.removeView(view) } catch (_: Exception) {}
    }
}
