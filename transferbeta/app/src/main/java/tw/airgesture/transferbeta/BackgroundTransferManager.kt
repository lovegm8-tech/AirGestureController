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
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import org.json.JSONObject
import tw.airgesture.transferbeta.core.GrabContentPolicy
import tw.airgesture.transferbeta.core.TransferPolicy
import tw.airgesture.transferbeta.core.PeerIdentityProtocol
import tw.airgesture.transferbeta.core.OfferDeliveryTracker
import java.security.KeyPair
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
    // v2 never accidentally negotiates with a previous non-authenticated beta.
    private val service = "tw.airgesture.transferbeta.p2p.v2"
    private val trusted = TrustedPeerStore(app)
    private val deviceId: String by lazy {
        val pref = app.getSharedPreferences("device", Context.MODE_PRIVATE)
        pref.getString("id", null) ?: UUID.randomUUID().toString().take(12).also {
            pref.edit().putString("id", it).apply()
        }
    }
    private var endpoint: String? = null
    private var connected = false
    val isConnected: Boolean get() = connected
    var onConnectionState: ((Boolean) -> Unit)? = null
        set(value) { field = value; value?.invoke(connected) }
    private var started = false
    private var busy = false
    private var session = ""
    private var outgoingOffer: JSONObject? = null
    private var offerDelivery: OfferDeliveryTracker? = null
    private var payloadOutgoing = -1L
    private var payloadIncoming = -1L
    private var incomingFile: Payload? = null
    private var incomingFinished = false
    private var outgoing: File? = null
    private var offered: Offer? = null
    private var generation = 0
    private var lastDisplayedPercent = -1
    private var lastProgressStamp = 0L
    private var searchCycle = 0
    private val searchWindowMs = 18_000L
    private val searchPauseMs = 12_000L
    var status: String = "未啟動"
        private set
    var pendingDigits: String? = null
        private set

    private var awaitingPair: String? = null
    private var peerHint: String? = null
    private var manualNumericApproved = false
    private var channelToken: String? = null
    private var authPending = false
    private var localNonce: String? = null
    private var peerNonce: String? = null
    private var localPublic: String? = null
    private var peerPublic: String? = null
    private var peerProof: String? = null
    private var ownProofValidated = false
    private var peerConfirmedReady = false
    private var identityKeyPair: KeyPair? = null
    private var authRound = 0

    val rememberedDeviceId: String? get() = trusted.trustedId()
    fun forgetTrustedDevice() {
        val old = trusted.trustedId()
        trusted.forget()
        ui(if (old != null) "已清除信任裝置，下次須重新確認配對數字" else "目前沒有記住的裝置")
        if (endpoint != null) {
            client.stopAllEndpoints()
            endpoint = null
            connected = false
            onConnectionState?.invoke(false)
            resetAuthentication()
            if (started) main.postDelayed({ search() }, 1200)
        }
    }

    private fun resetAuthentication() {
        authRound++
        manualNumericApproved = false
        channelToken = null
        authPending = false
        localNonce = null
        peerNonce = null
        localPublic = null
        peerPublic = null
        peerProof = null
        ownProofValidated = false
        peerConfirmedReady = false
        identityKeyPair = null
        peerHint = null
    }

    private fun failAuthentication(reason: String) {
        if (endpoint == null) return
        ui("裝置身份驗證失敗：" + reason)
        visual(VisualPhase.FAILURE, "連線驗證失敗 · 請到進階設定確認裝置")
        val old = endpoint
        connected = false
        onConnectionState?.invoke(false)
        resetAuthentication()
        endpoint = null
        if (old != null) client.disconnectFromEndpoint(old)
        if (started) main.postDelayed({ search() }, 1700L)
    }

    private fun startCryptographicAuth(id: String) {
        if (id != endpoint) return
        authPending = true
        val round = ++authRound
        ui("正在確認已配對裝置的加密身分…")
        // Android Keystore operations stay off the UI thread.
        worker.execute {
            try {
                val key = trusted.identity()
                val pub = PeerIdentityProtocol.encodedKey(key.public)
                val nonce = trusted.randomNonce()
                main.post {
                    if (!started || round != authRound || endpoint != id || !authPending) return@post
                    identityKeyPair = key
                    localPublic = pub
                    localNonce = nonce
                    fun hello(): JSONObject = JSONObject()
                        .put("type", "auth_hello").put("v", PeerIdentityProtocol.PROTOCOL_VERSION)
                        .put("id", deviceId).put("pub", pub).put("nonce", nonce)
                    control(hello())
                    // The two Nearby onConnectionResult callbacks are not guaranteed
                    // simultaneous. Retry the same session hello until a proof arrives.
                    for (delayMs in listOf(450L, 1100L, 2400L, 4300L)) {
                        main.postDelayed({
                            if (authPending && round == authRound && endpoint == id) {
                                control(hello())
                            }
                        }, delayMs)
                    }
                    sendLocalProof()
                    verifyRemoteProofIfReady()
                    ui("已交換認證資料，正在驗證對方…")
                }
            } catch (_: Exception) {
                main.post { if (round == authRound) failAuthentication("無法取得手機安全金鑰") }
            }
        }
        main.postDelayed({
            if (round == authRound && authPending && endpoint == id)
                failAuthentication("逾時，無法確認對方身份")
        }, 13_000)
    }

    private fun handleAuthHello(j: JSONObject) {
        if (!authPending) return
        if (j.optInt("v") != PeerIdentityProtocol.PROTOCOL_VERSION) {
            failAuthentication("協定版本不符，請兩支手機更新")
            return
        }
        val claimed = j.optString("id")
        val pub = j.optString("pub")
        val nonce = j.optString("nonce")
        if (!PeerIdentityProtocol.validPeerId(claimed) || claimed != peerHint ||
            claimed == deviceId || !PeerIdentityProtocol.validNonce(nonce)) {
            failAuthentication("裝置代碼或隨機驗證碼異常")
            return
        }
        try { PeerIdentityProtocol.parseKey(pub) }
        catch (_: Exception) { failAuthentication("公鑰格式錯誤"); return }
        val pinnedId = trusted.trustedId()
        val pinnedKey = trusted.trustedKey()
        if (pinnedId != null || pinnedKey != null) {
            if (!trusted.isTrusted(claimed, pub)) {
                failAuthentication("對方金鑰已變更，不會自動信任；請手動忘記裝置後重新配對")
                return
            }
        } else if (!manualNumericApproved) {
            failAuthentication("首次連線必須先人工確認配對數字")
            return
        }
        if (peerPublic != null && (peerPublic != pub || peerNonce != nonce)) {
            failAuthentication("同一連線重複收到不一致身份資料")
            return
        }
        peerPublic = pub
        peerNonce = nonce
        sendLocalProof()
        verifyRemoteProofIfReady()
    }

    private fun sendLocalProof() {
        val otherId = peerHint ?: return
        val otherPub = peerPublic ?: return
        val otherNonce = peerNonce ?: return
        val ownNonce = localNonce ?: return
        val ownPub = localPublic ?: return
        val key = identityKeyPair ?: return
        worker.execute {
            try {
                val proof = PeerIdentityProtocol.proofMessage(
                    deviceId, otherId, ownPub, otherPub, ownNonce, otherNonce,
                    channelToken ?: error("Nearby channel token missing"))
                val sig = PeerIdentityProtocol.sign(key, proof)
                main.post {
                    if (authPending && otherPub == peerPublic && otherNonce == peerNonce &&
                        ownNonce == localNonce) {
                        control(JSONObject().put("type", "auth_proof").put("sig", sig))
                    }
                }
            } catch (_: Exception) {
                main.post { if (authPending) failAuthentication("無法簽署身份挑戰") }
            }
        }
    }

    private fun verifyRemoteProofIfReady() {
        if (!authPending) return
        val signature = peerProof ?: return
        val otherId = peerHint ?: return
        val otherPub = peerPublic ?: return
        val otherNonce = peerNonce ?: return
        val ownNonce = localNonce ?: return
        val ownPub = localPublic ?: return
        val msg = try {
            PeerIdentityProtocol.proofMessage(
                otherId, deviceId, otherPub, ownPub, otherNonce, ownNonce,
                channelToken ?: error("Nearby channel token missing"))
        } catch (_: Exception) {
            failAuthentication("無效簽章資料")
            return
        }
        if (!PeerIdentityProtocol.verify(otherPub, msg, signature)) {
            failAuthentication("裝置沒有正確私鑰，已拒絕連線")
            return
        }
        if (!trusted.isTrusted(otherId, otherPub)) {
            if (!manualNumericApproved) {
                failAuthentication("未授權的新裝置")
                return
            }
            try { trusted.pinAfterVerifiedFirstPairing(otherId, otherPub) }
            catch (_: Exception) {
                failAuthentication("無法儲存或驗證信任裝置")
                return
            }
        }
        if (!ownProofValidated) {
            ownProofValidated = true
            control(JSONObject().put("type", "auth_ready"))
        }
        finishAuthIfBothReady()
    }

    private sealed class Offer {
        abstract val sid: String
        data class FileOffer(
            override val sid: String, val name: String, val size: Long, val hash: String
        ) : Offer()
        data class LinkOffer(override val sid: String, val url: String) : Offer()
    }

    /** Activity sets this while visible; all session data remains in this service. */
    var onReceiverOfferState: ((Boolean) -> Unit)? = null
    private fun setReceiveArmed(active: Boolean) { onReceiverOfferState?.invoke(active) }
    private fun clearIncomingPrompt() {
        app.getSystemService(NotificationManager::class.java).cancel(4431)
    }
    private fun notifyIncomingOffer(label: String) {
        val notifications = app.getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(
            "airgrab_receives", "AirGrab 待接收", NotificationManager.IMPORTANCE_DEFAULT))
        val accept = PendingIntent.getService(app, 4432,
            Intent(app, GlobalGestureService::class.java).setAction(
                GlobalGestureService.ACTION_CONFIRM_RECEIVE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        try { notifications.notify(4431, Notification.Builder(app, "airgrab_receives")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle("AirGrab 等待接收")
            .setContentText(label.take(50) + " · 握拳張掌，或點通知立即接收")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(
                android.R.drawable.ic_input_add, "立即接收", accept).build())
            .build()) } catch (_: SecurityException) {
                ui("通知權限未開，但仍可使用接收手勢")
            }
    }
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
        resetAuthentication()
        busy = false
        clearOfferDelivery()
        offered = null
        clearIncomingPrompt()
        setReceiveArmed(false)
        pendingDigits = null
        awaitingPair = null
        onStatus = null; onPairing = null
        onConnectionState?.invoke(false)
        onConnectionState = null
        worker.shutdown()
        onVisualEvent = null
        onReceiverOfferState = null
        ui("背景傳輸已停止")
    }
    private fun search() {
        if (!started || connected || authPending || awaitingPair != null) return
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
            if (started && !connected && !authPending &&
                awaitingPair == null && cycle == searchCycle) {
                client.stopDiscovery(); client.stopAdvertising()
                ui("省電搜尋間歇中；稍後自動尋找附近裝置")
                main.postDelayed({
                    if (started && !connected && !authPending &&
                        awaitingPair == null && cycle == searchCycle)
                        search()
                }, searchPauseMs)
            }
        }, searchWindowMs)
    }
    fun refresh() {
        if (!busy && !authPending && awaitingPair == null && !connected) search()
        else ui(if (connected) "裝置已連線" else "配對或傳輸仍在進行")
    }
    fun reportCaptureFailure(message: String) {
        ui(message)
        visual(VisualPhase.FAILURE, message.take(75))
    }
    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(id: String, peer: DiscoveredEndpointInfo) {
            val other = peer.endpointName.substringAfter('|', "")
            if (other.length == 12 && deviceId < other && endpoint == null &&
                awaitingPair == null && !connected) {
                endpoint = id
                peerHint = other
                client.requestConnection("Beta|" + deviceId, id, lifecycle)
                    .addOnFailureListener {
                        endpoint = null
                        ui("連線要求失敗：" + it.message)
                    }
            }
        }
        override fun onEndpointLost(id: String) {}
    }
    /** Both endpoints must validate each other's signature before transfers unlock. */
    private fun finishAuthIfBothReady() {
        if (!authPending || !ownProofValidated || !peerConfirmedReady) return
        authPending = false
        connected = true
        onConnectionState?.invoke(true)
        searchCycle++
        client.stopAdvertising()
        client.stopDiscovery()
        ui("已完成雙向身份驗證，已記住裝置並自動安全連線")
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(id: String, info: ConnectionInfo) {
            main.post {
                if (!started || (endpoint != null && endpoint != id) ||
                    (awaitingPair != null && awaitingPair != id) || connected || authPending) {
                    client.rejectConnection(id)
                    return@post
                }
                val hintedId = info.endpointName.substringAfter('|', "")
                if (!PeerIdentityProtocol.validPeerId(hintedId) || hintedId == deviceId) {
                    client.rejectConnection(id)
                    ui("無效的裝置廣播識別碼")
                    return@post
                }
                if (peerHint != null && peerHint != hintedId) {
                    client.rejectConnection(id)
                    ui("搜尋識別與連線識別不一致")
                    return@post
                }
                endpoint = id
                peerHint = hintedId
                channelToken = info.authenticationDigits
                // A matching name only allows an attempt. It NEVER authorizes file
                // exchange until the persisted public key signs fresh challenges.
                if (trusted.isRecognizedHint(hintedId)) {
                    ui("發現已記住裝置，正在驗證安全金鑰…")
                    awaitingPair = null
                    manualNumericApproved = false
                    client.acceptConnection(id, payloadCallback).addOnFailureListener {
                        failAuthentication("無法建立已記住裝置連線")
                    }
                } else {
                    awaitingPair = id
                    manualNumericApproved = false
                    pendingDigits = info.authenticationDigits
                    ui("首次安全配對：請在兩支手機確認 " + info.authenticationDigits)
                    onPairing?.invoke(info.authenticationDigits)
                    notifyPairing(info.authenticationDigits)
                }
            }
        }
        override fun onConnectionResult(id: String, result: ConnectionResolution) {
            main.post {
                if (!started || id != endpoint) return@post
                pendingDigits = null
                awaitingPair = null
                app.getSystemService(NotificationManager::class.java).cancel(4418)
                if (result.status.isSuccess) {
                    connected = false
                    startCryptographicAuth(id)
                } else {
                    connected = false
                    endpoint = null
                    resetAuthentication()
                    ui("安全連線未建立，稍後重新搜尋")
                    main.postDelayed({ search() }, 1700L)
                }
            }
        }
        override fun onDisconnected(id: String) {
            main.post {
                if (id != endpoint) return@post
                connected = false
                onConnectionState?.invoke(false)
                endpoint = null
                resetAuthentication()
                searchCycle++
                busy = false
                clearOfferDelivery()
                offered = null
                clearIncomingPrompt()
                setReceiveArmed(false)
                pendingDigits = null
                awaitingPair = null
                ui("對方已斷線，稍後自動尋找已記住裝置")
                if (started) main.postDelayed({ search() }, 1800L)
            }
        }
    }

    private fun notifyPairing(digits: String) {
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            "pair_needed", "隔空抓取配對確認", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(app, 114,
            Intent(app, TransferActivity::class.java)
                .putExtra("airgrab_show_settings", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        try {
            manager.notify(4418, Notification.Builder(app, "pair_needed")
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setContentTitle("隔空抓取：首次確認信任裝置")
                .setContentText(digits + "（第一次須比對，之後使用安全金鑰）")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build())
        } catch (_: SecurityException) {
            ui("通知權限未開：請返回 AirGrab 確認首次配對數字")
        }
    }
    fun acceptPairing(yes: Boolean) {
        val id = awaitingPair ?: return
        awaitingPair = null
        pendingDigits = null
        if (yes) {
            manualNumericApproved = true
            client.acceptConnection(id, payloadCallback)
                .addOnFailureListener { failAuthentication("手動確認後仍連線失敗") }
        }
        else {
            client.rejectConnection(id)
            endpoint = null
            resetAuthentication()
            ui("已拒絕配對")
            main.postDelayed({ search() }, 1700)
        }
    }

    private fun clearOfferDelivery() {
        outgoingOffer = null
        offerDelivery = null
    }

    /**
     * Delivery confirmation is separate from "file/link prepared" and from
     * actual successful transfer. Retry ONLY the same session payload.
     */
    private fun sendAndRetryOffer(offer: JSONObject) {
        val sid = offer.optString("sid")
        val tracker = OfferDeliveryTracker(sid, SystemClock.uptimeMillis())
        outgoingOffer = offer
        offerDelivery = tracker
        if (tracker.recordAttempt(SystemClock.uptimeMillis())) control(offer)
        for (delayMs in listOf(1400L, 3200L, 6200L, 11000L, 17000L, 24000L)) {
            main.postDelayed({
                if (connected && busy && session == sid &&
                    outgoingOffer === offer && offerDelivery === tracker &&
                    tracker.recordAttempt(SystemClock.uptimeMillis())) {
                    ui("對方尚未確認請求，重新發送（第 " + tracker.attempts + " 次）")
                    control(offer)
                }
            }, delayMs)
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
        sendAndRetryOffer(JSONObject().put("type", "link_offer").put("sid", session).put("url", url))
        ui("網址已取得，等待對方確認（30 秒）")
        visual(VisualPhase.WAITING_FOR_RECEIVER, "已取得連結 · 尚未送達")
        val key = ++generation
        main.postDelayed({
            if (generation == key && busy) {
                val acknowledged = offerDelivery?.wasAcknowledged() == true
                busy = false
                clearOfferDelivery()
                ui(if (acknowledged) "對方已收到請求，但 30 秒內未確認接收"
                    else "對方未收到傳送請求，已逾時")
                visual(VisualPhase.FAILURE,
                    if (acknowledged) "接收端沒有完成接收" else "請求未送達對方")
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
                    sendAndRetryOffer(JSONObject().put("type", "file_offer").put("sid", session)
                        .put("name", name).put("size", candidate.length()).put("hash", sum))
                    ui("已取得：" + name + "；等待對方接收")
                    visual(VisualPhase.WAITING_FOR_RECEIVER, "已取得檔案 · 等待對方")
                    val key = ++generation
                    main.postDelayed({
                        if (generation == key && busy && payloadOutgoing < 0) {
                            val acknowledged = offerDelivery?.wasAcknowledged() == true
                            busy = false
                            clearOfferDelivery()
                            ui(if (acknowledged) "已送達接收端，但未在 30 秒內接收"
                                else "未收到接收端的請求確認")
                            visual(VisualPhase.FAILURE,
                                if (acknowledged) "接收端未確認" else "對方未收到傳送請求")
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
        val current = offered ?: run {
            ui("尚無待接收的檔案或網址")
            visual(VisualPhase.FAILURE, "尚無可接收內容")
            return
        }
        clearIncomingPrompt()
        setReceiveArmed(false)
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
                    try {
                        val bytes = p.asBytes() ?: return@post
                        if (bytes.size > 16_384) return@post
                        val json = JSONObject(String(bytes, Charsets.UTF_8))
                        when (json.optString("type")) {
                            "auth_hello" -> handleAuthHello(json)
                            "auth_proof" -> {
                                if (authPending && json.optString("sig").length in 40..512) {
                                    peerProof = json.optString("sig")
                                    verifyRemoteProofIfReady()
                                }
                            }
                            "auth_ready" -> if (authPending) {
                                peerConfirmedReady = true
                                finishAuthIfBothReady()
                            }
                            else -> if (connected) onControl(json)
                        }
                    }
                    catch (_: Exception) { ui("無效控制訊息已丟棄") }
                } else if (p.type == Payload.Type.FILE) {
                    if (!connected || offered !is Offer.FileOffer || !busy) {
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
                if (id != endpoint || !connected) return@post
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
                        setReceiveArmed(false)
                        clearIncomingPrompt()
                        ui("傳輸取消或失敗")
                        visual(VisualPhase.FAILURE, "傳輸中斷，未完成")
                    }
                    PayloadTransferUpdate.Status.IN_PROGRESS -> {
                        if (u.totalBytes > 0) {
                            val percent = ((100 * u.bytesTransferred / u.totalBytes).toInt())
                                .coerceIn(0, 100)
                            val now = SystemClock.uptimeMillis()
                            if (lastDisplayedPercent < 0 ||
                                (percent != lastDisplayedPercent &&
                                 (percent - lastDisplayedPercent >= 5 ||
                                  now - lastProgressStamp >= 650L || percent == 100))) {
                                lastDisplayedPercent = percent
                                lastProgressStamp = now
                                ui("傳輸中 " + percent + "%")
                                if (u.payloadId == payloadIncoming) {
                                    visual(VisualPhase.RECEIVING, "接收中 " + percent + "%", percent)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    private fun onControl(j: JSONObject) {
        val sid = j.optString("sid")
        when (j.optString("type")) {
            "offer_seen" -> if (sid == session && busy) {
                val tracker = offerDelivery
                if (tracker != null && tracker.acknowledge(sid)) {
                    ui("接收手機已收到請求，等待接收手勢")
                    visual(VisualPhase.WAITING_FOR_RECEIVER, "對方已收到 · 等待接收")
                }
            }
            "file_offer" -> {
                val name = j.optString("name")
                val count = j.optLong("size", -1)
                val sum = j.optString("hash")
                val duplicate = offered as? Offer.FileOffer
                if (duplicate != null && duplicate.sid == sid &&
                    duplicate.name == TransferPolicy.safeName(name) &&
                    duplicate.size == count && duplicate.hash == sum) {
                    control(JSONObject().put("type", "offer_seen").put("sid", sid))
                    return
                }
                if (busy || offered != null || !TransferPolicy.validFileOffer(sid, name, count, sum)) {
                    control(JSONObject().put("type", "busy").put("sid", sid)); return
                }
                offered = Offer.FileOffer(sid, TransferPolicy.safeName(name)!!, count, sum)
                incomingFile = null; incomingFinished = false; payloadIncoming = -1L
                setReceiveArmed(true)
                notifyIncomingOffer(name)
                control(JSONObject().put("type", "offer_seen").put("sid", sid))
                ui("收到檔案請求：" + name + "；握拳 → 張掌以接收")
                visual(VisualPhase.OFFER_RECEIVED, "收到檔案 · 握拳張掌接收")
                expireOffer(sid)
            }
            "link_offer" -> {
                val url = j.optString("url")
                val duplicate = offered as? Offer.LinkOffer
                if (duplicate != null && duplicate.sid == sid && duplicate.url == url) {
                    control(JSONObject().put("type", "offer_seen").put("sid", sid))
                    return
                }
                if (busy || offered != null || !TransferPolicy.validLinkOffer(sid, url)) {
                    control(JSONObject().put("type", "busy").put("sid", sid)); return
                }
                offered = Offer.LinkOffer(sid, url)
                setReceiveArmed(true)
                notifyIncomingOffer("影片／網頁網址")
                control(JSONObject().put("type", "offer_seen").put("sid", sid))
                ui("收到影片或網頁網址；握拳 → 張掌以接收")
                visual(VisualPhase.OFFER_RECEIVED, "收到連結 · 握拳張掌接收")
                expireOffer(sid)
            }
            "file_ready" -> {
                if (!busy || sid != session || payloadOutgoing != -1L) return
                clearOfferDelivery()
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
                clearOfferDelivery()
                busy = false; ++generation
                ui("✓ 原檔 SHA-256 完整驗證，傳送成功")
                visual(VisualPhase.SEND_COMPLETE, "傳送完成 · 對方已驗證")
            }
            "file_invalid" -> if (sid == session) {
                clearOfferDelivery()
                busy = false; ui("接收端檔案完整性檢查失敗")
                visual(VisualPhase.FAILURE, "接收端驗證失敗")
            }
            "link_ack" -> if (sid == session) {
                clearOfferDelivery()
                busy = false; ++generation
                ui("✓ 對方已接收網址")
                visual(VisualPhase.SEND_COMPLETE, "網址已交給對方")
            }
            "busy" -> if (sid == session) {
                clearOfferDelivery()
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
                setReceiveArmed(false)
                clearIncomingPrompt()
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
