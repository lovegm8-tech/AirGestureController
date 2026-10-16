package tw.airgesture.controller.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.media.AudioManager
import android.os.PowerManager
import android.graphics.Path
import android.graphics.Point
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import tw.airgesture.controller.core.GestureDecisionEngine
import tw.airgesture.controller.core.HandVisualState
import tw.airgesture.controller.core.Settings
import tw.airgesture.controller.core.PointerDwellEngine
import tw.airgesture.controller.core.WakeDiagnosticLog

/** Injects only user-requested touch gestures; it never reads view contents. */
class GestureAccessibilityService : AccessibilityService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var activePackage: String? = null
    private var indicator: HandIndicator? = null
    private var cursor: PointerOverlay? = null

    companion object {
        @Volatile private var current: GestureAccessibilityService? = null
        private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
        fun isReady(): Boolean = current != null
        fun isYouTubeForeground(): Boolean = current?.activePackage == YOUTUBE_PACKAGE
        fun showIndicator() {
            current?.let { service -> service.mainHandler.post {
                if (current === service) {
                    if (service.indicator == null) service.indicator = HandIndicator(
                        service, Settings.animatedHandFeedback(service)
                    )
                    service.indicator?.show()
                }
            } }
        }
        fun handStateChanged(state: HandVisualState) {
            current?.let { service -> service.mainHandler.post {
                if (current === service) service.indicator?.update(state)
            } }
        }
        fun hideIndicator() {
            current?.let { service -> service.mainHandler.post {
                if (current === service) {
                    service.indicator?.hide()
                    service.indicator = null // Re-read display setting when service restarts.
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
        if (action == GestureDecisionEngine.Action.MEDIA_PLAY_PAUSE) {
            // A YouTube-only media button, not a touch gesture. Fail closed if
            // the foreground package switched after the classifier's snapshot.
            if (activePackage != YOUTUBE_PACKAGE) return
            try {
                val audio = getSystemService(AUDIO_SERVICE) as AudioManager
                val now = SystemClock.uptimeMillis()
                val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0)
                val up = KeyEvent(now, now, KeyEvent.ACTION_UP,
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0)
                audio.dispatchMediaKeyEvent(down)
                audio.dispatchMediaKeyEvent(up)
                WakeDiagnosticLog.add(this, "YouTube 長握拳：已發送媒體播放／暫停鍵")
            } catch (e: Exception) {
                WakeDiagnosticLog.add(this, "YouTube 媒體播放／暫停鍵失敗：${e.javaClass.simpleName}")
            }
            return
        }
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
            GestureDecisionEngine.Action.MEDIA_PLAY_PAUSE -> return
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
