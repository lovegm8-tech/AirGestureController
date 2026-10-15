package tw.airgesture.wakeprobe

import android.app.Activity
import android.app.KeyguardManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

/**
 * Temporary, transparent screen-on experiment Activity.
 *
 * Launch from the background may be blocked on Android 14+; this class logs
 * onCreate/onResume only when the system actually launches it. No attempt to
 * bypass the keyguard: requestDismissKeyguard leaves authentication to Android.
 */
class FaceUnlockWakeActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val finishSafely = Runnable {
        if (!isFinishing && !isDestroyed) {
            ProbeLog.add(this, "Activity 喚醒頁已自動關閉，避免遮住三星鎖定畫面")
            finish()
            overridePendingTransition(0, 0)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        ProbeLog.add(this, "Activity 喚醒頁 onCreate：背景啟動畫面獲系統允許")
        main.postDelayed(finishSafely, 2400L)
    }

    override fun onResume() {
        super.onResume()
        val power = getSystemService(POWER_SERVICE) as PowerManager
        val keyguard = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        ProbeLog.add(this, "Activity onResume：螢幕狀態=${if (power.isInteractive) "已亮" else "尚未亮"}，鎖定=${keyguard.isKeyguardLocked}")
        if (!keyguard.isKeyguardLocked) {
            ProbeLog.add(this, "Activity 已確認不在鎖定畫面；準備返回原影片")
            main.postDelayed(finishSafely, 250L)
            return
        }
        // This is a request to SYSTEM authentication, not an unlock bypass.
        try {
            ProbeLog.add(this, "向 Android 請求正常解除鎖定流程（不代替臉部/密碼驗證）")
            keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    ProbeLog.add(this@FaceUnlockWakeActivity, "Android 回報解除鎖定成功")
                    main.post(finishSafely)
                }
                override fun onDismissCancelled() {
                    ProbeLog.add(this@FaceUnlockWakeActivity, "Android 取消解除鎖定請求")
                }
                override fun onDismissError() {
                    ProbeLog.add(this@FaceUnlockWakeActivity, "Android 拒絕解除鎖定請求")
                }
            })
        } catch (e: Exception) {
            ProbeLog.add(this, "鎖定畫面驗證請求失敗：${e.javaClass.simpleName}")
        }
    }

    override fun onDestroy() {
        main.removeCallbacks(finishSafely)
        super.onDestroy()
    }
}
