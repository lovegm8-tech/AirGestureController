package tw.airgesture.controller.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Point
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import tw.airgesture.controller.core.GestureDecisionEngine

/** Injects only user-requested touch gestures; it never reads view contents. */
class GestureAccessibilityService : AccessibilityService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var activePackage: String? = null

    companion object {
        @Volatile private var current: GestureAccessibilityService? = null
        fun isReady(): Boolean = current != null
        fun send(action: GestureDecisionEngine.Action) {
            val instance = current ?: return
            instance.mainHandler.post { if (current === instance) instance.execute(action) }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        current = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            activePackage = event.packageName?.toString()
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun execute(action: GestureDecisionEngine.Action) {
        // Avoid tapping this controller's own settings UI.
        if (activePackage == packageName) return
        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        val size = Point().also { manager.defaultDisplay.getRealSize(it) }
        if (size.x <= 0 || size.y <= 0) return
        val x = size.x * .5f
        val y = size.y * .5f
        val path = Path()
        val duration: Long
        when (action) {
            GestureDecisionEngine.Action.SWIPE_UP -> {
                path.moveTo(x, size.y * .74f)
                path.lineTo(x, size.y * .30f)
                duration = 320L
            }
            GestureDecisionEngine.Action.SWIPE_DOWN -> {
                path.moveTo(x, size.y * .30f)
                path.lineTo(x, size.y * .74f)
                duration = 320L
            }
            GestureDecisionEngine.Action.TAP_CENTER -> {
                path.moveTo(x, y)
                duration = 80L
            }
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }
}
