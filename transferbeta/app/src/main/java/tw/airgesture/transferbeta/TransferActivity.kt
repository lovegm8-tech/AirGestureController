package tw.airgesture.transferbeta

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.provider.Settings
import androidx.lifecycle.Lifecycle
import tw.airgesture.transferbeta.core.GrabContentPolicy
import android.app.AlertDialog
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
    private lateinit var chosen: TextView
    private lateinit var hand: TextView
    private lateinit var animation: TextView
    private lateinit var progress: ProgressBar
    private var provider: ProcessCameraProvider? = null
    private var recognizer: GestureRecognizer? = null
    private val sequence = GestureSequence(holdMillis = 230L, unknownGraceMillis = 480L)
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
        if (hasPermissions()) { startCamera(); findPeers() }
        else show("缺少相機或附近裝置權限，請授權後重新開啟 App")
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 28)
            setBackgroundColor(0xfff7f9fc.toInt())
        }
        fun text(msg: String, size: Float = 17f) = TextView(this).apply {
            this.text = msg
            textSize = size
            setTextColor(0xff1f3550.toInt())
            setPadding(0, 10, 0, 10)
        }
        root.addView(text("隔空傳檔｜雙機測試 v0.1.1", 23f))
        root.addView(text("兩支手機皆安裝此 App；第一次連線確認雙方驗證碼。", 15f))
        status = text("未連線"); root.addView(status)
        chosen = text("尚未選擇原檔"); root.addView(chosen)
        hand = text("前鏡頭尚未啟動"); root.addView(hand)
        animation = text("準備就緒", 23f).apply {
            gravity = Gravity.CENTER
            minimumHeight = 140
            setBackgroundColor(0xffe5edf8.toInt())
        }
        root.addView(animation)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
        }
        root.addView(progress)
        fun button(name: String, action: () -> Unit) {
            root.addView(Button(this).apply { text = name; setOnClickListener { action() } })
        }
        button("選擇要傳送的原始照片或 PDF") { picker.launch(arrayOf("*/*")) }
        button("重新搜尋附近手機") { findPeers() }
        button("模擬張掌 → 握拳（發送）") { sendGesture() }
        button("模擬握拳 → 張掌（接收）") { receiveGesture() }
        root.addView(text("實際手勢：先維持第一個手勢約 0.3 秒，再維持第二個手勢約 0.3 秒。", 14f))
        root.addView(text("可直接在三星相簿、Google 相簿或檔案管理員中按「分享」，選擇「隔空傳檔測試版」。", 14f))
        val sensitivityButton = Button(this).apply {
            text = "S25 辨識模式：靈敏（點選切換）"
            setOnClickListener {
                enhancedMode = !enhancedMode
                text = if (enhancedMode) "S25 辨識模式：靈敏（點選切換）"
                    else "辨識模式：標準（點選切換）"
                getSharedPreferences("gesture", MODE_PRIVATE).edit()
                    .putBoolean("enhanced", enhancedMode).apply()
                flash(if (enhancedMode) "已切換靈敏模式：降低辨識門檻，觀察誤判率"
                    else "已切換標準模式：較嚴格的手勢信心門檻")
            }
        }
        root.addView(sensitivityButton)
        root.addView(text("全域抓取模式：瀏覽器網址可直接傳送；其他 App 僅能嘗試傳送「畫面截圖」，不是照片／文件原檔。", 14f))
        button("前往 Android 無障礙設定，啟用隔空抓取") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        button("啟動全域隔空抓取（持續前鏡頭）") {
            if (!hasPermissions()) {
                permissionLauncher.launch(permissions())
            } else {
                provider?.unbindAll()
                recognizer?.close()
                recognizer = null
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                ContextCompat.startForegroundService(this,
                    Intent(this, GlobalGestureService::class.java)
                        .setAction(GlobalGestureService.ACTION_START))
                flash("已要求開始全域抓取；請切換至相簿或瀏覽器", true)
            }
        }
        button("停止全域模式，恢復 App 內手勢") {
            stopService(Intent(this, GlobalGestureService::class.java))
            startCamera()
        }
        button("查看省電推論計數（非電池耗電量）") {
            flash("前鏡頭影格：" + GlobalGestureService.framesSeen +
                "；AI 推論：" + GlobalGestureService.inferences +
                "；要求 FPS：" + GlobalGestureService.requestedSensorFps)
        }
        button("開啟最近收到的影片／網頁連結") {
            lastReceivedLink?.let { openLink(it) } ?: flash("目前沒有收到網址")
        }
        root.addView(text("請先到「設定 → 無障礙」手動開啟「隔空抓取」，並保持附近連線。待機仍需使用鏡頭，實際耗電待測。", 14f))
        scroll.addView(root)
        setContentView(scroll)
        enhancedMode = getSharedPreferences("gesture", MODE_PRIVATE).getBoolean("enhanced", true)
        sensitivityButton.text = if (enhancedMode) "S25 辨識模式：靈敏（點選切換）"
            else "辨識模式：標準（點選切換）"
        GlobalGrabBridge.onGrabResult = { result ->
            main.post { consumeGlobalGrab(result) }
        }
        GlobalGrabBridge.onReceiveGesture = { main.post { receiveGesture() } }
        if (hasPermissions()) {
            if (!GlobalGestureService.isActive) startCamera()
            findPeers()
        } else permissionLauncher.launch(permissions())
        consumeSharedFile(intent)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeSharedFile(intent)
    }

    private fun consumeSharedFile(shared: Intent?) {
        if (shared?.action != Intent.ACTION_SEND) return
        @Suppress("DEPRECATION")
        val uri = if (Build.VERSION.SDK_INT >= 33) {
            shared.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else shared.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        val source = uri ?: shared.clipData?.getItemAt(0)?.uri
        if (source == null || (source.scheme != "content" && source.scheme != "file")) {
            flash("分享內容不是可讀取的檔案；請改用系統檔案選取")
            return
        }
        loadFile(source)
    }

    private fun consumeGlobalGrab(result: GrabResult) {
        if (!connected) { flash("目前無法抓取：請先完成雙手機配對"); return }
        when (result) {
            is GrabResult.Link -> sendLink(result.url)
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
                    flash("原檔準備完成", true)
                }
            } catch (e: Exception) { show("選檔失敗：" + e.message) }
        }
    }

    private fun findPeers() {
        if (!hasPermissions()) { permissionLauncher.launch(permissions()); return }
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
                flash("收到網址：請握拳 → 張掌，開啟對方影片／網頁")
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
                flash("接收請求：" + safeName(name) + "\n請握拳 → 張掌")
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
            when (sequence.accept(pose, now)) {
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
        if (!GlobalGestureService.isActive) provider?.unbindAll()
        recognizer?.close()
        GlobalGrabBridge.onGrabResult = null
        GlobalGrabBridge.onReceiveGesture = null
        nearby.stopAllEndpoints(); nearby.stopAdvertising(); nearby.stopDiscovery()
        io.shutdown(); cameraExecutor.shutdown()
        super.onDestroy()
    }
}

/**
 * A stable transition detector tolerant of brief missed frames.
 * Unknown observations never synthesize a gesture; they only preserve an existing
 * candidate briefly so a low-confidence frame does not restart the hold timer.
 */
class GestureSequence(
    private val holdMillis: Long = 230L,
    private val unknownGraceMillis: Long = 480L
) {
    enum class Result { NONE, SEND, RECEIVE }

    private var candidate = ""
    private var candidateSince = 0L
    private var candidateLastSeen = 0L
    private var confirmed = ""
    private var confirmedAt = 0L
    private var cooldownUntil = 0L

    fun accept(raw: String, now: Long): Result {
        val pose = if (raw == "Open_Palm" || raw == "Closed_Fist") raw else ""
        if (pose.isEmpty()) {
            if (candidate.isNotEmpty() && now - candidateLastSeen > unknownGraceMillis) {
                candidate = ""
            }
            if (confirmed.isNotEmpty() && now - confirmedAt > 3000L) confirmed = ""
            return Result.NONE
        }
        if (pose != candidate || now - candidateLastSeen > unknownGraceMillis) {
            candidate = pose
            candidateSince = now
        }
        candidateLastSeen = now
        if (now < cooldownUntil || now - candidateSince < holdMillis || pose == confirmed) {
            return Result.NONE
        }
        val previous = confirmed
        val gap = now - confirmedAt
        confirmed = pose
        confirmedAt = now
        if (previous.isEmpty() || gap > 3000L) return Result.NONE
        val result = when {
            previous == "Open_Palm" && pose == "Closed_Fist" -> Result.SEND
            previous == "Closed_Fist" && pose == "Open_Palm" -> Result.RECEIVE
            else -> Result.NONE
        }
        if (result != Result.NONE) {
            cooldownUntil = now + 1600L
            candidate = ""
            confirmed = ""
        }
        return result
    }
}
