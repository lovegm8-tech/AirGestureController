package tw.airgesture.controller

import android.app.Activity
import android.app.KeyguardManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import tw.airgesture.controller.core.WakeDiagnosticLog

/**
 * Short-lived face-unlock handoff. Does not bypass or read device credentials.
 * The camera service releases its CameraX device BEFORE launching this Activity.
 */
class FaceUnlockWakeActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val close = Runnable {
        if (!isFinishing && !isDestroyed) {
            WakeDiagnosticLog.add(this, "喚醒 Activity 已結束，返回原 App")
            finish()
            overridePendingTransition(0, 0)
        }
    }

    override fun onCreate(state: Bundle?) {
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        super.onCreate(state)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        WakeDiagnosticLog.add(this, "已建立解鎖 Activity")
        main.postDelayed(close, 2400L)
    }

    override fun onResume() {
        super.onResume()
        val power = getSystemService(POWER_SERVICE) as PowerManager
        val keyguard = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        WakeDiagnosticLog.add(this, "亮屏=${power.isInteractive}；系統鎖定=${keyguard.isKeyguardLocked}")
        if (!keyguard.isKeyguardLocked) {
            main.postDelayed(close, 220L)
            return
        }
        try {
            keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    WakeDiagnosticLog.add(this@FaceUnlockWakeActivity, "Android 回報解鎖成功")
                    main.post(close)
                }
                override fun onDismissCancelled() {
                    WakeDiagnosticLog.add(this@FaceUnlockWakeActivity, "系統取消解鎖請求")
                }
                override fun onDismissError() {
                    WakeDiagnosticLog.add(this@FaceUnlockWakeActivity, "解鎖請求遭系統拒絕")
                }
            })
        } catch (e: Exception) {
            WakeDiagnosticLog.add(this, "請求系統驗證失敗：${e.javaClass.simpleName}")
        }
    }

    override fun onDestroy() {
        main.removeCallbacks(close)
        super.onDestroy()
    }
}
