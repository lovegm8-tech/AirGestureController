package tw.airgesture.controller

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings as AndroidSettings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import tw.airgesture.controller.core.Settings
import tw.airgesture.controller.core.WakeDiagnosticLog
import tw.airgesture.controller.service.CameraGestureService
import tw.airgesture.controller.service.GestureAccessibilityService

/** No ads, analytics, network requests, or SYSTEM_ALERT_WINDOW permissions. */
class MainActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var startButton: Button
    private lateinit var accessButton: Button
    private lateinit var sensitivity: Spinner
    private lateinit var performance: Spinner
    private lateinit var cursorRange: Spinner
    private lateinit var pointerDwell: Spinner
    private lateinit var longFistDuration: Spinner
    private lateinit var animatedHandFeedback: Switch
    private lateinit var circleVolume: Switch
    private lateinit var smartLitPower: Switch
    private lateinit var tiltedPalmAssist: Switch
    private lateinit var fastSwipeAssist: Switch
    private lateinit var screenWake: Switch
    private lateinit var wakePowerMode: Spinner
    private lateinit var cooldown: SeekBar
    private lateinit var cooldownText: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() { updateStatus(); handler.postDelayed(this, 1000) }
    }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startControl() else toast("必須允許前鏡頭才能辨識手勢")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dip(22)
            setPadding(p, dip(28), p, p)
        }
        root.addView(content)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            content.setPadding(dip(22), dip(28) + bars.top, dip(22), dip(22) + bars.bottom)
            insets
        }
        setContentView(root)

        fun label(text: String, size: Float = 17f, bold: Boolean = false): TextView = TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(0xff1e293b.toInt())
            if (bold) setTypeface(null, Typeface.BOLD)
            setPadding(0, dip(10), 0, dip(10))
            content.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        fun button(text: String, onClick: () -> Unit): Button = Button(this).apply {
            this.text = text
            setOnClickListener { onClick() }
            content.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        label("隔空手勢控制器", 27f, true)
        label("無廣告 · 離線手部辨識 · 不保存相機畫面", 14f)
        status = label("狀態檢查中…", 16f, true)
        accessButton = button("① 開啟無障礙控制權限") {
            startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        startButton = button("② 啟動前鏡頭辨識") {
            if (CameraGestureService.running) stopControl() else checkAndStart()
        }
        label("操作對照", 19f, true)
        label("✋ 張掌向上移動 → 螢幕向上滑\n✋ 張掌向下移動 → 螢幕向下滑\n✋ 張掌向右移動 → 左邊緣右滑返回\n☝️ 只伸食指 → 控制懸浮游標，停穩所選秒數點擊\n✊ 張掌後短握拳 → 單擊中央；YouTube 長握拳 → 播放／暫停", 16f)
        label("重複操作：手掌停穩約 0.5 秒即可準備下一次手勢。食指點擊後需移動到新位置，避免停留時連點。", 13f)
        label("辨識提示：手部進入畫面會顯示狀態動畫；沒有手時縮回小圓點。提示不阻擋螢幕點擊。", 13f)
        animatedHandFeedback = Switch(this).apply {
            text = "顯示手部辨識動畫（建議）"
            isChecked = Settings.animatedHandFeedback(this@MainActivity)
            textSize = 16f
        }
        content.addView(animatedHandFeedback, LinearLayout.LayoutParams(-1, dip(54)))
        label("預設開啟：已偵測到手、張掌已辨識、握拳已辨識、食指游標辨識；點擊或滑動成功時短暫顯示執行提示。關閉可改回舊版簡潔小圓點。", 13f)

        label("張掌畫圈控制音量（v0.3.0）", 19f, true)
        circleVolume = Switch(this).apply {
            text = "啟用張掌順／逆時針畫圈調整音量"
            isChecked = Settings.circleVolume(this@MainActivity)
            textSize = 16f
        }
        content.addView(circleVolume, LinearLayout.LayoutParams(-1, -2))
        label("張掌先停穩約 0.35 秒，看到跟隨手掌的藍色圓環後開始畫圈。順時針加音量，逆時針減音量；首段約 240°，持續轉動每 120° 再調 1 格。畫圈軌跡跟隨手掌，偵測到直線滑動時會嘗試保留原本滑動。若造成誤觸可關閉此功能。", 13f)

        label("食指游標停穩點擊時間", 19f, true)
        pointerDwell = Spinner(this)
        pointerDwell.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("1 秒（反應最快）", "2 秒（建議）", "3 秒（較不容易誤觸）"))
        pointerDwell.setSelection(Settings.pointerDwellIndex(this))
        content.addView(pointerDwell, LinearLayout.LayoutParams(-1, dip(54)))
        label("停穩達所選秒數自動點一次；再移動到新位置才會重新計時，避免連續誤點。", 13f)

        label("長握拳判定時間", 19f, true)
        longFistDuration = Spinner(this)
        longFistDuration.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("0.5 秒（快速）", "0.7 秒（建議）", "1 秒（較慢）"))
        longFistDuration.setSelection(Settings.longFistIndex(this))
        content.addView(longFistDuration, LinearLayout.LayoutParams(-1, dip(54)))
        label("YouTube 官方 App：短握拳放開後單擊；持續握拳達所選秒數只發送一次媒體播放／暫停指令，不先點畫面。其他 App：沿用原本單擊與長握拳追加點擊。設定儲存後需重啟辨識。", 13f)

        label("斜角手掌辨識（實驗性）", 19f, true)
        tiltedPalmAssist = Switch(this).apply {
            text = "啟用斜角張掌輔助判斷"
            isChecked = Settings.tiltedPalmAssist(this@MainActivity)
            textSize = 16f
        }
        content.addView(tiltedPalmAssist, LinearLayout.LayoutParams(-1, dip(54)))
        label("預設關閉，保持原本正面辨識。開啟後僅在 AI 無法確認張掌時，嘗試使用手指關節判斷；不改變食指游標及握拳點擊。", 13f)

        label("快速揮掌追蹤（實驗性）", 19f, true)
        fastSwipeAssist = Switch(this).apply {
            text = "啟用快速滑動輔助"
            isChecked = Settings.fastSwipeAssist(this@MainActivity)
            textSize = 16f
        }
        content.addView(fastSwipeAssist, LinearLayout.LayoutParams(-1, dip(54)))
        label("預設關閉。開啟後提早記錄張掌起點，容許約 0.26 秒的短暫辨識中斷；仍需有效張掌畫面才會執行滑動。", 13f)

        label("熄屏張掌喚醒與省電（測試版）", 19f, true)
        screenWake = Switch(this).apply {
            text = "螢幕關閉時張掌約 1 秒，嘗試喚醒與三星臉部解鎖"
            isChecked = Settings.screenWake(this@MainActivity)
            textSize = 16f
        }
        content.addView(screenWake, LinearLayout.LayoutParams(-1, -2))
        label("預設關閉：螢幕熄滅會關閉前鏡頭。開啟後會讓出鏡頭供三星臉部辨識；解鎖完成才恢復原本隔空手勢。", 13f)
        wakePowerMode = Spinner(this)
        wakePowerMode.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("省電優先：偵測 3 秒、關閉前鏡頭 5 秒（建議）",
                   "連續偵測：等待較短、耗電較高"))
        wakePowerMode.setSelection(Settings.wakePowerMode(this))
        content.addView(wakePowerMode, LinearLayout.LayoutParams(-1, dip(54)))
        label("快速喚醒省電：維持 3 秒偵測／5 秒休息，熄屏偵測使用 480×360 畫面並嘗試較低 FPS，保留 CPU 喚醒鎖避免休眠延誤。最多待命 10 分鐘。", 13f)
        label("熄屏喚醒診斷紀錄", 17f, true)
        val wakeEvents = label(WakeDiagnosticLog.read(this), 12f)
        button("重新整理喚醒紀錄") { wakeEvents.text = WakeDiagnosticLog.read(this) }
        button("清除喚醒紀錄") {
            WakeDiagnosticLog.clear(this)
            wakeEvents.text = WakeDiagnosticLog.read(this)
        }

        label("亮屏智慧省電（新功能）", 19f, true)
        smartLitPower = Switch(this).apply {
            text = "啟用三階段智慧待命（建議）"
            isChecked = Settings.smartLitPower(this@MainActivity)
            textSize = 16f
        }
        content.addView(smartLitPower, LinearLayout.LayoutParams(-1, dip(54)))
        label("啟用：無手先每 0.35 秒辨識一次，20 秒後每 0.9 秒一次；有手立即提高到約每 0.1 秒一次並保持 4.5 秒。會嘗試要求前鏡頭 12～15 FPS；關閉則恢復舊版亮屏頻率與鏡頭設定。", 13f)

        label("辨識效能模式", 19f, true)
        performance = Spinner(this)
        performance.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("省電（待命約 3 次／秒、手勢約 7 次／秒）",
                "智慧平衡（待命約 5 次／秒、手勢約 10 次／秒）",
                "高反應（待命約 9 次／秒、手勢約 12 次／秒）"))
        performance.setSelection(Settings.performanceMode(this))
        content.addView(performance, LinearLayout.LayoutParams(-1, dip(54)))
        label("若已啟用亮屏智慧省電，此處的原本省電／平衡／高反應選項僅作為關閉智慧省電後的備援。變更後需重新啟動辨識。", 13f)

        label("食指游標活動範圍", 19f, true)
        cursorRange = Spinner(this)
        cursorRange.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("原始 1 倍（舊版移動幅度）",
                "擴展 2 倍",
                "全螢幕 3 倍（建議）",
                "極大範圍 4 倍（手指活動範圍較小）"))
        cursorRange.setSelection(Settings.cursorRange(this))
        content.addView(cursorRange, LinearLayout.LayoutParams(-1, dip(54)))
        label("改善食指難以移到螢幕四角的問題；如果游標太靈敏，可改選 2 倍。點擊倒數依上方時間設定。", 13f)

        label("手勢靈敏度", 19f, true)
        sensitivity = Spinner(this)
        sensitivity.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("低（防誤觸）", "標準", "高（小幅度也觸發）"))
        sensitivity.setSelection(Settings.sensitivity(this))
        content.addView(sensitivity, LinearLayout.LayoutParams(-1, dip(54)))

        cooldownText = label("操作冷卻時間", 17f)
        cooldown = SeekBar(this).apply {
            max = 11
            progress = ((Settings.cooldown(this@MainActivity) - 500) / 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    cooldownText.text = "操作冷卻時間：${500 + value * 100} 毫秒"
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        }
        cooldownText.text = "操作冷卻時間：${500 + cooldown.progress * 100} 毫秒"
        content.addView(cooldown, LinearLayout.LayoutParams(-1, dip(48)))
        button("儲存設定") {
            Settings.save(this, sensitivity.selectedItemPosition, (500 + cooldown.progress * 100).toLong(), performance.selectedItemPosition, cursorRange.selectedItemPosition, tiltedPalmAssist.isChecked, fastSwipeAssist.isChecked, screenWake.isChecked, wakePowerMode.selectedItemPosition, pointerDwell.selectedItemPosition, longFistDuration.selectedItemPosition, animatedHandFeedback.isChecked, smartLitPower.isChecked, circleVolume.isChecked)
            toast("已儲存；重新啟動辨識後生效")
        }
        label("隱私：App 不要求網路權限，影像只在本機暫時分析，不會錄影或上傳。\n啟動後可從通知列隨時停止前鏡頭。", 13f)
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refresh)
        handler.post(refresh)
    }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }

    private fun checkAndStart() {
        if (!GestureAccessibilityService.isReady()) {
            toast("請先開啟本 App 的無障礙服務")
            startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermission.launch(Manifest.permission.CAMERA)
            return
        }
        startControl()
    }

    private fun startControl() {
        if (!GestureAccessibilityService.isReady()) { toast("無障礙服務尚未啟用"); return }
        Settings.save(this, sensitivity.selectedItemPosition, (500 + cooldown.progress * 100).toLong(), performance.selectedItemPosition, cursorRange.selectedItemPosition, tiltedPalmAssist.isChecked, fastSwipeAssist.isChecked, screenWake.isChecked, wakePowerMode.selectedItemPosition, pointerDwell.selectedItemPosition, longFistDuration.selectedItemPosition, animatedHandFeedback.isChecked, smartLitPower.isChecked, circleVolume.isChecked)
        val intent = Intent(this, CameraGestureService::class.java).setAction(CameraGestureService.START)
        try {
            ContextCompat.startForegroundService(this, intent)
            toast("已啟動；現在可以切換到其他 App")
        } catch (e: Exception) {
            toast("無法啟動相機：${e.javaClass.simpleName}")
        }
        updateStatus()
    }

    private fun stopControl() {
        stopService(Intent(this, CameraGestureService::class.java))
        updateStatus()
    }

    private fun updateStatus() {
        if (!::status.isInitialized) return
        val access = GestureAccessibilityService.isReady()
        val running = CameraGestureService.running
        status.text = "無障礙服務：${if (access) "已開啟" else "未開啟"}\n前鏡頭：${CameraGestureService.status}"
        accessButton.text = if (access) "① 無障礙權限：已開啟" else "① 開啟無障礙控制權限"
        startButton.text = if (running) "停止手勢辨識" else "② 啟動前鏡頭辨識"
    }

    private fun dip(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
