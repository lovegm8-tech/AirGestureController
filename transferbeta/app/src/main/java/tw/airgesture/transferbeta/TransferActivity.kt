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
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.Gravity
import android.widget.Button
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
    private lateinit var advanced: LinearLayout
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
    private val sequence = GestureSequence(holdMillis = 2_000L, unknownGraceMillis = 480L)
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
        if (hasPermissions()) {
            refreshHome()
            if (homePrefs.getBoolean("auto_run", false) && hasAccessibilityPermission()) {
                startGlobalMode()
            }
        } else {
            show("尚未授予必要權限")
            refreshHome()
        }
    }

    private var shownPairCode: String? = null

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
        if (queuedAutoHome) return
        val mayClose = tw.airgesture.transferbeta.core.HomeScreenPolicy.shouldAutoBackground(
            homePrefs.getBoolean("auto_run", false),
            normalLauncherEntry,
            GlobalGestureService.isActive,
            GlobalGestureService.transfer?.isConnected == true,
            homePromptVisible,
            advancedOpen
        )
        if (!mayClose || !Settings.canDrawOverlays(this)) return
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
                ) && Settings.canDrawOverlays(this)) {
                moveTaskToBack(true)
            }
        }, 900)
    }

    private fun startGlobalMode() {
        if (!hasPermissions()) {
            permissionLauncher.launch(permissions())
            return
        }
        if (!hasAccessibilityPermission()) {
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

    private fun refreshHome() {
        if (!::status.isInitialized || !::primaryAction.isInitialized) return
        val d = tw.airgesture.transferbeta.core.HomeScreenPolicy.decide(
            hasPermissions(), hasAccessibilityPermission(),
            GlobalGestureService.isActive,
            GlobalGestureService.transfer?.isConnected == true
        )
        val overlayMissing = hasPermissions() && hasAccessibilityPermission() &&
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
        titleHint.setTextColor(if (d.state ==
                tw.airgesture.transferbeta.core.HomeState.READY) 0xff0e8053.toInt()
            else 0xff1f3550.toInt())
        if (d.state == tw.airgesture.transferbeta.core.HomeState.SEARCHING) {
            status.text = GlobalGestureService.transfer?.status ?: "搜尋附近手機中"
        }
    }

    override fun onResume() {
        super.onResume()
        refreshHome()
        if (homePrefs.getBoolean("auto_run", false) &&
            hasPermissions() && hasAccessibilityPermission() && !GlobalGestureService.isActive) {
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
        root.addView(text("免觸控操作、隔空抓取，一次啟用即可使用。", 15f))
        titleHint = text("檢查服務狀態中…", 20f)
        root.addView(titleHint)
        status = text("等待連線", 14f)
        root.addView(status)
        primaryAction = Button(this).apply {
            text = "開始設定"
            minHeight = (resources.displayMetrics.density * 64).toInt()
            setOnClickListener {
                if (hasPermissions() && hasAccessibilityPermission() &&
                    !Settings.canDrawOverlays(this@TransferActivity)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + packageName)))
                    return@setOnClickListener
                }
                when (tw.airgesture.transferbeta.core.HomeScreenPolicy.decide(
                    hasPermissions(), hasAccessibilityPermission(),
                    GlobalGestureService.isActive,
                    GlobalGestureService.transfer?.isConnected == true
                ).action) {
                    tw.airgesture.transferbeta.core.HomeAction.GRANT_PERMISSION ->
                        permissionLauncher.launch(permissions())
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
        root.addView(text("滑動｜張掌上下揮動；向右揮＝上一頁\n點擊｜張掌→短握拳→放開（YouTube 播放／暫停）\n游標｜伸食指移動，停留 2 秒點擊\n傳送｜握拳 2 秒抓取；收到分享後握拳 2 秒接收", 15f))
        root.addView(text("整天待命：亮屏無手自動降頻，熄屏完全釋放前鏡頭；解鎖後再恢復。\n隔空操作無須附近手機；只有傳檔需要配對。", 13f))
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
                        "\n\n傳送端抓取後開放接收 30 秒，已配對設備可各自握拳持續 2 秒加入。" +
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
        extraButton("辨識與效能資訊") {
            flash("影格：" + GlobalGestureService.framesSeen +
                "／辨識：" + GlobalGestureService.inferences +
                "／鏡頭 FPS：" + GlobalGestureService.requestedSensorFps)
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

    private fun permissions(): Array<String> {
        val out = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            out += Manifest.permission.BLUETOOTH_SCAN
            out += Manifest.permission.BLUETOOTH_ADVERTISE
            out += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= 33) out += Manifest.permission.NEARBY_WIFI_DEVICES
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
                flash("收到網址：請握拳維持 2 秒，接收對方影片／網頁")
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
                flash("接收請求：" + safeName(name) + "\n請握拳維持 2 秒")
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
 * One deliberate closed fist held for 2 seconds triggers one action.
 * An outstanding incoming offer makes the gesture RECEIVE; otherwise it is SEND.
 * A brief unrecognized camera frame is tolerated, but never starts a gesture.
 * The fist must be released before it can trigger again.
 */
class GestureSequence(
    private val holdMillis: Long = 2_000L,
    private val unknownGraceMillis: Long = 550L
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
                if (fistSince < 0L || lastFistSeen < 0L || now < lastFistSeen ||
                    now - lastFistSeen > unknownGraceMillis) {
                    fistSince = now
                    fired = false
                }
                lastFistSeen = now
                if (fired || now - fistSince < holdMillis) return Result.NONE
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
                    reset()
                }
            }
        }
        return Result.NONE
    }
}
