package tw.airgesture.wakeprobe

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.graphics.Path
import android.graphics.Point
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/** Can only inject a center tap after device is already unlocked. Reads no UI content. */
class MediaGestureAccessibilityService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())

    companion object {
        @Volatile private var current: MediaGestureAccessibilityService? = null
        fun isReady(): Boolean = current != null
        fun tapCenter(): Boolean {
            val service = current ?: return false
            service.main.post { if (current === service) service.doTap() }
            return true
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        current = this
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun doTap() {
        val power = getSystemService(POWER_SERVICE) as PowerManager
        val lock = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        if (!power.isInteractive || lock.isKeyguardLocked) {
            ProbeLog.add(this, "禁止觸控：螢幕未亮或尚未解鎖")
            return
        }
        val size = Point().also {
            (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealSize(it)
        }
        if (size.x <= 0 || size.y <= 0) return
        val path = Path().apply { moveTo(size.x * .5f, size.y * .5f) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 75L))
            .build()
        val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                ProbeLog.add(this@MediaGestureAccessibilityService, "已模擬螢幕中央點擊（不代表影片一定播放）")
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                ProbeLog.add(this@MediaGestureAccessibilityService, "中央點擊被系統取消")
            }
        }, main)
        if (!accepted) ProbeLog.add(this, "系統拒絕模擬中央點擊")
    }
}
