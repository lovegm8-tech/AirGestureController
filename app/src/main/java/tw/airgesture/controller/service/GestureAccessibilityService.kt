package tw.airgesture.controller.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.os.PowerManager
import android.graphics.Path
import android.graphics.Point
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import tw.airgesture.controller.core.GestureDecisionEngine
import tw.airgesture.controller.core.PointerDwellEngine

/** Injects only user-requested touch gestures; it never reads view contents. */
class GestureAccessibilityService : AccessibilityService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var activePackage: String? = null
    private var indicator: HandIndicator? = null
    private var cursor: PointerOverlay? = null

    companion object {
        @Volatile private var current: GestureAccessibilityService? = null
        fun isReady(): Boolean = current != null
        fun showIndicator() {
            current?.let { service -> service.mainHandler.post {
                if (current === service) {
                    if (service.indicator == null) service.indicator = HandIndicator(service)
                    service.indicator?.show()
                }
            } }
        }
        fun handDetected(open: Boolean) {
            current?.let { service -> service.mainHandler.post {
                if (current === service) service.indicator?.update(open)
            } }
        }
        fun hideIndicator() {
            current?.let { service -> service.mainHandler.post {
                if (current === service) {
                    service.indicator?.hide()
                    service.cursor?.hide()
                }
            } }
        }

        fun pointerUpdated(result: PointerDwellEngine.Result?) {
            current?.let { service -> service.mainHandler.post {
                if (current !== service) return@post
                if (result == null) {
                    service.cursor?.hide()
                } else {
                    if (service.cursor == null) service.cursor = PointerOverlay(service)
                    val position = service.cursor?.update(result.x, result.y, result.progress, result.click)
                    if (result.click && position != null) {
                        service.tapAt(position.first, position.second)
                        service.indicator?.flashAction()
                    }
                }
            } }
        }
        fun send(action: GestureDecisionEngine.Action) {
            val instance = current ?: return
            instance.mainHandler.post {
                if (current === instance) {
                    instance.execute(action)
                    instance.indicator?.flashAction()
                }
            }
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
        indicator?.hide()
        indicator = null
        cursor?.hide()
        cursor = null
        if (current === this) current = null
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun execute(action: GestureDecisionEngine.Action) {
        // Do not interact with the lock screen or a turned-off screen.
        val keyguard = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        val power = getSystemService(POWER_SERVICE) as PowerManager
        if (keyguard.isKeyguardLocked || !power.isInteractive) return
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
            GestureDecisionEngine.Action.SWIPE_BACK -> {
                // Android back-gesture edge: from the extreme left toward the right.
                path.moveTo(size.x * .015f, y)
                path.lineTo(size.x * .60f, y)
                duration = 310L
            }
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    private fun tapAt(x: Float, y: Float) {
        // One-finger dwell never taps the locked screen or our own settings page.
        val keyguard = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        val power = getSystemService(POWER_SERVICE) as PowerManager
        if (keyguard.isKeyguardLocked || !power.isInteractive || activePackage == packageName) return
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 80L)).build()
        dispatchGesture(gesture, null, null)
    }
}
