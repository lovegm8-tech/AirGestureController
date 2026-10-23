package tw.airgesture.transferbeta

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.provider.Settings
import androidx.lifecycle.Lifecycle
import tw.airgesture.transferbeta.core.GrabContentPolicy
import tw.airgesture.transferbeta.core.VideoSendMode
import android.app.AlertDialog
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityManager
import android.view.View
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.BatteryManager
import android.content.IntentFilter
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.Gravity
import android.widget.Button
import android.widget.Switch
import android.widget.Spinner
import android.widget.SeekBar
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.media.projection.MediaProjectionManager
import tw.airgesture.transferbeta.subtitle.SubtitleCaptureService
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import tw.airgesture.transferbeta.core.FloatingSettings
import tw.airgesture.transferbeta.core.LegacyUpdateCleanup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors

class TransferActivity : ComponentActivity() {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val service = "tw.airgesture.transferbeta.p2p.v1"
    private val nearby by lazy { Nearby.getConnectionsClient(this) }
    private val deviceId by lazy {
        val prefs = getSharedPreferences("device", MODE_PRIVATE)
        prefs.getString("id", null) ?: UUID.randomUUID().toString().take(12).also {
            prefs.edit().putString("id", it).apply()
        }
    }
    private lateinit var status: TextView
    private lateinit var titleHint: TextView
    private lateinit var primaryAction: Button
    private lateinit var gestureSwitch: Switch
    private lateinit var transferSwitch: Switch
    private lateinit var subtitleSwitch: Switch
    private lateinit var subtitleLanguage: Spinner
    private lateinit var voiceFallbackSwitch: Switch
    private var subtitlePendingPermission = false
    private var subtitleNeedsOverlay = false
    private lateinit var modeDescription: TextView
    private var updatingModeControls = false
    private lateinit var advanced: LinearLayout
    private lateinit var setupGuide: LinearLayout
    private lateinit var setupTitle: TextView
    private lateinit var cameraStep: Button
    private lateinit var nearbyStep: Button
    private lateinit var accessibilityStep: Button
    private lateinit var overlayStep: Button
    private lateinit var notificationStep: Button
    private var advancedOpen = false
    private var normalLauncherEntry = false
    private var homePromptVisible = false
    private var queuedAutoHome = false
    private val homePrefs by lazy { getSharedPreferences("airgrab_home", MODE_PRIVATE) }
    private lateinit var chosen: TextView
    private lateinit var hand: TextView
    private lateinit var animation: TextView
    private lateinit var progress: ProgressBar
    private var provider: ProcessCameraProvider? = null
    private var recognizer: GestureRecognizer? = null
    private val sequence = GestureSequence(holdMillis = 2_000L, unknownGraceMillis = 850L)
    @Volatile private var enhancedMode = true
    private var endpoint: String? = null
    private var connected = false
    private var sending = false
    private var receiving = false
    private var file: File? = null
    private var fileName = ""
    private var fileHash = ""
    private var session = ""
    private var outgoingId = -1L
    private var incomingId = -1L
    private var incoming: Payload? = null
    private var offer: Offer? = null
    private data class LinkOffer(val session: String, val url: String)
    private var linkOffer: LinkOffer? = null
    private var lastReceivedLink: String? = null
    private var offerEpoch = 0
    private data class Offer(val session: String, val name: String, val size: Long, val hash: String)

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadFile(uri)
    }
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) flash("通知權限未開；接收端背景收到網址時，可能無法顯示開啟連結的通知")
    }
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshHome()
    }
    private val voiceAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val allowed = granted &&
            getSharedPreferences("floating_subtitle", MODE_PRIVATE)
                .getString("source_language", "en") == "en"
        getSharedPreferences("floating_subtitle", MODE_PRIVATE)
            .edit().putBoolean("enable_voice_fallback_en", allowed).apply()
        if (::voiceFallbackSwitch.isInitialized) {
            updatingModeControls = true
            voiceFallbackSwitch.isChecked = allowed
            updatingModeControls = false
        }
        flash(if (allowed) "已授權音訊擷取；重新開啟翻譯後啟用語音補位"
              else "未取得音訊權限；仍可使用 OCR")
    }
    private val subtitleCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        subtitlePendingPermission = false
        if (result.resultCode == RESULT_OK && result.data != null) {
            try {
                val command = Intent(this, SubtitleCaptureService::class.java)
                    .setAction(SubtitleCaptureService.ACTION_START)
                    .putExtra(SubtitleCaptureService.EXTRA_RESULT, result.resultCode)
                    .putExtra(SubtitleCaptureService.EXTRA_DATA, result.data)
                ContextCompat.startForegroundService(this, command)
                // Show the calibration rectangle over the previous video app,
                // not on top of Floating Control's settings page.
                main.postDelayed({
                    moveTaskToBack(true)
                    refreshHome()
                }, 350)
            } catch (exception: Exception) {
                flash("無法啟動 OCR 懸浮翻譯：" + exception.javaClass.simpleName)
            }
        } else {
            flash("未允許擷取畫面，懸浮翻譯維持關閉")
        }
        refreshHome()
    }

    private var shownPairCode: String? = null

    /** Pure transfer mode must not require camera or accessibility access. */
    private fun hasModeAccessibility() =
        !FloatingSettings.gestureCamera(this) || hasAccessibilityPermission()

    private fun hasModePermissions() =
        (!FloatingSettings.gestureCamera(this) ||
            permissionGranted(Manifest.permission.CAMERA)) &&
        (!FloatingSettings.nearby(this) || nearbyGranted())

    private fun modeLabel(): String {
        val gesture = FloatingSettings.gestureCamera(this)
        val transfer = FloatingSettings.nearby(this)
        return when {
            gesture && transfer -> "完整模式：手勢操作＋亮屏附近傳檔"
            gesture -> "手勢模式：前鏡頭操作，附近搜尋已關閉"
            transfer -> "純傳檔模式：前鏡頭關閉，亮屏時可收發檔案"
            else -> "功能暫停：前鏡頭和附近搜尋都關閉"
        }
    }

    private fun hasAccessibilityPermission(): Boolean {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
            val name = it.resolveInfo.serviceInfo
            name.packageName == packageName &&
                name.name.endsWith(".GlobalGrabAccessibilityService")
        }
    }

    private fun attachBackgroundSession() {
        val manager = GlobalGestureService.transfer ?: return
        manager.onStatus = { text ->
            show(text)
            refreshHome()
            maybeReturnHome()
        }
        manager.onConnectionState = { _ ->
            refreshHome()
            maybeReturnHome()
        }
        manager.onPairing = { code ->
            if (shownPairCode != code && !isFinishing) {
                shownPairCode = code
            homePromptVisible = true
            refreshHome()
            AlertDialog.Builder(this)
                .setTitle("確認配對")
                .setMessage("請確認兩支手機都顯示相同數字：\n\n" + code)
                .setPositiveButton("數字相同，連線") { _, _ ->
                    manager.acceptPairing(true)
                    shownPairCode = null
                    homePromptVisible = false
                    refreshHome()
                    maybeReturnHome()
                }
                .setNegativeButton("拒絕") { _, _ ->
                    manager.acceptPairing(false)
                    shownPairCode = null
                    homePromptVisible = false
                    refreshHome()
                }
                .setCancelable(false)
                .show()
            }
        }
    }

    private fun maybeReturnHome() {
        if (queuedAutoHome || subtitlePendingPermission) return
        val mayClose = tw.airgesture.transferbeta.core.HomeScreenPolicy.shouldAutoBackground(
            homePrefs.getBoolean("auto_run", false),
            normalLauncherEntry,
            GlobalGestureService.isActive,
            GlobalGestureService.transfer?.isConnected == true,
            homePromptVisible,
            advancedOpen
        )
        // Camera-free transfer is allowed to background without overlay
        // permission; share intents still reach ShareReceiverActivity.
        if (!mayClose || (FloatingSettings.gestureCamera(this) &&
                !Settings.canDrawOverlays(this))) return
        queuedAutoHome = true
        main.postDelayed({
            queuedAutoHome = false
            if (tw.airgesture.transferbeta.core.HomeScreenPolicy.shouldAutoBackground(
                    homePrefs.getBoolean("auto_run", false),
                    normalLauncherEntry,
                    GlobalGestureService.isActive,
                    GlobalGestureService.transfer?.isConnected == true,
                    homePromptVisible,
                    advancedOpen
                ) && (!FloatingSettings.gestureCamera(this) ||
                    Settings.canDrawOverlays(this))) {
                moveTaskToBack(true)
            }
        }, 900)
    }

    private fun beginSubtitleCapture() {
        if (SubtitleCaptureService.running || subtitlePendingPermission) return
        subtitlePendingPermission = true
        if (!Settings.canDrawOverlays(this)) {
            subtitleNeedsOverlay = true
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + packageName)))
        } else {
            requestSubtitleScreenCapture()
        }
    }

    private fun requestSubtitleScreenCapture() {
        try {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            subtitleCaptureLauncher.launch(manager.createScreenCaptureIntent())
        } catch (exception: Exception) {
            subtitlePendingPermission = false
            flash("無法要求畫面擷取：" + exception.javaClass.simpleName)
            refreshHome()
        }
    }

    private fun stopSubtitleCapture() {
        subtitlePendingPermission = false
        subtitleNeedsOverlay = false
        stopService(Intent(this, SubtitleCaptureService::class.java))
        main.postDelayed({ refreshHome() }, 300)
    }

    private fun startGlobalMode() {
        if (!FloatingSettings.gestureCamera(this) && !FloatingSettings.nearby(this)) {
            show("請先開啟前鏡頭手勢或附近裝置傳檔")
            return
        }
        if (!hasModePermissions()) {
            permissionLauncher.launch(permissions().filterNot { permissionGranted(it) }.toTypedArray())
            return
        }
        if (!hasModeAccessibility()) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        homePrefs.edit().putBoolean("auto_run", true).apply()
        if (!GlobalGestureService.isActive) {
            try {
                ContextCompat.startForegroundService(this,
                    Intent(this, GlobalGestureService::class.java)
                        .setAction(GlobalGestureService.ACTION_START))
            } catch (e: Exception) {
                homePrefs.edit().putBoolean("auto_run", false).apply()
                show("無法啟動背景服務：" + e.message)
                return
            }
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        refreshHome()
        main.postDelayed({
            if (!isFinishing) {
                attachBackgroundSession()
                refreshHome()
                maybeReturnHome()
            }
        }, 700)
    }

    private fun permissionGranted(name: String) =
        ContextCompat.checkSelfPermission(this, name) == PackageManager.PERMISSION_GRANTED

    private fun nearbyPermissions(): Array<String> {
        // Always list the actual radio permissions, even while Nearby is OFF:
        // otherwise turning it back on would incorrectly appear authorized.
        val needed = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            needed += Manifest.permission.BLUETOOTH_SCAN
            needed += Manifest.permission.BLUETOOTH_ADVERTISE
            needed += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.NEARBY_WIFI_DEVICES
        return needed.toTypedArray()
    }

    private fun nearbyGranted() = nearbyPermissions().all { permissionGranted(it) }

    private fun requestNextPermission() {
        when {
            FloatingSettings.gestureCamera(this) &&
                !permissionGranted(Manifest.permission.CAMERA) ->
                permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
            FloatingSettings.nearby(this) && !nearbyGranted() ->
                permissionLauncher.launch(nearbyPermissions().filterNot { permissionGranted(it) }.toTypedArray())
            !hasModeAccessibility() ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            FloatingSettings.gestureCamera(this) && !Settings.canDrawOverlays(this) ->
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + packageName)))
            else -> startGlobalMode()
        }
    }

    private fun refreshSetupGuide() {
        if (!::setupGuide.isInitialized) return
        val gestures = FloatingSettings.gestureCamera(this)
        val transfers = FloatingSettings.nearby(this)
        val cameraOk = permissionGranted(Manifest.permission.CAMERA)
        val nearbyOk = nearbyGranted()
        val accessOk = hasAccessibilityPermission()
        val overlayOk = Settings.canDrawOverlays(this)
        val notifyOk = Build.VERSION.SDK_INT < 33 ||
            permissionGranted(Manifest.permission.POST_NOTIFICATIONS)
        val required = listOf(
            !gestures || cameraOk,
            !transfers || nearbyOk,
            !gestures || accessOk,
            !gestures || overlayOk
        )
        val done = required.count { it }
        setupGuide.visibility = if (done == 4) View.GONE else View.VISIBLE
        setupTitle.text = "初次使用設定 · $done／4 項完成"
        cameraStep.visibility = if (gestures) View.VISIBLE else View.GONE
        nearbyStep.visibility = if (transfers) View.VISIBLE else View.GONE
        accessibilityStep.visibility = if (gestures) View.VISIBLE else View.GONE
        overlayStep.visibility = if (gestures) View.VISIBLE else View.GONE
        cameraStep.text = (if(cameraOk) "✓ 已允許" else "① 點此開啟") +
            "｜相機：辨識手掌與手勢"
        nearbyStep.text = (if(nearbyOk) "✓ 已允許" else "② 點此開啟") +
            "｜附近裝置：配對及傳送檔案"
        accessibilityStep.text = (if(accessOk) "✓ 已啟用" else "③ 點此開啟") +
            "｜無障礙服務：模擬滑動、點擊與分享"
        overlayStep.text = (if(overlayOk) "✓ 已允許" else "④ 點此開啟") +
            "｜懸浮顯示：手勢提示及接收狀態"
        notificationStep.text = (if(notifyOk) "✓ 已允許" else "建議開啟") +
            "｜通知：背景傳檔及收到網址提醒"
        cameraStep.isEnabled = !cameraOk
        nearbyStep.isEnabled = !nearbyOk
        accessibilityStep.isEnabled = !accessOk
        overlayStep.isEnabled = !overlayOk
        notificationStep.isEnabled = !notifyOk
    }

    private fun refreshHome() {
        if (!::status.isInitialized || !::primaryAction.isInitialized) return
        if (::gestureSwitch.isInitialized && ::transferSwitch.isInitialized) {
            updatingModeControls = true
            gestureSwitch.isChecked = FloatingSettings.gestureCamera(this)
            transferSwitch.isChecked = FloatingSettings.nearby(this)
            if (::subtitleSwitch.isInitialized) {
                subtitleSwitch.isChecked = SubtitleCaptureService.running || subtitlePendingPermission
                subtitleLanguage.isEnabled = !SubtitleCaptureService.running && !subtitlePendingPermission
            }
            updatingModeControls = false
            if (::voiceFallbackSwitch.isInitialized) {
                voiceFallbackSwitch.isEnabled =
                    getSharedPreferences("floating_subtitle", MODE_PRIVATE)
                        .getString("source_language", "en") == "en"
            }
            modeDescription.text = modeLabel() +
                (if (GlobalGestureService.isActive) " · 背景服務執行中" else " · 尚未啟動") +
                (if (SubtitleCaptureService.running) " · OCR 翻譯中" else "")
        }
        refreshSetupGuide()
        val d = tw.airgesture.transferbeta.core.HomeScreenPolicy.decide(
            hasModePermissions(), hasModeAccessibility(),
            GlobalGestureService.isActive,
            GlobalGestureService.transfer?.isConnected == true
        )
        val overlayMissing = FloatingSettings.gestureCamera(this) &&
            hasModePermissions() && hasModeAccessibility() &&
            !Settings.canDrawOverlays(this)
        val label = if (overlayMissing) "開啟手勢與接收提示權限" else when (d.action) {
            tw.airgesture.transferbeta.core.HomeAction.GRANT_PERMISSION -> "開始設定 · 允許必要權限"
            tw.airgesture.transferbeta.core.HomeAction.ENABLE_ACCESSIBILITY -> "繼續設定 · 啟用懸浮操作"
            tw.airgesture.transferbeta.core.HomeAction.START -> "啟用懸浮操作"
            tw.airgesture.transferbeta.core.HomeAction.WAIT -> "返回桌面 · 操作已啟用"
            tw.airgesture.transferbeta.core.HomeAction.BACKGROUND -> "返回桌面 · 開始使用"
        }
        primaryAction.text = label
        titleHint.text = if (overlayMissing)
            "尚未允許懸浮顯示：看不到已偵測手、張掌、握拳和接收通知"
        else when (d.state) {
            tw.airgesture.transferbeta.core.HomeState.NEED_PERMISSION -> "尚差一步：開啟權限"
            tw.airgesture.transferbeta.core.HomeState.NEED_ACCESSIBILITY -> "尚差一步：開啟懸浮操作服務"
            tw.airgesture.transferbeta.core.HomeState.NEED_START -> "設定已完成，按下方啟用"
            tw.airgesture.transferbeta.core.HomeState.SEARCHING -> "隔空操作已啟用；傳檔正在搜尋附近手機"
            tw.airgesture.transferbeta.core.HomeState.READY -> "隔空操作已啟用 · 傳檔裝置已連線"
        }
        if (GlobalGestureService.isActive) {
            titleHint.text = when {
                !FloatingSettings.gestureCamera(this) && FloatingSettings.nearby(this) ->
                    "純傳檔待命 · 前鏡頭已關閉"
                FloatingSettings.gestureCamera(this) && !FloatingSettings.nearby(this) ->
                    "手勢操作已啟用 · 附近搜尋已關閉"
                !FloatingSettings.gestureCamera(this) && !FloatingSettings.nearby(this) ->
                    "已暫停前鏡頭及附近搜尋"
                else -> titleHint.text
            }
        }
        titleHint.setTextColor(if (d.state ==
                tw.airgesture.transferbeta.core.HomeState.READY) 0xff0e8053.toInt()
            else 0xff1f3550.toInt())
        if (d.state == tw.airgesture.transferbeta.core.HomeState.SEARCHING) {
            status.text = GlobalGestureService.transfer?.status ?: "搜尋附近手機中"
        }
    }

    override fun onResume() {
        super.onResume()
        if (subtitleNeedsOverlay) {
            subtitleNeedsOverlay = false
            if (Settings.canDrawOverlays(this)) {
                main.post { requestSubtitleScreenCapture() }
            } else {
                subtitlePendingPermission = false
                flash("需要懸浮顯示權限才能顯示翻譯字幕")
            }
        }
        refreshHome()
        if (homePrefs.getBoolean("auto_run", false) &&
            hasModePermissions() && hasModeAccessibility() && !GlobalGestureService.isActive) {
            startGlobalMode()
        }
        attachBackgroundSession()
        maybeReturnHome()
    }

    override fun onPause() {
        GlobalGestureService.transfer?.let {
            it.onStatus = null
            it.onPairing = null
            it.onConnectionState = null
        }
        super.onPause()
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        LegacyUpdateCleanup.run(this)
        if (intent?.action == Intent.ACTION_SEND) {
            // Legacy pinned Sharesheet targets may explicitly launch this Activity.
            // Forward straight to the UI-free intake rather than showing the home.
            forwardLegacyShare(intent)
            finish()
            return
        }
        normalLauncherEntry = intent?.action == Intent.ACTION_MAIN &&
            intent?.getBooleanExtra("airgrab_show_settings", false) != true
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 40)
            setBackgroundColor(0xfff7f9fc.toInt())
        }
        fun text(value: String, size: Float = 17f) = TextView(this).apply {
            this.text = value
            textSize = size
            setTextColor(0xff1f3550.toInt())
            setPadding(0, 10, 0, 10)
        }
        root.addView(text("懸浮操作", 28f))
        root.addView(text("目前版本：v" + BuildConfig.VERSION_NAME +
            "（版本代碼 " + BuildConfig.VERSION_CODE + "）", 14f))
        root.addView(text("免觸控操作、隔空抓取，一次啟用即可使用。", 15f))
        val modePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 16, 20, 20)
            background = GradientDrawable().apply {
                cornerRadius = 22f
                setColor(0xffe8f1fb.toInt())
                setStroke(2, 0xffb8cff1.toInt())
            }
        }
        modePanel.addView(text("功能獨立開關", 20f))
        fun modeRow(label: String, description: String, toggle: Switch): LinearLayout {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val wording = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            wording.addView(text(label, 17f))
            wording.addView(text(description, 13f))
            row.addView(wording, LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(toggle)
            return row
        }
        gestureSwitch = Switch(this).apply {
            isChecked = FloatingSettings.gestureCamera(this@TransferActivity)
            contentDescription = "前鏡頭手勢辨識"
            setOnCheckedChangeListener { _, checked ->
                if (updatingModeControls) return@setOnCheckedChangeListener
                if (checked && !permissionGranted(Manifest.permission.CAMERA)) {
                    updatingModeControls = true
                    isChecked = false
                    updatingModeControls = false
                    permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
                    return@setOnCheckedChangeListener
                }
                FloatingSettings.saveBool(this@TransferActivity,
                    "gesture_camera_enabled", checked)
                reloadLiveSettings()
                refreshHome()
            }
        }
        modePanel.addView(modeRow("前鏡頭手勢",
            "關閉後釋放前鏡頭，不影響附近傳檔", gestureSwitch))
        transferSwitch = Switch(this).apply {
            isChecked = FloatingSettings.nearby(this@TransferActivity)
            contentDescription = "附近裝置傳檔"
            setOnCheckedChangeListener { _, checked ->
                if (updatingModeControls) return@setOnCheckedChangeListener
                if (checked && !nearbyGranted()) {
                    updatingModeControls = true
                    isChecked = false
                    updatingModeControls = false
                    val missing = nearbyPermissions().filterNot { permissionGranted(it) }
                    if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
                    return@setOnCheckedChangeListener
                }
                FloatingSettings.saveBool(this@TransferActivity,
                    "nearby_enabled", checked)
                reloadLiveSettings()
                refreshHome()
            }
        }
        modePanel.addView(modeRow("附近裝置傳檔",
            "亮屏搜尋、傳送和接收；熄屏暫停搜尋", transferSwitch))
        subtitleSwitch = Switch(this).apply {
            isChecked = SubtitleCaptureService.running
            contentDescription = "懸浮翻譯開關"
            setOnCheckedChangeListener { _, checked ->
                if (updatingModeControls) return@setOnCheckedChangeListener
                if (checked) beginSubtitleCapture() else stopSubtitleCapture()
                refreshHome()
            }
        }
        modePanel.addView(modeRow("懸浮翻譯",
            "OCR 翻譯影片現有外語字幕；無字幕語音翻譯將在後續版本加入",
            subtitleSwitch))
        modePanel.addView(text("字幕來源語言（切換語言需先關閉翻譯）", 13f))
        subtitleLanguage = Spinner(this).apply {
            adapter = ArrayAdapter(this@TransferActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("英文字幕", "日文字幕", "韓文字幕"))
            val selected = getSharedPreferences("floating_subtitle", MODE_PRIVATE)
                .getString("source_language", "en")
            setSelection(when (selected) { "ja" -> 1; "ko" -> 2; else -> 0 })
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?,
                    position: Int, id: Long) {
                    if (SubtitleCaptureService.running || subtitlePendingPermission) return
                    val lang = arrayOf("en", "ja", "ko")[position.coerceIn(0, 2)]
                    getSharedPreferences("floating_subtitle", MODE_PRIVATE)
                        .edit().putString("source_language", lang).apply()
                    if (::voiceFallbackSwitch.isInitialized) {
                        voiceFallbackSwitch.isEnabled = (lang == "en")
                        if (lang != "en") {
                            updatingModeControls = true
                            voiceFallbackSwitch.isChecked = false
                            updatingModeControls = false
                        }
                    }
                }
            }
        }
        modePanel.addView(subtitleLanguage)
        voiceFallbackSwitch = Switch(this).apply {
            isChecked = getSharedPreferences("floating_subtitle", MODE_PRIVATE)
                .getBoolean("enable_voice_fallback_en", false)
            contentDescription = "英文語音補位開關"
            setOnCheckedChangeListener { _, checked ->
                if (updatingModeControls) return@setOnCheckedChangeListener
                val prefs = getSharedPreferences("floating_subtitle", MODE_PRIVATE)
                if (checked && prefs.getString("source_language", "en") != "en") {
                    updatingModeControls = true
                    isChecked = false
                    updatingModeControls = false
                    flash("語音補位第一版只支援英文；日韓文繼續用 OCR")
                    return@setOnCheckedChangeListener
                }
                if (checked && ContextCompat.checkSelfPermission(
                        this@TransferActivity, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                    updatingModeControls = true
                    isChecked = false
                    updatingModeControls = false
                    voiceAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    return@setOnCheckedChangeListener
                }
                prefs.edit().putBoolean("enable_voice_fallback_en", checked).apply()
                if (SubtitleCaptureService.running)
                    flash("請先關閉再重新開啟翻譯，套用語音補位設定")
            }
        }
        modePanel.addView(modeRow(
            "英文語音補位（實驗版）",
            "OCR 來不及更新時以影片播放聲音補充。首次下載約40MB英文模型，會增加耗電；若播放器禁止內部音訊擷取則無法使用。",
            voiceFallbackSwitch))
        modePanel.addView(text("字幕辨識位置：啟動後直接在影片上拖曳綠框、拉四角調整大小。直向、橫向分別記住上次的位置。", 13f))
        modePanel.addView(Button(this).apply {
            text = "重新框選字幕範圍"
            isAllCaps = false
            setOnClickListener {
                if (SubtitleCaptureService.running) {
                    startService(Intent(this@TransferActivity, SubtitleCaptureService::class.java)
                        .setAction(SubtitleCaptureService.ACTION_EDIT))
                    main.postDelayed({ moveTaskToBack(true) }, 280L)
                } else {
                    flash("請先開啟懸浮翻譯，再到影片上框選字幕")
                }
            }
        })
        modePanel.addView(Button(this).apply {
            text = "查看字幕辨識診斷（原文／翻譯）"
            isAllCaps = false
            setOnClickListener {
                AlertDialog.Builder(this@TransferActivity)
                    .setTitle("本機字幕辨識診斷")
                    .setMessage(
                        "掃描方式：${SubtitleCaptureService.lastScanMode}" +
                        "\\n\\nOCR 原文：\\n${SubtitleCaptureService.lastRecognizedSource}" +
                        "\\n\\n翻譯結果：\\n${SubtitleCaptureService.lastTranslationResult}" +
                        "\\n\\n翻譯品質：${SubtitleCaptureService.lastTranslationQuality}" +
                        "\\n\\n字幕選取：${SubtitleCaptureService.lastSelectionStatus}" +
                        "\\n\\nOCR 候選：${SubtitleCaptureService.lastCandidateSummary}" +
                        "\\n\\n語音補位：${SubtitleCaptureService.lastVoiceStatus}" +
                        "\\n\\n語音原文：${SubtitleCaptureService.lastSpokenOriginal}" +
                        "\\n\\n只保存在記憶體中，沒有上傳畫面。"
                    )
                    .setPositiveButton("關閉", null).show()
            }
        })
        modeDescription = text(modeLabel(), 14f)
        modePanel.addView(modeDescription)
        root.addView(modePanel)
        setupGuide = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 14, 20, 18)
            background = GradientDrawable().apply {
                setColor(0xffedf4ff.toInt())
                cornerRadius = 22f
                setStroke(2, 0xffb8cff1.toInt())
            }
        }
        setupTitle = text("初次使用設定", 19f)
        setupGuide.addView(setupTitle)
        setupGuide.addView(text("依序點選下方未完成項目。系統設定完成後返回本 App，會自動重新檢查權限。", 14f))
        fun guideButton(): Button = Button(this).apply {
            isAllCaps = false
            textSize = 13f
            minHeight = (resources.displayMetrics.density * 57).toInt()
        }
        cameraStep = guideButton().apply { setOnClickListener {
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
        } }
        nearbyStep = guideButton().apply { setOnClickListener {
            permissionLauncher.launch(nearbyPermissions().filterNot { permissionGranted(it) }.toTypedArray())
        } }
        accessibilityStep = guideButton().apply { setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } }
        overlayStep = guideButton().apply { setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + packageName)))
        } }
        notificationStep = guideButton().apply { setOnClickListener {
            if(Build.VERSION.SDK_INT >= 33)
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } }
        listOf(cameraStep, nearbyStep, accessibilityStep, overlayStep, notificationStep)
            .forEach { setupGuide.addView(it) }
        setupGuide.addView(Button(this).apply {
            text = "權限按鈕無法開啟？前往本 App 的系統設定"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + packageName)))
            }
        })
        root.addView(setupGuide)
        titleHint = text("檢查服務狀態中…", 20f)
        root.addView(titleHint)
        status = text("等待連線", 14f)
        root.addView(status)
        primaryAction = Button(this).apply {
            text = "開始設定"
            minHeight = (resources.displayMetrics.density * 64).toInt()
            setOnClickListener {
                if (FloatingSettings.gestureCamera(this@TransferActivity) &&
                    hasModePermissions() && hasModeAccessibility() &&
                    !Settings.canDrawOverlays(this@TransferActivity)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + packageName)))
                    return@setOnClickListener
                }
                when (tw.airgesture.transferbeta.core.HomeScreenPolicy.decide(
                    hasModePermissions(), hasModeAccessibility(),
                    GlobalGestureService.isActive,
                    GlobalGestureService.transfer?.isConnected == true
                ).action) {
                    tw.airgesture.transferbeta.core.HomeAction.GRANT_PERMISSION ->
                        requestNextPermission()
                    tw.airgesture.transferbeta.core.HomeAction.ENABLE_ACCESSIBILITY ->
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    tw.airgesture.transferbeta.core.HomeAction.START -> startGlobalMode()
                    tw.airgesture.transferbeta.core.HomeAction.WAIT ->
                        GlobalGestureService.transfer?.refresh()
                    tw.airgesture.transferbeta.core.HomeAction.BACKGROUND ->
                        moveTaskToBack(true)
                }
            }
        }
        root.addView(primaryAction)
        root.addView(Button(this).apply {
            text = "手勢操作測試區 · 實際測試滑動和點擊"
            setOnClickListener {
                if (!GlobalGestureService.isActive ||
                    !FloatingSettings.gestureCamera(this@TransferActivity) ||
                    !hasAccessibilityPermission()) {
                    AlertDialog.Builder(this@TransferActivity)
                        .setMessage("請先完成權限設定並啟動懸浮操作，再進行手勢測試。")
                        .setPositiveButton("知道了", null).show()
                } else startActivity(Intent(this@TransferActivity, GestureTestActivity::class.java))
            }
        })
        root.addView(text("設定首頁會禁止手勢誤觸；想驗證滑動請開啟上方測試區。隔空抓取則請在相簿等其他 App 測試。", 13f))
        root.addView(text("滑動｜張掌上下揮動；向右揮＝上一頁；向左揮＝從最右側向左滑\n點擊｜直接握拳保持 1.5 秒（YouTube 播放／暫停）\n游標｜伸食指移動，停留 2 秒點擊\n傳送｜張掌→握拳抓取；收到分享後握拳→張掌接收", 15f))
        root.addView(text("整天待命：亮屏無手自動降頻，熄屏完全釋放前鏡頭；玩遊戲時也會自動暫停，離開遊戲後恢復。\n隔空操作無須附近手機；只有傳檔需要配對。", 13f))
        val advancedToggle = Button(this).apply { text = "進階設定與測試  ▾" }
        root.addView(advancedToggle)
        advanced = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        fun extraButton(label: String, click: () -> Unit) {
            advanced.addView(Button(this).apply {
                text = label
                setOnClickListener { click() }
            })
        }
        extraButton("手勢停留點擊時間") {
            val items = arrayOf("1 秒（快速）", "2 秒（預設）", "3 秒（防誤觸）")
            AlertDialog.Builder(this).setTitle("食指停留點擊")
                .setSingleChoiceItems(items, FloatingSettings.dwellIndex(this)) { d, value ->
                    FloatingSettings.saveInt(this, "dwell", value)
                    reloadLiveSettings()
                    d.dismiss()
                }.setNegativeButton("取消", null).show()
        }
        extraButton("食指游標活動範圍") {
            val items = arrayOf("1 倍（精細）", "2 倍", "3 倍（預設）", "4 倍（大範圍）")
            AlertDialog.Builder(this).setTitle("游標活動範圍")
                .setSingleChoiceItems(items, FloatingSettings.range(this)) { d, value ->
                    FloatingSettings.saveInt(this, "cursor_range", value)
                    reloadLiveSettings()
                    d.dismiss()
                }.setNegativeButton("取消", null).show()
        }
        extraButton("張掌滑動靈敏度") {
            val items = arrayOf("低（防誤觸）", "標準", "高")
            AlertDialog.Builder(this).setTitle("張掌滑動靈敏度")
                .setSingleChoiceItems(items, FloatingSettings.sensitivity(this)) { d, value ->
                    FloatingSettings.saveInt(this, "swipe_sensitivity", value)
                    reloadLiveSettings()
                    d.dismiss()
                }.setNegativeButton("取消", null).show()
        }
        extraButton("全天省電／辨識反應模式") {
            val items = arrayOf("省電優先（偵測較慢）", "智慧平衡（預設）", "反應優先（較耗電）")
            AlertDialog.Builder(this).setTitle("全天運作效能")
                .setSingleChoiceItems(items, FloatingSettings.power(this)) { d, value ->
                    FloatingSettings.saveInt(this, "power_mode", value)
                    reloadLiveSettings()
                    d.dismiss()
                }.setNegativeButton("取消", null).show()
        }
        extraButton("YouTube Shorts 播放／暫停方式") {
            val items = arrayOf(
                "自動辨識（預設）：Shorts 點一下畫面、一般影片用媒體鍵",
                "短影音點按：在 YouTube 一律點一下影片畫面",
                "僅媒體鍵：YouTube 一律用播放／暫停鍵"
            )
            AlertDialog.Builder(this).setTitle("YouTube 播放／暫停")
                .setSingleChoiceItems(items, FloatingSettings.youtubeShortsMode(this)) { d, value ->
                    FloatingSettings.saveInt(this, "youtube_shorts_mode", value)
                    flash("已設定 YouTube 播放控制： " +
                        when (value) {
                            1 -> "畫面單擊（Shorts 測試用）"
                            2 -> "僅媒體鍵"
                            else -> "自動判斷 Shorts／一般影片"
                        }, true)
                    d.dismiss()
                }.setNegativeButton("取消", null).show()
        }
        extraButton("操作方向確認動畫（開／關，預設開啟）") {
            val newValue = !FloatingSettings.actionFeedback(this)
            FloatingSettings.saveBool(this, "action_feedback_enabled", newValue)
            reloadLiveSettings()
            flash(if (newValue) "已開啟操作確認動畫：成功送出手勢時短暫提示方向"
                else "已關閉操作確認動畫（手勢仍可正常使用）", true)
        }
        extraButton("張掌手掌位置圓圈／軌跡（開／關，預設開啟）") {
            val value = !FloatingSettings.palmPositionEnabled(this)
            FloatingSettings.saveBool(this, "palm_position_enabled", value)
            reloadLiveSettings()
            flash(if (value) "已啟用張掌位置圓圈及移動軌跡"
                else "已關閉張掌圓圈與軌跡，手勢操作維持不變", true)
        }
        extraButton("查看版本更新紀錄／更新進度") {
            val versionLog = """
                v1.0.22｜碰一碰傳送前重建 NFC 讀取模式，12 秒未感應自動重試一次，提供手動重試與本機診斷；傳送端／接收端碰觸時若 Nearby 未連線則自動搜尋信任裝置；熄屏省電、解鎖後快速重連，斷線限頻重搜；原有驗證、手勢與逐檔傳輸不變
                v1.0.21｜NFC 碰一碰支援一次選擇 2～32 個原始照片、影片或文件；一次 NFC 驗證指定一台信任裝置，Nearby 逐檔無損傳送並逐檔 SHA-256 核對，接收端自動接受；一般隔空抓取、多檔及網址傳送保持不變
                v1.0.20｜依實機結果移除「不按分享就碰觸傳送」和 NFC 網頁接收待命實驗；保留手動「分享 → 懸浮操作 · NFC 碰一碰」發送單張照片、影片、文件或有效網址，Nearby 單一指定接收者自動接收
                v1.0.19｜曾嘗試免按分享的 NFC 網頁碰觸（已在 v1.0.20 移除）
                v1.0.18｜NFC 背景網頁實驗原型（已移除）
                v1.0.17｜修正三星相簿在「懸浮操作／NFC 碰一碰」雙分享入口的二次選單卡住；手勢抓取自動選一般懸浮操作，NFC 入口保留手動選擇
                v1.0.16｜三星相簿分享新增 NFC 碰一碰獨立選項；NFC 一次性驗證綁定已配對 Nearby 裝置，只傳給碰觸對象；成功驗證後自動接收原檔（需兩端背景傳檔運作）
                v1.0.15｜修正 S26 Ultra 前鏡頭未鏡像影像的左右手正反判斷：掌心可起滑、手背不可起滑；不影響傳檔與握拳\n                v1.0.14｜首頁獨立控制前鏡頭手勢／Nearby 傳檔；純傳檔模式不用開相機或無障礙服務；解鎖後恢復附近搜尋；通知顯示模式
                v1.0.13｜掌心朝向前鏡頭才可起滑／重新起滑；手背不能啟動滑動；右側提示可起滑狀態；握拳、抓取與接收維持原樣
                v1.0.12｜上滑判定恢復 v1.0.10；張掌顯示手掌位置圓圈與軌跡；減少幽靈手提示、加快提示消失
                v1.0.11｜縮短已解鎖狀態下的鏡頭啟動等待、改善首次抬手辨識與下緣起手上滑；左滑避開三星返回；移除自動更新
                v1.0.10｜提升快速張掌滑動的追蹤連續性與方向判斷
                v1.0.9｜張掌左滑、辨識提示靠右、逐檔無損傳送、遊戲自動暫停、辨識加速、握拳 1.5 秒
                v1.0.8｜隔空操作及隔空抓取整合、原有單檔及多人接收
            """.trimIndent()
            AlertDialog.Builder(this).setTitle("懸浮操作 v" + BuildConfig.VERSION_NAME)
                .setMessage(versionLog +
                    "\n\n更新方式：不再自動檢查或下載 APK。需手動取得同簽章的新版本安裝更新。" +
                    "\n進度說明：此紀錄隨 APK 版本更新，不會連線查詢 GitHub。")
                .setPositiveButton("完成", null).show()
        }
        extraButton("玩遊戲時自動暫停隔空操作（開／關）") {
            val enabled = !FloatingSettings.pauseInGames(this)
            FloatingSettings.saveBool(this, "pause_in_games", enabled)
            reloadLiveSettings()
            flash(if (enabled)
                "已啟用：進入遊戲自動釋放前鏡頭，離開後恢復"
                else "已停用：玩遊戲時也會繼續辨識手勢", true)
        }
        // Nearby is controlled by the independent homepage switch.
        extraButton("NFC 碰一碰 · 技術驗證（不發送照片）") {
            startActivity(Intent(this, NfcProbeActivity::class.java))
        }
        extraButton("手動選擇照片或檔案發送") { picker.launch(arrayOf("*/*")) }
        val videoModeButton = Button(this).apply {
            fun showMode() {
                val mode = VideoSendMode.fromKey(
                    getSharedPreferences("video_transfer", MODE_PRIVATE)
                        .getString("mode", "auto"))
                text = "影片模式：" + mode.display
            }
            showMode()
            setOnClickListener {
                val modes = VideoSendMode.entries
                val selected = VideoSendMode.fromKey(
                    getSharedPreferences("video_transfer", MODE_PRIVATE)
                        .getString("mode", "auto"))
                AlertDialog.Builder(this@TransferActivity)
                    .setTitle("AirGrab 影片傳送模式")
                    .setSingleChoiceItems(modes.map { it.display }.toTypedArray(),
                        modes.indexOf(selected)) { dialog, index ->
                        getSharedPreferences("video_transfer", MODE_PRIVATE)
                            .edit().putString("mode", modes[index].key).apply()
                        showMode()
                        dialog.dismiss()
                    }
                    .setNegativeButton("取消", null).show()
            }
        }
        advanced.addView(videoModeButton)
        advanced.addView(text(
            "自動模式：接收端不支援 HEVC／HDR 或高解析度影片時，發送端轉成相容 H.264／MP4。" +
                "轉檔需要等待，但不會更改手機裡的原始影片。", 13f))

        extraButton("測試接收（不需要做手勢）") {
            GlobalGestureService.transfer?.confirmReceive()
                ?: flash("請先啟動隔空抓取服務")
        }
        extraButton("重新搜尋裝置") { GlobalGestureService.transfer?.refresh() }
        extraButton("已配對裝置與多台連線") {
            val current = GlobalGestureService.transfer
            if (current == null) {
                flash("請先啟動 AirGrab 才能管理信任裝置")
            } else {
                val saved = current.rememberedDeviceIds
                val live = current.connectedDeviceIds.toSet()
                val names = current.connectedDeviceNames
                val summary = if (saved.isEmpty()) "尚無已配對裝置，首次連線須核對雙方數字。"
                    else saved.mapIndexed { index, id ->
                        (index + 1).toString() + ". " +
                            (names[id]?.let { "$it ($id)" } ?: id) +
                            (if (id in live) "（已連線）" else "（未連線）")
                    }.joinToString("\n")
                AlertDialog.Builder(this)
                    .setTitle("AirGrab 多裝置：已連線 " + live.size + " 台")
                    .setMessage(summary +
                        "\n\n傳送端抓取後開放接收 30 秒，已配對設備可各自握拳→張掌加入。" +
                        "\n最多記住 8 台裝置。清除配對會讓所有裝置下次重新核對數字。")
                    .setPositiveButton("保留配對", null)
                    .setNegativeButton("清除全部配對") { _, _ ->
                        current.forgetTrustedDevice()
                        refreshHome()
                    }.show()
            }
        }
        extraButton("無障礙服務設定") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        extraButton("懸浮動畫權限") {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + packageName)))
            } else flash("已允許懸浮動畫", true)
        }
        extraButton("辨識與操作診斷") {
            val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val batteryTenths = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            val batteryReading = if (batteryTenths >= 0)
                String.format(java.util.Locale.TAIWAN, "%.1f °C", batteryTenths / 10.0)
            else "無法讀取"
            AlertDialog.Builder(this)
                .setTitle("懸浮操作 · 動作診斷")
                .setMessage(
                    "無障礙服務：" +
                        (if (GlobalGrabAccessibilityService.isConnected) "已連線" else "未連線，請重新啟用") +
                    "\n最近辨識：" + GlobalGestureService.lastPose +
                    "\n已發出指令：" + GlobalGestureService.actionCount + " 次" +
                    "\n最後指令：" + GlobalGestureService.lastGestureAction +
                    "\n操作結果：" + GlobalGrabAccessibilityService.lastControlStatus +
                    "\n鏡頭影格：" + GlobalGestureService.framesSeen +
                    "\nAI辨識：" + GlobalGestureService.inferences +
                    "\n鏡頭 FPS：" + GlobalGestureService.requestedSensorFps +
                    "\n電池感測溫度：" + batteryReading +
                    "\n辨識異常：" + GlobalGestureService.lastRecognitionError +
                    "\n附近裝置連線：" +
                        (if (GlobalGestureService.transfer?.isConnected == true) "已連線" else "尚未連線") +
                    "\n注意：設定首頁禁止手勢，請到手勢操作測試區檢查"
                ).setPositiveButton("完成", null).show()
        }
        extraButton("停止懸浮操作") {
            homePrefs.edit().putBoolean("auto_run", false).apply()
            stopService(Intent(this, GlobalGestureService::class.java))
            status.text = "使用者已停止背景服務"
            main.postDelayed({ refreshHome() }, 250)
        }
        chosen = text("尚未選擇測試檔案", 14f)
        advanced.addView(chosen)
        hand = text("舊版 App 內手勢測試已停用；由全域服務辨識", 13f)
        advanced.addView(hand)
        animation = text("測試訊息", 15f).apply { gravity = Gravity.CENTER }
        advanced.addView(animation)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        advanced.addView(progress)
        root.addView(advanced)
        advancedToggle.setOnClickListener {
            advancedOpen = !advancedOpen
            advanced.visibility = if (advancedOpen) View.VISIBLE else View.GONE
            advancedToggle.text = if (advancedOpen) "收起進階設定  ▴" else "進階設定與測試  ▾"
        }
        scroll.addView(root)
        setContentView(scroll)
        enhancedMode = getSharedPreferences("gesture", MODE_PRIVATE).getBoolean("enhanced", true)
        if (!GlobalGestureService.isActive) {
            GlobalGrabBridge.onGrabResult = { result ->
                main.post { consumeGlobalGrab(result) }
            }
            GlobalGrabBridge.onReceiveGesture = { main.post { receiveGesture() } }
        }
        refreshHome()
        // External shares are handled exclusively by the transparent ShareReceiverActivity.
        // The camera and Nearby are now exclusively owned by GlobalGestureService.
        // Do not start activity-local camera/discovery on every app launch.
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == Intent.ACTION_SEND) {
            forwardLegacyShare(intent)
            finish()
            return
        }
        normalLauncherEntry = intent.action == Intent.ACTION_MAIN &&
            !intent.getBooleanExtra("airgrab_show_settings", false)
    }

    private fun forwardLegacyShare(shared: Intent) {
        val routed = Intent(shared).setClass(this, ShareReceiverActivity::class.java)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(routed)
    }

    private fun consumeGlobalGrab(result: GrabResult) {
        if (!connected) { flash("目前無法抓取：請先完成雙手機配對"); return }
        when (result) {
            is GrabResult.Link -> sendLink(result.url)
            is GrabResult.ShareStarted -> flash(result.detail, true)
            is GrabResult.ScreenSnapshot -> {
                flash("已擷取畫面截圖，正在準備傳送（不是原始照片）", true)
                io.execute {
                    try {
                        val digest = sha256(result.file)
                        main.post {
                            file?.delete()
                            file = result.file
                            fileName = "screen-grab-" + System.currentTimeMillis() + ".png"
                            fileHash = digest
                            chosen.text = "畫面截圖（不是原檔）：" + fileName
                            sendGesture()
                        }
                    } catch (e: Exception) {
                        main.post { flash("畫面截圖處理失敗：" + e.message) }
                    }
                }
            }
            is GrabResult.Unavailable -> flash(result.reason)
        }
    }

    private fun sendLink(url: String) {
        if (GlobalGestureService.isActive) {
            GlobalGestureService.transfer?.offerLink(url)
            return
        }
        val checked = GrabContentPolicy.safeWebUrl(url)
            ?: run { flash("不支援此網址，已取消抓取"); return }
        if (!connected || sending || receiving || offer != null || linkOffer != null) {
            flash("尚未連線或已有傳輸請求"); return
        }
        session = UUID.randomUUID().toString()
        sending = true
        control(JSONObject().put("type", "link_offer").put("sid", session).put("url", checked))
        flash("✓ 已抓取影片／網頁網址，等待另一支手機接收", true)
        val key = session
        main.postDelayed({
            if (sending && session == key) {
                sending = false; flash("網址接收等待逾時")
            }
        }, 30_000)
    }

    private fun openLink(url: String) {
        val checked = GrabContentPolicy.safeWebUrl(url)
            ?: run { flash("網址無效，已拒絕開啟"); return }
        try {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(checked)))
                flash("正在開啟已收到的網址", true)
            } else {
                val notifications = getSystemService(NotificationManager::class.java)
                notifications.createNotificationChannel(NotificationChannel(
                    "link_received", "隔空傳檔網址", NotificationManager.IMPORTANCE_DEFAULT))
                val openIntent = Intent(Intent.ACTION_VIEW, Uri.parse(checked)).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK)
                val pending = PendingIntent.getActivity(
                    this, 0, openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val notification = Notification.Builder(this, "link_received")
                    .setSmallIcon(android.R.drawable.ic_menu_view)
                    .setContentTitle("隔空傳檔：已收到網址")
                    .setContentText("點擊開啟影片或網頁")
                    .setAutoCancel(true)
                    .setContentIntent(pending).build()
                notifications.notify(4201, notification)
                flash("已收到連結；請點通知開啟（系統限制背景自動跳轉）", true)
            }
        } catch (e: Exception) { flash("無法開啟連結：" + e.message) }
    }

    private fun reloadLiveSettings() {
        if(GlobalGestureService.isActive) startService(
            Intent(this, GlobalGestureService::class.java)
                .setAction(GlobalGestureService.ACTION_SETTINGS_CHANGED))
    }

    private fun permissions(): Array<String> {
        val out = mutableListOf<String>()
        if (FloatingSettings.gestureCamera(this)) out += Manifest.permission.CAMERA
        if (FloatingSettings.nearby(this)) {
            out += Manifest.permission.ACCESS_FINE_LOCATION
            if (Build.VERSION.SDK_INT >= 31) {
                out += Manifest.permission.BLUETOOTH_SCAN
                out += Manifest.permission.BLUETOOTH_ADVERTISE
                out += Manifest.permission.BLUETOOTH_CONNECT
            }
            if (Build.VERSION.SDK_INT >= 33) out += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        return out.toTypedArray()
    }
    private fun hasPermissions() = permissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }
    private fun show(msg: String) = main.post { status.text = msg }
    private fun flash(msg: String, success: Boolean = false) = main.post {
        animation.text = msg
        animation.setBackgroundColor(if (success) 0xffdaf4e3.toInt() else 0xffe5edf8.toInt())
        animation.animate().cancel()
        animation.alpha = 0.5f; animation.scaleX = 0.92f; animation.scaleY = 0.92f
        animation.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(350).start()
    }
    private fun safeName(n: String) = n.substringAfterLast('/').substringAfterLast('\\')
        .replace(Regex("[\\x00-\\x1f]"), "_").take(120).ifEmpty { "document.bin" }
    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buffer = ByteArray(131072)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                md.update(buffer, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
    private fun loadFile(uri: Uri) {
        chosen.text = "讀取檔案中…"
        io.execute {
            try {
                val raw = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "document.bin"
                val target = File.createTempFile("original-", ".bin", File(cacheDir, "send").apply { mkdirs() })
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output) }
                } ?: error("無法讀取原檔")
                check(target.length() <= 256L*1024*1024) { "測試版單檔最多 256MB" }
                val hash = sha256(target)
                main.post {
                    file?.delete()
                    file = target; fileName = safeName(raw); fileHash = hash
                    chosen.text = "原檔：" + fileName + "（" + target.length() + " bytes）"
                    if (GlobalGestureService.isActive) {
                        GlobalGestureService.transfer?.offerFile(target, fileName)
                    } else flash("原檔準備完成", true)
                }
            } catch (e: Exception) { show("選檔失敗：" + e.message) }
        }
    }

    private fun findPeers() {
        if (!hasPermissions()) { permissionLauncher.launch(permissions()); return }
        if (GlobalGestureService.isActive) {
            GlobalGestureService.transfer?.refresh()
            return
        }
        if (sending || receiving) { flash("傳輸中不能重新配對"); return }
        nearby.stopAllEndpoints(); nearby.stopDiscovery(); nearby.stopAdvertising()
        endpoint = null; connected = false
        val name = "Beta|" + deviceId
        val strategy = Strategy.P2P_POINT_TO_POINT
        nearby.startAdvertising(name, service, connections,
            AdvertisingOptions.Builder().setStrategy(strategy).build())
            .addOnFailureListener { show("廣播失敗：" + it.message) }
        nearby.startDiscovery(service, discovery,
            DiscoveryOptions.Builder().setStrategy(strategy).build())
            .addOnFailureListener { show("搜尋失敗：" + it.message) }
        show("正在搜尋附近手機；本機代碼 " + deviceId)
    }
    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(id: String, info: DiscoveredEndpointInfo) {
            val other = info.endpointName.substringAfter('|', "")
            if (other.length == 12 && deviceId < other && endpoint == null && !connected) {
                endpoint = id
                nearby.requestConnection("Beta|" + deviceId, id, connections)
                    .addOnFailureListener { endpoint = null; show("連線要求失敗：" + it.message) }
            }
        }
        override fun onEndpointLost(endpointId: String) {}
    }
    private val connections = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(id: String, info: ConnectionInfo) {
            main.post {
                AlertDialog.Builder(this@TransferActivity)
                    .setTitle("安全配對驗證")
                    .setMessage("裝置：" + info.endpointName +
                        "\n請確認兩支手機顯示相同數字：\n\n" + info.authenticationDigits)
                    .setPositiveButton("數字相同，允許") { _, _ ->
                        endpoint = id
                        nearby.acceptConnection(id, payloads)
                            .addOnFailureListener { show("接受連線失敗：" + it.message) }
                    }
                    .setNegativeButton("取消") { _, _ ->
                        nearby.rejectConnection(id); endpoint = null
                    }.setCancelable(false).show()
            }
        }
        override fun onConnectionResult(id: String, r: ConnectionResolution) {
            if (r.status.isSuccess) {
                endpoint = id; connected = true
                nearby.stopAdvertising(); nearby.stopDiscovery()
                show("已安全連線，可以手勢傳送")
            } else {
                connected = false; endpoint = null
                show("連線失敗，請重新搜尋")
            }
        }
        override fun onDisconnected(id: String) {
            connected = false; endpoint = null; sending = false; receiving = false; offer = null
            show("連線中斷")
        }
    }

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(id: String, p: Payload) {
            main.post {
                if (id != endpoint) return@post
                when (p.type) {
                    Payload.Type.BYTES -> try {
                        onControl(JSONObject(String(p.asBytes() ?: return@post, Charsets.UTF_8)))
                    } catch (e: Exception) { show("控制訊息無效：" + e.message) }
                    Payload.Type.FILE -> {
                        if (!receiving || offer == null) { nearby.cancelPayload(p.id); return@post }
                        incoming = p; incomingId = p.id
                        flash("檔案接收中…")
                    }
                }
            }
        }
        override fun onPayloadTransferUpdate(id: String, update: PayloadTransferUpdate) {
            main.post {
                if (id != endpoint) return@post
                if (update.payloadId != incomingId && update.payloadId != outgoingId) return@post
                when (update.status) {
                    PayloadTransferUpdate.Status.IN_PROGRESS ->
                        if (update.totalBytes > 0) progress.progress =
                            ((update.bytesTransferred * 100 / update.totalBytes).toInt()).coerceIn(0, 100)
                    PayloadTransferUpdate.Status.SUCCESS ->
                        if (update.payloadId == incomingId) verifyReceived()
                        else show("傳送完畢，等待對方驗證原檔")
                    PayloadTransferUpdate.Status.FAILURE, PayloadTransferUpdate.Status.CANCELED -> {
                        sending = false; receiving = false; flash("傳輸中斷")
                    }
                }
            }
        }
    }
    private fun control(obj: JSONObject) {
        val id = endpoint ?: return
        nearby.sendPayload(id, Payload.fromBytes(obj.toString().toByteArray()))
            .addOnFailureListener { show("控制資料傳送失敗：" + it.message) }
    }
    private fun onControl(obj: JSONObject) {
        val sid = obj.optString("sid")
        when (obj.optString("type")) {
            "link_offer" -> {
                val checked = GrabContentPolicy.safeWebUrl(obj.optString("url"))
                if (!connected || sending || receiving || offer != null || linkOffer != null ||
                    sid.isBlank() || checked == null) {
                    control(JSONObject().put("type", "busy").put("sid", sid)); return
                }
                linkOffer = LinkOffer(sid, checked)
                val current = ++offerEpoch
                flash("收到網址：請先握拳再張掌，接收對方影片／網頁")
                main.postDelayed({
                    if (offerEpoch == current && linkOffer?.session == sid && !receiving) {
                        linkOffer = null; flash("網址接收等待逾時")
                    }
                }, 30_000)
            }
            "link_ready" -> if (sending && sid == session) {
                sending = false
                flash("✓ 接收端已確認網址", true)
            }
            "offer" -> {
                val name = obj.optString("name")
                val length = obj.optLong("size", -1)
                val hash = obj.optString("hash")
                if (sending || receiving || offer != null || sid.isBlank() || name.isBlank() ||
                    length < 0 || length > 256L*1024*1024 || !hash.matches(Regex("[0-9a-f]{64}"))) {
                    control(JSONObject().put("type", "busy").put("sid", sid)); return
                }
                offer = Offer(sid, safeName(name), length, hash)
                val check = ++offerEpoch
                flash("接收請求：" + safeName(name) + "\n請握拳→張掌接收")
                main.postDelayed({
                    if (check == offerEpoch && offer?.session == sid && !receiving) {
                        offer = null; flash("接收等待逾時")
                    }
                }, 30_000)
            }
            "ready" -> {
                if (!sending || sid != session || outgoingId != -1L) return
                try {
                    val outgoing = Payload.fromFile(file ?: return)
                    outgoingId = outgoing.id
                    nearby.sendPayload(endpoint ?: return, outgoing)
                        .addOnFailureListener { sending = false; flash("傳送啟動失敗：" + it.message) }
                    flash("正在傳送原始檔案…")
                } catch (e: Exception) { sending = false; flash("傳送失敗：" + e.message) }
            }
            "verified" -> if (sending && sid == session) {
                sending = false; progress.progress = 100
                flash("✓ 傳送成功\n檔案 SHA-256 驗證一致", true)
            }
            "bad" -> if (sid == session) { sending = false; flash("檔案驗證失敗") }
            "busy" -> if (sid == session) { sending = false; flash("接收端目前無法接收") }
        }
    }
    private fun sendGesture() {
        if (GlobalGestureService.isActive) {
            val selected = file
            if (selected != null) GlobalGestureService.transfer?.offerFile(selected, fileName)
            else flash("尚無可傳檔案")
            return
        }
        val toSend = file
        if (!connected) { flash("請先完成雙機配對"); return }
        if (toSend == null || !toSend.exists()) { flash("請先選擇原檔"); return }
        if (sending || receiving || offer != null) { flash("目前有其他傳輸進行中"); return }
        sending = true
        session = UUID.randomUUID().toString()
        outgoingId = -1L; progress.progress = 0
        control(JSONObject().put("type", "offer").put("sid", session)
            .put("name", fileName).put("size", toSend.length()).put("hash", fileHash))
        flash("✓ 已抓取！等待另一支手機接收（30 秒）", true)
        val key = session
        main.postDelayed({
            if (sending && session == key && outgoingId == -1L) {
                sending = false; flash("等待接收逾時")
            }
        }, 30_000)
    }
    private fun receiveGesture() {
        if (GlobalGestureService.isActive) {
            GlobalGestureService.transfer?.confirmReceive()
            return
        }
        if (!connected) { flash("請先完成雙機配對"); return }
        if (receiving || sending) return
        linkOffer?.let { pending ->
            lastReceivedLink = pending.url
            linkOffer = null
            offerEpoch++
            control(JSONObject().put("type", "link_ready").put("sid", pending.session))
            openLink(pending.url)
            return
        }
        val request = offer ?: run { flash("沒有等待接收的檔案或網址"); return }
        receiving = true; offerEpoch++; incomingId = -1L
        progress.progress = 0
        flash("✓ 已放下！接收中：" + request.name, true)
        control(JSONObject().put("type", "ready").put("sid", request.session))
    }
    private fun verifyReceived() {
        val p = incoming ?: return
        val meta = offer ?: return
        io.execute {
            var dest: Uri? = null
            try {
                val from = p.asFile()?.asUri() ?: error("無法開啟接收檔")
                val info = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, meta.name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                dest = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, info)
                    ?: error("無法建立下載檔")
                val hash = MessageDigest.getInstance("SHA-256")
                var bytes = 0L
                contentResolver.openInputStream(from)?.use { input ->
                    contentResolver.openOutputStream(dest!!, "w")?.use { output ->
                        val buffer = ByteArray(131072)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            hash.update(buffer, 0, count)
                            bytes += count
                        }
                    } ?: error("無法寫入原檔")
                } ?: error("無法讀取傳入內容")
                val digest = hash.digest().joinToString("") { "%02x".format(it) }
                check(bytes == meta.size && digest == meta.hash) { "大小或 SHA-256 不一致" }
                contentResolver.update(dest!!, ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }, null, null)
                try { contentResolver.delete(from, null, null) } catch (_: Exception) {}
                main.post {
                    control(JSONObject().put("type", "verified").put("sid", meta.session))
                    incoming = null; offer = null; receiving = false; progress.progress = 100
                    flash("✓ 收到原始檔：" + meta.name + "\nSHA-256 完整驗證", true)
                }
            } catch (e: Exception) {
                try { dest?.let { contentResolver.delete(it, null, null) } } catch (_: Exception) {}
                main.post {
                    control(JSONObject().put("type", "bad").put("sid", meta.session))
                    incoming = null; offer = null; receiving = false
                    flash("接收失敗：" + e.message)
                }
            }
        }
    }
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                provider = future.get()
                recognizer = GestureRecognizer.createFromOptions(this,
                    GestureRecognizer.GestureRecognizerOptions.builder()
                        .setBaseOptions(BaseOptions.builder().setModelAssetPath("gesture_recognizer.task").build())
                        .setRunningMode(RunningMode.VIDEO)
                        .setNumHands(1)
                        .setMinHandDetectionConfidence(0.52f)
                        .setMinHandPresenceConfidence(0.52f)
                        .setMinTrackingConfidence(0.50f)
                        .build())
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(android.util.Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888).build()
                analysis.setAnalyzer(cameraExecutor) { frame ->
                    try { analyze(frame) } catch (e: Exception) { show("辨識錯誤：" + e.message) }
                    finally { frame.close() }
                }
                provider?.unbindAll()
                provider?.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
                hand.text = "前鏡頭已啟動，等待手勢"
            } catch (e: Exception) { show("啟動前鏡頭失敗：" + e.message) }
        }, ContextCompat.getMainExecutor(this))
    }
    private var lastFrame = 0L
    private fun analyze(frame: ImageProxy) {
        val now = SystemClock.uptimeMillis()
        if (now - lastFrame < 95) return
        lastFrame = now
        val w = frame.width; val h = frame.height; val plane = frame.planes[0]
        val buffer = plane.buffer.duplicate()
        val data = ByteArray(w*h*4)
        val row = ByteArray(w*4)
        for (y in 0 until h) {
            buffer.position(y*plane.rowStride)
            buffer.get(row, 0, w*4)
            System.arraycopy(row, 0, data, y*w*4, w*4)
        }
        val image = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        image.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(data))
        val rotated = if (frame.imageInfo.rotationDegrees == 0) image
            else Bitmap.createBitmap(image, 0, 0, w, h,
                Matrix().apply { postRotate(frame.imageInfo.rotationDegrees.toFloat()) }, true)
        try {
            val result = recognizer?.recognizeForVideo(BitmapImageBuilder(rotated).build(), now) ?: return
            val cat = result.gestures().firstOrNull()?.maxByOrNull { it.score() }
            val score = cat?.score() ?: 0f
            val threshold = if (enhancedMode) 0.58f else 0.70f
            val pose = if (score >= threshold) cat?.categoryName() ?: "" else ""
            when (sequence.accept(pose, now, receiveAvailable = offer != null || linkOffer != null)) {
                GestureSequence.Result.SEND -> main.post { sendGesture() }
                GestureSequence.Result.RECEIVE -> main.post { receiveGesture() }
                else -> {}
            }
            if (now % 330 < 110) main.post {
                hand.text = "前鏡頭：" + when (pose) {
                    "Open_Palm" -> "張掌"; "Closed_Fist" -> "握拳"; else -> "等待手勢"
                } + "（辨識分數 " + (score * 100).toInt() + "%，" +
                    (if (enhancedMode) "靈敏" else "標準") + "）"
            }
        } finally {
            if (rotated !== image) rotated.recycle()
            image.recycle()
        }
    }
    override fun onDestroy() {
        provider?.unbindAll()
        recognizer?.close()
        if (!GlobalGestureService.isActive) {
            GlobalGrabBridge.onGrabResult = null
            GlobalGrabBridge.onReceiveGesture = null
            nearby.stopAllEndpoints(); nearby.stopAdvertising(); nearby.stopDiscovery()
        }
        io.shutdown(); cameraExecutor.shutdown()
        super.onDestroy()
    }
}

/**
 * A deliberate held fist triggers SEND or RECEIVE after separate thresholds.
 * An outstanding incoming offer uses receiveHoldMillis; otherwise use holdMillis.
 * A brief unrecognized camera frame is tolerated, but never starts a gesture.
 * The fist must be released before it can trigger again.
 */
class GestureSequence(
    private val holdMillis: Long = 2_000L,
    private val unknownGraceMillis: Long = 550L,
    private val receiveHoldMillis: Long = holdMillis
) {
    enum class Result { NONE, SEND, RECEIVE }

    private var fistSince = -1L
    private var lastFistSeen = -1L
    private var palmSince = -1L
    private var fired = false

    fun reset() {
        fistSince = -1L
        lastFistSeen = -1L
        palmSince = -1L
        fired = false
    }

    fun accept(raw: String, now: Long, receiveAvailable: Boolean = false): Result {
        when (raw) {
            "Closed_Fist" -> {
                palmSince = -1L
                // Once triggered, skipped frames cannot re-arm a continuous fist.
                // A confirmed open palm release is mandatory for the next grab.
                if (fired) {
                    lastFistSeen = now
                    return Result.NONE
                }
                if (fistSince < 0L || lastFistSeen < 0L || now < lastFistSeen ||
                    now - lastFistSeen > unknownGraceMillis) {
                    fistSince = now
                }
                lastFistSeen = now
                val requiredHold = if (receiveAvailable) receiveHoldMillis else holdMillis
                if (now - fistSince < requiredHold) return Result.NONE
                fired = true
                return if (receiveAvailable) Result.RECEIVE else Result.SEND
            }
            "Open_Palm" -> {
                // Do not re-arm on one accidental open-palm classification.
                if (palmSince < 0L) palmSince = now
                if (now - palmSince >= 250L) reset()
            }
            else -> {
                palmSince = -1L
                if (lastFistSeen >= 0L &&
                    (now < lastFistSeen || now - lastFistSeen > unknownGraceMillis)) {
                    if (fired) {
                        // Preserve one-shot latch across long dropped-frame gaps.
                        fistSince = -1L
                        lastFistSeen = -1L
                    } else reset()
                }
            }
        }
        return Result.NONE
    }
}
