package tw.airgesture.wakeprobe

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.graphics.Typeface
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/** Standalone, manual-start screen-off capability probe (no recording). */
class MainActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var state: TextView
    private lateinit var timeline: TextView
    private lateinit var start: Button
    private val refresher = object : Runnable {
        override fun run() {
            if (::state.isInitialized) {
                state.text = "狀態：${WakeProbeService.status}\n" +
                    "無障礙點擊：${if (MediaGestureAccessibilityService.isReady()) "已授權" else "尚未授權"}"
                start.text = if (WakeProbeService.running) "停止本次實驗" else "啟動 8 分鐘實驗"
                timeline.text = ProbeLog.read(this@MainActivity)
            }
            handler.postDelayed(this, 900L)
        }
    }
    private val askCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchProbe() else notice("需要前鏡頭權限才能測試")
    }
    private val askNotification = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Foreground service also runs when notification permission is denied. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroller = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(30), dp(20), dp(24))
        }
        scroller.addView(layout)
        setContentView(scroller)

        fun label(value: String, size: Float = 15f, strong: Boolean = false): TextView =
            TextView(this).apply {
                text = value
                textSize = size
                if (strong) setTypeface(null, Typeface.BOLD)
                setPadding(0, dp(8), 0, dp(8))
                layout.addView(this)
            }
        fun button(value: String, click: () -> Unit): Button =
            Button(this).apply {
                text = value
                setOnClickListener { click() }
                layout.addView(this)
            }

        label("張掌喚醒實驗", 26f, true)
        label("獨立於 AirGestureController 的測試 App\n僅在本機分析前鏡頭，不錄影、不傳送影像、不要求網路權限。")
        state = label("狀態讀取中", 16f, true)
        label("測試操作", 18f, true)
        label("① 先讓影片停在原本的 App。\n② 授權無障礙點擊權限。\n③ 啟動實驗後，切回影片再關閉螢幕。\n④ 對前鏡頭張掌保持約 1 秒，觀察是否亮屏。\n⑤ 由三星原有臉部解鎖；回到影片後再張掌→握拳，嘗試點擊影片中央繼續播放。")
        label("關閉螢幕後是否還能使用相機、能否由 App 亮屏，皆取決於三星與 Android 限制。此實驗不會解除或繞過鎖定畫面。")
        button("① 設定實驗用無障礙服務") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        start = button("② 啟動 8 分鐘實驗") {
            if (WakeProbeService.running) {
                stopService(Intent(this, WakeProbeService::class.java))
            } else if (!MediaGestureAccessibilityService.isReady()) {
                notice("若要測試解鎖後握拳點擊，先開啟實驗用無障礙服務")
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } else if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                askCamera.launch(Manifest.permission.CAMERA)
            } else launchProbe()
        }
        button("查看「允許開啟螢幕」特殊權限（若系統有此設定）") {
            // App details allows Samsung users to find per-app special access.
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:$packageName")
            })
        }
        label("測試結果紀錄（時間順序）", 18f, true)
        button("清除測試紀錄") { ProbeLog.clear(this) }
        timeline = label("等待讀取…", 13f)
        label("重要：正式隔空手勢 App 與這款測試 App 不宜同時啟用，因為兩者都需要前鏡頭。\n測試 8 分鐘後會自動停止，也可從通知或此頁停止。")
    }

    private fun launchProbe() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            askNotification.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        try {
            ContextCompat.startForegroundService(this,
                Intent(this, WakeProbeService::class.java).setAction(WakeProbeService.START))
            notice("實驗已啟動，請切回影片再熄屏")
        } catch (e: Exception) {
            ProbeLog.add(this, "啟動失敗：${e.javaClass.simpleName}")
            notice("無法啟動相機服務：${e.javaClass.simpleName}")
        }
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refresher)
        handler.post(refresher)
    }
    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }
    private fun dp(n: Int): Int = (n * resources.displayMetrics.density + .5f).toInt()
    private fun notice(msg: String) { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
}
