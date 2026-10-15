package tw.airgesture.transferbeta

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import org.json.JSONObject
import tw.airgesture.transferbeta.core.GrabContentPolicy
import tw.airgesture.transferbeta.core.TransferPolicy
import tw.airgesture.transferbeta.core.VisualEvent
import tw.airgesture.transferbeta.core.VisualPhase
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Owned by the foreground service, never by TransferActivity.
 *
 * Security: the first connection and each reconnection requires BOTH people to
 * compare Nearby's digits. It intentionally does not trust mutable device names.
 * This version does not silently auto-pair until cryptographic key pinning exists.
 */
class BackgroundTransferManager(private val app: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val client = Nearby.getConnectionsClient(app)
    private val service = "tw.airgesture.transferbeta.p2p.v1"
    private val deviceId: String by lazy {
        val pref = app.getSharedPreferences("device", Context.MODE_PRIVATE)
        pref.getString("id", null) ?: UUID.randomUUID().toString().take(12).also {
            pref.edit().putString("id", it).apply()
        }
    }
    private var endpoint: String? = null
    private var connected = false
    private var started = false
    private var busy = false
    private var session = ""
    private var payloadOutgoing = -1L
    private var payloadIncoming = -1L
    private var incomingFile: Payload? = null
    private var incomingFinished = false
    private var outgoing: File? = null
    private var offered: Offer? = null
    private var generation = 0
    private var searchCycle = 0
    private val searchWindowMs = 18_000L
    private val searchPauseMs = 12_000L
    var status: String = "未啟動"
        private set
    var pendingDigits: String? = null
        private set
    private var awaitingPair: String? = null
    private sealed class Offer {
        abstract val sid: String
        data class FileOffer(
            override val sid: String, val name: String, val size: Long, val hash: String
        ) : Offer()
        data class LinkOffer(override val sid: String, val url: String) : Offer()
    }

    /** Activity sets this while visible; all session data remains in this service. */
    var onVisualEvent: ((VisualEvent) -> Unit)? = null
    private fun visual(phase: VisualPhase, label: String, progress: Int? = null) {
        main.post { onVisualEvent?.invoke(VisualEvent(phase, label, progress)) }
    }
    fun reportShareStarted(detail: String) {
        visual(VisualPhase.CAPTURING, detail)
        ui(detail)
    }
    var onBackgroundStatus: ((String) -> Unit)? = null
    var onStatus: ((String) -> Unit)? = null
        set(value) { field = value; value?.invoke(status) }
    var onPairing: ((String) -> Unit)? = null
        set(value) { field = value; pendingDigits?.let { value?.invoke(it) } }

    private fun ui(msg: String) {
        main.post {
            status = msg
            onBackgroundStatus?.invoke(msg)
            onStatus?.invoke(msg)
        }
    }
    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val b = ByteArray(131072)
            while (true) {
                val n = input.read(b)
                if (n < 0) break
                digest.update(b, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun start() {
        if (started) return
        started = true
        search()
    }
    fun stop() {
        started = false
        generation++
        searchCycle++
        client.stopDiscovery(); client.stopAdvertising(); client.stopAllEndpoints()
        endpoint = null
        connected = false
        busy = false
        offered = null
        pendingDigits = null
        awaitingPair = null
        onStatus = null; onPairing = null
        worker.shutdown()
        onVisualEvent = null
        ui("背景傳輸已停止")
    }
    private fun search() {
        if (!started || connected || awaitingPair != null) return
        val cycle = ++searchCycle
        client.stopDiscovery(); client.stopAdvertising()
        endpoint = null
        val options = Strategy.P2P_POINT_TO_POINT
        val name = "Beta|" + deviceId
        ui("正在搜尋附近已安裝隔空傳檔的手機")
        client.startAdvertising(name, service, lifecycle,
            AdvertisingOptions.Builder().setStrategy(options).build())
            .addOnFailureListener { ui("附近廣播不可用：" + it.message) }
        client.startDiscovery(service, discovery,
            DiscoveryOptions.Builder().setStrategy(options).build())
            .addOnFailureListener { ui("附近搜尋不可用：" + it.message) }
        // Discovery/advertising can drain power when no peer is nearby.
        // Keep confirmed connections alive, but duty-cycle searching otherwise.
        main.postDelayed({
            if (started && !connected && awaitingPair == null && cycle == searchCycle) {
                client.stopDiscovery(); client.stopAdvertising()
                ui("省電搜尋間歇中；稍後自動尋找附近裝置")
                main.postDelayed({
                    if (started && !connected && awaitingPair == null && cycle == searchCycle)
                        search()
                }, searchPauseMs)
            }
        }, searchWindowMs)
    }
    fun refresh() {
        if (!busy && awaitingPair == null && !connected) search()
        else ui(if (connected) "裝置已連線" else "配對或傳輸仍在進行")
    }
    fun reportCaptureFailure(message: String) { ui(message) }
    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(id: String, peer: DiscoveredEndpointInfo) {
            val other = peer.endpointName.substringAfter('|', "")
            if (other.length == 12 && deviceId < other && endpoint == null &&
                awaitingPair == null && !connected) {
                endpoint = id
                client.requestConnection("Beta|" + deviceId, id, lifecycle)
                    .addOnFailureListener {
                        endpoint = null
                        ui("連線要求失敗：" + it.message)
                    }
            }
        }
        override fun onEndpointLost(id: String) {}
    }
    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(id: String, info: ConnectionInfo) {
            main.post {
                if (!started || awaitingPair != null) {
                    client.rejectConnection(id)
                } else {
                    endpoint = id
                    awaitingPair = id
                    pendingDigits = info.authenticationDigits
                    ui("新連線待確認：請在兩支手機比對數字 " + info.authenticationDigits)
                    onPairing?.invoke(info.authenticationDigits)
                    notifyPairing(info.authenticationDigits)
                }
            }
        }
        override fun onConnectionResult(id: String, r: ConnectionResolution) {
            main.post {
                pendingDigits = null
                awaitingPair = null
                if (r.status.isSuccess) {
                    endpoint = id
                    connected = true
                    searchCycle++
                    client.stopAdvertising()
                    client.stopDiscovery()
                    ui("雙手機已安全配對；背景傳輸就緒")
                } else {
                    endpoint = null
                    connected = false
                    ui("配對失敗；請重新搜尋")
                    main.postDelayed({ search() }, 1700)
                }
            }
        }
        override fun onDisconnected(id: String) {
            main.post {
                connected = false
                endpoint = null
                searchCycle++
                busy = false
                offered = null
                pendingDigits = null
                awaitingPair = null
                ui("對方已斷線；重新搜尋並重新驗證配對")
                if (started) main.postDelayed({ search() }, 1800)
            }
        }
    }

    private fun notifyPairing(digits: String) {
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            "pair_needed", "隔空抓取配對確認", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(app, 114,
            Intent(app, TransferActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify(4418, Notification.Builder(app, "pair_needed")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle("隔空抓取：請比對配對數字")
            .setContentText(digits + "（需兩支手機都確認）")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build())
    }
    fun acceptPairing(yes: Boolean) {
        val id = awaitingPair ?: return
        awaitingPair = null
        pendingDigits = null
        if (yes) client.acceptConnection(id, payloadCallback)
            .addOnFailureListener { ui("接受配對失敗：" + it.message) }
        else {
            client.rejectConnection(id)
            endpoint = null
            ui("已拒絕配對")
            main.postDelayed({ search() }, 1700)
        }
    }

    private fun control(value: JSONObject) {
        val id = endpoint ?: return
        client.sendPayload(id, Payload.fromBytes(value.toString().toByteArray(Charsets.UTF_8)))
            .addOnFailureListener { ui("控制訊息未送達：" + it.message) }
    }
    fun offerLink(raw: String) {
        val url = GrabContentPolicy.safeWebUrl(raw)
        if (url == null) { ui("無法安全解析網址，已取消抓取"); return }
        if (!connected || busy) { ui("無法傳送：未連線或已有傳輸進行中"); return }
        busy = true
        session = UUID.randomUUID().toString()
        control(JSONObject().put("type", "link_offer").put("sid", session).put("url", url))
        ui("已抓取網址，等待對方握拳張掌（30 秒）")
        visual(VisualPhase.CONTENT_READY, "已抓取網址 · 等待接收")
        val key = ++generation
        main.postDelayed({
            if (generation == key && busy) {
                busy = false
                ui("網址分享逾時")
                visual(VisualPhase.FAILURE, "對方未在 30 秒內接收")
            }
        }, TransferPolicy.OFFER_TIMEOUT_MS)
    }

    fun offerFile(candidate: File, originalName: String) {
        if (!connected || busy) { ui("無法傳送：未連線或正在傳送"); return }
        if (!candidate.isFile || candidate.length() > TransferPolicy.MAX_BYTES) {
            ui("檔案不存在或超過 256MB"); return
        }
        val name = TransferPolicy.safeName(originalName)
            ?: run { ui("檔案名稱無效"); return }
        busy = true
        worker.execute {
            try {
                val sum = hash(candidate)
                main.post {
                    if (!started || !connected || !busy) return@post
                    session = UUID.randomUUID().toString()
                    outgoing = candidate
                    payloadOutgoing = -1
                    control(JSONObject().put("type", "file_offer").put("sid", session)
                        .put("name", name).put("size", candidate.length()).put("hash", sum))
                    ui("已抓取：" + name + "；等待對方接收")
                    visual(VisualPhase.CONTENT_READY, "已抓取：" + name)
                    val key = ++generation
                    main.postDelayed({
                        if (generation == key && busy && payloadOutgoing < 0) {
                            busy = false; ui("檔案接收等待逾時")
                            visual(VisualPhase.FAILURE, "等待接收逾時")
                        }
                    }, TransferPolicy.OFFER_TIMEOUT_MS)
                }
            } catch (e: Exception) {
                main.post { busy = false; ui("無法讀取原始檔案：" + e.message) }
            }
        }
    }
    fun confirmReceive() {
        if (!connected || busy) { ui("接收端未連線或正在傳輸"); return }
        val current = offered ?: run { ui("目前沒有待接收的內容"); return }
        busy = true
        ++generation
        when (current) {
            is Offer.LinkOffer -> {
                control(JSONObject().put("type", "link_ack").put("sid", current.sid))
                ui("已接收網頁／影片網址，請由通知開啟")
                notifyLink(current.url)
                visual(VisualPhase.RECEIVE_COMPLETE, "已收到連結 · 點通知開啟")
                busy = false; offered = null
            }
            is Offer.FileOffer -> {
                control(JSONObject().put("type", "file_ready").put("sid", current.sid))
                ui("準備接收原檔：" + current.name)
                visual(VisualPhase.RECEIVING, "接收中：" + current.name, 0)
            }
        }
    }

    private fun notifyLink(url: String) {
        val valid = GrabContentPolicy.safeWebUrl(url) ?: return
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            "link_received", "隔空抓取網址", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(app, 115, Intent(
            Intent.ACTION_VIEW, Uri.parse(valid)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify(4420, Notification.Builder(app, "link_received")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("已收到影片或網頁網址")
            .setContentText("點擊開啟")
            .setContentIntent(open)
            .setAutoCancel(true).build())
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(id: String, p: Payload) {
            main.post {
                if (id != endpoint) return@post
                if (p.type == Payload.Type.BYTES) {
                    try { onControl(JSONObject(String(p.asBytes() ?: return@post))) }
                    catch (_: Exception) { ui("無效控制訊息已丟棄") }
                } else if (p.type == Payload.Type.FILE) {
                    if (offered !is Offer.FileOffer || !busy) {
                        client.cancelPayload(p.id); return@post
                    }
                    incomingFile = p
                    payloadIncoming = p.id
                    if (incomingFinished) verifyFile()
                }
            }
        }
        override fun onPayloadTransferUpdate(id: String, u: PayloadTransferUpdate) {
            main.post {
                if (id != endpoint) return@post
                if (u.payloadId != payloadOutgoing && u.payloadId != payloadIncoming) return@post
                val expected = offered as? Offer.FileOffer
                if (u.payloadId == payloadIncoming && u.totalBytes > TransferPolicy.MAX_BYTES) {
                    client.cancelPayload(u.payloadId)
                    busy = false; incomingFile = null; offered = null
                    ui("拒絕接收超過 256MB 的檔案")
                    return@post
                }
                if (u.payloadId == payloadIncoming && expected != null &&
                    u.totalBytes >= 0 && u.totalBytes != expected.size) {
                    client.cancelPayload(u.payloadId)
                    busy = false; incomingFile = null; offered = null
                    ui("接收檔案大小與宣告不符，已拒絕")
                    return@post
                }
                when (u.status) {
                    PayloadTransferUpdate.Status.SUCCESS -> {
                        if (u.payloadId == payloadIncoming) {
                            incomingFinished = true
                            if (incomingFile != null) verifyFile()
                        } else ui("檔案已傳出，等待接收端 SHA-256 驗證")
                    }
                    PayloadTransferUpdate.Status.FAILURE,
                    PayloadTransferUpdate.Status.CANCELED -> {
                        busy = false; incomingFile = null; offered = null
                        ui("傳輸取消或失敗")
                        visual(VisualPhase.FAILURE, "傳輸中斷，未完成")
                    }
                    PayloadTransferUpdate.Status.IN_PROGRESS -> {
                        if (u.totalBytes > 0) {
                            val percent = ((100 * u.bytesTransferred / u.totalBytes).toInt())
                                .coerceIn(0, 100)
                            ui("傳輸中 " + percent + "%")
                            if (u.payloadId == payloadIncoming && (percent % 5 == 0 || percent == 100))
                                visual(VisualPhase.RECEIVING, "正在接收 · " + percent + "%", percent)
                        }
                    }
                }
            }
        }
    }
    private fun onControl(j: JSONObject) {
        val sid = j.optString("sid")
        when (j.optString("type")) {
            "file_offer" -> {
                val name = j.optString("name")
                val count = j.optLong("size", -1)
                val sum = j.optString("hash")
                if (busy || offered != null || !TransferPolicy.validFileOffer(sid, name, count, sum)) {
                    control(JSONObject().put("type", "busy").put("sid", sid)); return
                }
                offered = Offer.FileOffer(sid, TransferPolicy.safeName(name)!!, count, sum)
                incomingFile = null; incomingFinished = false; payloadIncoming = -1L
                ui("收到檔案請求：" + name + "；握拳 → 張掌以接收")
                visual(VisualPhase.OFFER_RECEIVED, "收到檔案 · 握拳張掌接收")
                expireOffer(sid)
            }
            "link_offer" -> {
                val url = j.optString("url")
                if (busy || offered != null || !TransferPolicy.validLinkOffer(sid, url)) {
                    control(JSONObject().put("type", "busy").put("sid", sid)); return
                }
                offered = Offer.LinkOffer(sid, url)
                ui("收到影片或網頁網址；握拳 → 張掌以接收")
                visual(VisualPhase.OFFER_RECEIVED, "收到連結 · 握拳張掌接收")
                expireOffer(sid)
            }
            "file_ready" -> {
                if (!busy || sid != session || payloadOutgoing != -1L) return
                val candidate = outgoing ?: return
                try {
                    val p = Payload.fromFile(candidate)
                    payloadOutgoing = p.id
                    client.sendPayload(endpoint ?: return, p)
                        .addOnFailureListener { busy = false; ui("無法啟動傳送：" + it.message) }
                    ui("正在傳送完整檔案")
                } catch (t: Exception) { busy = false; ui("傳送失敗：" + t.message) }
            }
            "file_verified" -> if (busy && sid == session) {
                busy = false; ++generation
                ui("✓ 原檔 SHA-256 完整驗證，傳送成功")
                visual(VisualPhase.SEND_COMPLETE, "傳送完成 · 對方已驗證")
            }
            "file_invalid" -> if (sid == session) {
                busy = false; ui("接收端檔案完整性檢查失敗")
                visual(VisualPhase.FAILURE, "接收端驗證失敗")
            }
            "link_ack" -> if (sid == session) {
                busy = false; ++generation
                ui("✓ 對方已接收網址")
                visual(VisualPhase.SEND_COMPLETE, "網址已交給對方")
            }
            "busy" -> if (sid == session) {
                busy = false; ++generation
                ui("接收端忙碌或無法接收")
                visual(VisualPhase.FAILURE, "接收端暫時無法接收")
            }
        }
    }
    private fun expireOffer(id: String) {
        val key = ++generation
        main.postDelayed({
            if (generation == key && offered?.sid == id && !busy) {
                offered = null
                ui("接收請求逾時")
                visual(VisualPhase.FAILURE, "接收請求已逾時")
            }
        }, TransferPolicy.OFFER_TIMEOUT_MS)
    }
    private fun verifyFile() {
        val meta = offered as? Offer.FileOffer ?: return
        visual(VisualPhase.VERIFYING, "接收完成，正在確認檔案完整性")
        val payload = incomingFile ?: return
        incomingFile = null // prevent double verification from duplicate callbacks
        worker.execute {
            var target: Uri? = null
            try {
                val source = payload.asFile()?.asUri() ?: error("沒有收到檔案")
                target = app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, meta.name)
                        put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }) ?: error("無法建立下載項目")
                val md = MessageDigest.getInstance("SHA-256")
                var count = 0L
                app.contentResolver.openInputStream(source)?.use { input ->
                    app.contentResolver.openOutputStream(target!!, "w")?.use { out ->
                        val buffer = ByteArray(131072)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            md.update(buffer, 0, n)
                            count += n
                        }
                    } ?: error("無法儲存檔案")
                } ?: error("無法讀取接收檔")
                val digest = md.digest().joinToString("") { "%02x".format(it) }
                check(TransferPolicy.matchingHash(meta.hash, digest, meta.size, count)) {
                    "SHA-256 不一致"
                }
                app.contentResolver.update(target!!, ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }, null, null)
                main.post {
                    control(JSONObject().put("type","file_verified").put("sid",meta.sid))
                    busy = false; offered = null; ++generation
                    ui("✓ 已接收原始檔案：" + meta.name)
                    visual(VisualPhase.RECEIVE_COMPLETE, "接收成功：" + meta.name)
                }
            } catch (e: Exception) {
                try { target?.let { app.contentResolver.delete(it, null, null) } }
                catch (_: Exception) {}
                main.post {
                    control(JSONObject().put("type","file_invalid").put("sid",meta.sid))
                    busy = false; offered = null; ++generation
                    ui("接收檔案驗證失敗：" + e.message)
                    visual(VisualPhase.FAILURE, "檔案檢查失敗，未儲存成功")
                }
            }
        }
    }
}
