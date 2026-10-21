package tw.airgesture.transferbeta

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.bluetooth.BluetoothManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import org.json.JSONObject
import org.json.JSONArray
import tw.airgesture.transferbeta.core.AirGrabBundle
import tw.airgesture.transferbeta.core.NfcAutoAcceptPolicy
import tw.airgesture.transferbeta.core.GrabContentPolicy
import tw.airgesture.transferbeta.core.MediaFilePolicy
import tw.airgesture.transferbeta.core.MediaKind
import tw.airgesture.transferbeta.core.MultiReceiverBatch
import tw.airgesture.transferbeta.core.PeerIdentityProtocol
import tw.airgesture.transferbeta.core.TransferPolicy
import tw.airgesture.transferbeta.core.TransferSpeedTracker
import tw.airgesture.transferbeta.core.VideoCompatibilityPolicy
import tw.airgesture.transferbeta.core.VideoDescription
import tw.airgesture.transferbeta.core.VideoSendMode
import tw.airgesture.transferbeta.core.VisualEvent
import tw.airgesture.transferbeta.core.VisualPhase
import java.io.File
import java.security.KeyPair
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Independent Android multi-peer Nearby transport. Discovery uses P2P_CLUSTER,
 * with one challenge-bound ECDSA authentication state for EACH Nearby endpoint.
 *
 * An outgoing offer stays open for 30 seconds, including after the first receiver
 * has finished. Recipients join by their own gesture. Large file payloads are
 * serialized, and each recipient independently acknowledges SHA-256 verification.
 *
 * This service intentionally uses v3 discovery; never downgrade an authenticated
 * v3 connection into the incompatible one-peer v2 protocol.
 */
class MultiPeerTransferManager(private val app: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val client = Nearby.getConnectionsClient(app)
    private val trusted = TrustedPeerStore(app)
    private val service = "tw.airgesture.transferbeta.p2p.v3"
    private val deviceId: String by lazy {
        val prefs = app.getSharedPreferences("device", Context.MODE_PRIVATE)
        prefs.getString("id", null) ?: UUID.randomUUID().toString().take(12).also {
            prefs.edit().putString("id", it).apply()
        }
    }

    private class Peer(val endpoint: String, val hint: String) {
        var modelLabel = hint
        var nearbyReady = false
        var verified = false
        var supportsBundles = false
        var supportsQueue = false
        var authPending = false
        var awaitingPair = false
        var numericApproved = false
        var promptShown = false
        var channelToken: String? = null
        var localNonce: String? = null
        var remoteNonce: String? = null
        var localPublic: String? = null
        var remotePublic: String? = null
        var remoteProof: String? = null
        var ownProofVerified = false
        var remoteReady = false
        var keyPair: KeyPair? = null
        var authGeneration = 0
        var outgoingPayload = -1L
        var lastOfferSentAt = 0L
        var lastProgress = -1
        val speed = TransferSpeedTracker()
    }

    private sealed class Offered {
        abstract val sid: String
        data class FileOffer(
            override val sid: String, val name: String, val size: Long,
            val sha: String, val items: Int = 1
        ) : Offered()
        data class QueueOffer(override val sid: String, val files: List<ExpectedItem>) : Offered()
        data class LinkOffer(override val sid: String, val url: String) : Offered()
    }

    data class StagedFile(val file: File, val name: String, val precomputedSha256: String? = null)
    private data class ExpectedItem(val name: String, val size: Long, val sha: String)
    private data class PreparedItem(val file: File, val info: ExpectedItem)

    private class Outgoing(
        val sid: String,
        val json: JSONObject,
        val policy: MultiReceiverBatch,
        val file: File? = null,
        val url: String? = null,
        val deleteAfterTransfer: Boolean = false,
        val queue: List<PreparedItem>? = null,
        val targetPeerId: String? = null
    ) {
        var awaitingVerificationPayload = -1L
    }

    private data class PendingVideo(
        val sid: String, val source: File, val name: String,
        val description: VideoDescription, val mode: VideoSendMode,
        val replies: MutableMap<String, Boolean> = mutableMapOf()
    )

    private val peers = linkedMapOf<String, Peer>()

    private data class PendingNfcBind(
        val endpoint: String, val peerId: String, val sid: String,
        val callback: (Boolean, String, String) -> Unit
    )
    private var pendingNfcBind: PendingNfcBind? = null
    private var boundNfcTarget: Pair<String, String>? = null // peer id, one-use sid
    private var incomingNfcSession: Triple<String, String, Long>? = null

    private var active: Outgoing? = null
    private var offered: Offered? = null
    private var offeredBy: String? = null
    private var incomingFile: Payload? = null
    private var incomingQueueIndex = 0
    private var incomingPayload = -1L
    private val incomingSpeed = TransferSpeedTracker()
    private var incomingFinished = false
    private var incomingQueueAcknowledged = false
    private var busy = false
    private var started = false
    private var epoch = 0
    private var scanning = false
    private var scanEpoch = 0
    private var searchStartedAt = 0L
    private var fastDiscoveryUntil = 0L
    private var pendingVideo: PendingVideo? = null
    private var pendingFileCount = 1
    private var pendingOwnedFile: File? = null
    private var stagedQueue: List<StagedFile>? = null
    private var transcoder: CompatibilityVideoTranscoder? = null
    private var displayedPair: String? = null
    private var lastConnected = false
    private var trustedReconnectScheduled = false
    private var lastTrustedReconnectAt = 0L

    val isConnected: Boolean get() = peers.values.any { it.verified }
    val rememberedDeviceId: String? get() = trusted.trustedId()
    val rememberedDeviceIds: List<String> get() = trusted.rememberedIds()
    val connectedDeviceIds: List<String> get() =
        peers.values.filter { it.verified }.map { it.hint }
    val connectedDeviceNames: Map<String, String> get() =
        peers.values.filter { it.verified }.associate { it.hint to it.modelLabel }

    var status = "未啟動"
        private set
    var pendingDigits: String? = null
        private set
    var onConnectionState: ((Boolean) -> Unit)? = null
        set(value) { field = value; value?.invoke(isConnected) }
    var onStatus: ((String) -> Unit)? = null
        set(value) { field = value; value?.invoke(status) }
    var onBackgroundStatus: ((String) -> Unit)? = null
    var onVisualEvent: ((VisualEvent) -> Unit)? = null
    var onReceiverOfferState: ((Boolean) -> Unit)? = null
    var onPairing: ((String) -> Unit)? = null
        set(value) { field = value; pendingDigits?.let { value?.invoke(it) } }

    private fun ui(message: String) {
        main.post {
            status = message
            onStatus?.invoke(message)
            onBackgroundStatus?.invoke(message)
        }
    }
    private fun visual(phase: VisualPhase, message: String, progress: Int? = null) {
        main.post { onVisualEvent?.invoke(VisualEvent(phase, message, progress)) }
    }
    fun reportShareStarted(detail: String) {
        ui(detail); visual(VisualPhase.CAPTURING, detail)
    }
    fun reportCaptureFailure(message: String) {
        ui(message); visual(VisualPhase.FAILURE, message.take(75))
    }
    private fun updateConnectionCount() {
        val connected = isConnected
        if (connected != lastConnected) {
            lastConnected = connected
            onConnectionState?.invoke(connected)
        }
    }

    /** Suspend radio discovery while the screen is locked; preserve live endpoints. */
    @Volatile private var discoveryEnabled = true

    /** Bind the NFC contact to an already verified, remembered Nearby endpoint.
     * The receiver must prove it issued the one-time NFC nonce before any
     * targeted offer may be sent. Callback always returns on main thread.
     */
    fun bindNfcTap(peerId: String, nonce: String,
        callback: (Boolean, String, String) -> Unit) {
        main.post {
            val p = peers.values.firstOrNull { it.verified && it.hint == peerId }
            if (!started || p == null || pendingNfcBind != null ||
                !nonce.matches(Regex("[0-9a-f]{32}"))) {
                callback(false, "", "尚未和碰觸的手機建立已驗證連線，請先讓雙方亮屏並完成配對")
                return@post
            }
            val sid = UUID.randomUUID().toString()
            val pending = PendingNfcBind(p.endpoint, peerId, sid, callback)
            pendingNfcBind = pending
            sendControl(p, JSONObject().put("type", "nfc_bind_request")
                .put("sid", sid).put("nonce", nonce))
            main.postDelayed({
                if (pendingNfcBind === pending) {
                    pendingNfcBind = null
                    callback(false, "", "NFC 身分驗證逾時；確認兩支手機仍保持附近連線")
                }
            }, 10_000L)
        }
    }

    fun setDiscoveryEnabled(enabled: Boolean) {
        if (discoveryEnabled == enabled) return
        discoveryEnabled = enabled
        if (!started) return
        if (!enabled) {
            // Invalidate both old discovery and its scheduled future wakeup.
            scanEpoch++
            client.stopDiscovery()
            client.stopAdvertising()
            scanning = false
            ui("熄屏省電：暫停搜尋附近手機，現有連線仍保留")
        } else {
            // Unlock is a fresh opportunity to locate previously trusted devices.
            val now = SystemClock.uptimeMillis()
            searchStartedAt = now
            fastDiscoveryUntil = now + 120_000L
            search()
        }
    }

    /** Only called after the keyguard is actually unlocked. */
    fun resumeAfterUnlock(enabled: Boolean) {
        val wasEnabled = discoveryEnabled
        setDiscoveryEnabled(enabled)
        if (enabled && wasEnabled && started &&
            !isConnected && rememberedDeviceIds.isNotEmpty()) refresh()
    }

    fun start() {
        if (started) return
        started = true
        searchStartedAt = android.os.SystemClock.uptimeMillis()
        fastDiscoveryUntil = searchStartedAt + 120_000L
        if (discoveryEnabled) search()
        else ui("熄屏省電：待解鎖後搜尋附近手機")
    }

    fun stop() {
        if (!started) return
        started = false
        pendingNfcBind?.callback?.invoke(false, "", "背景傳檔服務已停止")
        pendingNfcBind = null
        incomingNfcSession = null
        boundNfcTarget = null
        epoch++
        scanEpoch++
        client.stopDiscovery()
        client.stopAdvertising()
        client.stopAllEndpoints()
        transcoder?.cancel()
        transcoder = null
        peers.clear()
        active?.let { batch ->
            if (batch.deleteAfterTransfer) batch.file?.delete()
            batch.queue?.forEach { it.file.delete() }
        }
        stagedQueue?.forEach { it.file.delete() }
        stagedQueue = null
        active = null
        pendingOwnedFile?.delete()
        pendingOwnedFile = null
        pendingFileCount = 1
        pendingVideo = null
        offered = null
        offeredBy = null
        incomingQueueIndex = 0
        busy = false
        closeIncomingPrompt()
        pendingDigits = null
        displayedPair = null
        onReceiverOfferState?.invoke(false)
        updateConnectionCount()
        onStatus = null
        onPairing = null
        onConnectionState = null
        onVisualEvent = null
        onReceiverOfferState = null
        worker.shutdown()
        ui("隔空抓取已停止")
    }

    /**
     * Trusted identity persists; scanning is needed only to FIND a disconnected
     * peer. Once connected, the existing endpoint remains alive with discovery
     * and advertising paused for long intervals. The first 2 minutes retain
     * the original discovery cadence for quick reconnection and first pairing.
     * A staggered late idle schedule avoids identical two-device blind spots.
     */
    private fun search() {
        if (!started || !discoveryEnabled) return
        val cycle = ++scanEpoch
        // Upcoming Nearby changes mean radios won't be enabled automatically.
        // Respect the device owner's radio settings; retry without polling ML.
        val wifiOn: Boolean? = try {
            (app.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled
        } catch (_: Exception) { null }
        val btOn: Boolean? = try {
            (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager)
                .adapter?.isEnabled
        } catch (_: Exception) { null }
        if (wifiOn == false && btOn == false) {
            ui("附近連線需啟用 Wi-Fi 或藍牙，15 秒後重試")
            scanning = false
            main.postDelayed({
                if (started && discoveryEnabled && cycle == scanEpoch) search()
            }, 15_000L)
            return
        }
        val name = "Beta|" + deviceId
        val options = Strategy.P2P_CLUSTER
        client.stopDiscovery()
        client.stopAdvertising()
        client.startAdvertising(name, service, lifecycle,
            AdvertisingOptions.Builder().setStrategy(options).build())
            .addOnFailureListener { ui("附近廣播不可用：" + it.message) }
        client.startDiscovery(service, discovery,
            DiscoveryOptions.Builder().setStrategy(options).build())
            .addOnFailureListener { ui("附近搜尋不可用：" + it.message) }
        scanning = true
        ui(if (wifiOn == false && btOn == true)
            "Wi-Fi 尚未啟用，目前以藍牙搜尋；開啟 Wi-Fi 可改善傳輸速度"
            else "正在搜尋已配對的附近裝置（目前 " + connectedDeviceIds.size + " 台連線）")
        val now = android.os.SystemClock.uptimeMillis()
        val connected = isConnected
        val idleTrusted = rememberedDeviceIds.isNotEmpty() &&
            now >= fastDiscoveryUntil && now - searchStartedAt >= 120_000L
        val searchWindow = when {
            connected -> 8_000L
            idleTrusted -> 12_000L
            else -> 18_000L
        }
        val restWindow = when {
            connected -> 48_000L
            idleTrusted -> 22_000L + ((now / 1000L + deviceId.hashCode().toLong()).and(7L) * 1000L)
            else -> 12_000L
        }
        main.postDelayed({
            if (!started || cycle != scanEpoch) return@postDelayed
            client.stopDiscovery()
            client.stopAdvertising()
            scanning = false
            main.postDelayed({
                if (started && discoveryEnabled && cycle == scanEpoch) search()
            }, restWindow)
        }, searchWindow)
    }

    fun refresh() {
        // Explicit user action resets to quick discovery for the next two minutes.
        fastDiscoveryUntil = android.os.SystemClock.uptimeMillis() + 120_000L
        if (started && discoveryEnabled) search()
        else ui(if (started) "請先解鎖螢幕，附近搜尋會自動恢復"
            else "請先啟動懸浮操作")
    }

    fun forgetTrustedDevice() {
        trusted.forget()
        ui("所有已記住的裝置已清除；重新連線時須逐台核對配對數字")
        for (p in peers.values.toList()) dropPeer(p, "已清除配對")
        if (started) search()
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(id: String, info: DiscoveredEndpointInfo) {
            main.post {
                if (!started || peers.size >= 8) return@post
                val other = info.endpointName.substringAfter('|', "")
                if (!PeerIdentityProtocol.validPeerId(other) || other == deviceId ||
                    !info.endpointName.startsWith("Beta|")) return@post
                if (peers.values.any { it.hint == other }) return@post
                // One deterministic side initiates. Prevents duplicate peer
                // connections when three or more devices advertise at once.
                if (deviceId >= other) return@post
                val peer = Peer(id, other)
                peers[id] = peer
                client.requestConnection("Beta|" + deviceId, id, lifecycle)
                    .addOnFailureListener {
                        main.post { dropPeer(peer, "連線要求失敗") }
                    }
            }
        }
        override fun onEndpointLost(id: String) {}
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(id: String, info: ConnectionInfo) {
            main.post {
                val hinted = info.endpointName.substringAfter('|', "")
                if (!started || !info.endpointName.startsWith("Beta|") ||
                    !PeerIdentityProtocol.validPeerId(hinted) || hinted == deviceId ||
                    peers.size >= 8 && id !in peers ||
                    peers.values.any { it.endpoint != id && it.hint == hinted }) {
                    client.rejectConnection(id)
                    return@post
                }
                val peer = peers[id] ?: Peer(id, hinted).also { peers[id] = it }
                if (peer.hint != hinted) {
                    client.rejectConnection(id)
                    dropPeer(peer, "連線裝置識別不一致")
                    return@post
                }
                peer.channelToken = info.authenticationDigits
                if (trusted.isRecognizedHint(hinted)) {
                    client.acceptConnection(id, payloadCallback)
                        .addOnFailureListener {
                            main.post { dropPeer(peer, "無法連線已記住裝置") }
                        }
                } else {
                    peer.awaitingPair = true
                    showNextPairRequest()
                }
            }
        }
        override fun onConnectionResult(id: String, result: ConnectionResolution) {
            main.post {
                val peer = peers[id] ?: return@post
                if (!started) return@post
                if (!result.status.isSuccess) {
                    dropPeer(peer, "附近安全連線未建立")
                    return@post
                }
                peer.nearbyReady = true
                peer.awaitingPair = false
                showNextPairRequest()
                startAuthentication(peer)
            }
        }
        override fun onDisconnected(id: String) {
            main.post {
                peers[id]?.let { dropPeer(it, "裝置連線中斷") }
            }
        }
    }

    private fun showNextPairRequest() {
        val waiting = peers.values.firstOrNull { it.awaitingPair && !it.promptShown }
        if (displayedPair != null) return
        if (waiting == null) {
            pendingDigits = null
            return
        }
        val digits = waiting.channelToken ?: return
        displayedPair = waiting.endpoint
        waiting.promptShown = true
        pendingDigits = digits
        onPairing?.invoke(digits)
        ui("新裝置 " + waiting.hint.take(12) + "：請兩台核對 " + digits)
        notifyPairing(digits)
    }

    fun acceptPairing(yes: Boolean) {
        val id = displayedPair ?: return
        displayedPair = null
        pendingDigits = null
        val p = peers[id] ?: return
        if (!p.awaitingPair) return
        p.awaitingPair = false
        app.getSystemService(NotificationManager::class.java).cancel(4418)
        if (yes) {
            p.numericApproved = true
            client.acceptConnection(id, payloadCallback)
                .addOnFailureListener { main.post { dropPeer(p, "首次配對失敗") } }
        } else {
            client.rejectConnection(id)
            dropPeer(p, "已拒絕首次配對")
        }
        showNextPairRequest()
    }

    private fun notifyPairing(digits: String) {
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(
            "pair_needed", "隔空抓取配對確認", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(app, 114,
            Intent(app, TransferActivity::class.java)
                .putExtra("airgrab_show_settings", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        try {
            nm.notify(4418, Notification.Builder(app, "pair_needed")
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setContentTitle("AirGrab 新裝置配對")
                .setContentText("請確認雙方數字：" + digits)
                .setContentIntent(open).setAutoCancel(true).build())
        } catch (_: SecurityException) {
            ui("請開啟 AirGrab 確認裝置配對數字")
        }
    }

    /** Reconnect a dropped trusted peer without waiting for the slow idle scan cycle.
     * Coalesce bursts and limit retries to avoid constant radio scanning.
     */
    private fun scheduleTrustedReconnect() {
        if (!started || !discoveryEnabled || trustedReconnectScheduled ||
            rememberedDeviceIds.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        val delay = maxOf(750L, lastTrustedReconnectAt + 15_000L - now)
        trustedReconnectScheduled = true
        main.postDelayed({
            trustedReconnectScheduled = false
            if (!started || !discoveryEnabled ||
                rememberedDeviceIds.none { id -> peers.values.none { it.verified && it.hint == id } }
            ) return@postDelayed
            lastTrustedReconnectAt = SystemClock.uptimeMillis()
            fastDiscoveryUntil = lastTrustedReconnectAt + 120_000L
            searchStartedAt = lastTrustedReconnectAt
            search()
            ui("信任裝置已斷線，正在自動重新搜尋")
        }, delay)
    }

    private fun dropPeer(p: Peer, reason: String) {
        if (peers[p.endpoint] !== p) return
        peers.remove(p.endpoint)
        p.authGeneration++
        if (displayedPair == p.endpoint) {
            displayedPair = null
            pendingDigits = null
        }
        try { client.disconnectFromEndpoint(p.endpoint) } catch (_: Exception) {}
        if (pendingNfcBind?.endpoint == p.endpoint) {
            pendingNfcBind?.callback?.invoke(false, "", "NFC 驗證時附近裝置已斷線")
            pendingNfcBind = null
        }
        if (incomingNfcSession?.first == p.endpoint) incomingNfcSession = null
        if (boundNfcTarget?.first == p.hint) boundNfcTarget = null
        val batch = active
        if (batch != null && batch.policy.fail(p.endpoint)) {
            ui("裝置 " + p.hint + " 失敗：" + reason + "；其他裝置繼續")
            startNextFile()
            finishBroadcastIfDone(batch)
        }
        if (offeredBy == p.endpoint) {
            offeredBy = null
            offered = null
            incomingFile = null
            incomingPayload = -1
            busy = false
            closeIncomingPrompt()
            onReceiverOfferState?.invoke(false)
        }
        updateConnectionCount()
        if (displayedPair == null) showNextPairRequest()
        if (!isConnected && active == null && busy && offered == null) {
            ++epoch // abandon an unfinished file hashing/transcode request
            pendingVideo = null
            transcoder?.cancel()
            transcoder = null
            busy = false
        }
        if (started && peers.isEmpty()) ui("附近裝置已斷線，正在繼續搜尋")
        if (trusted.isRecognizedHint(p.hint)) scheduleTrustedReconnect()
    }

    private fun startAuthentication(p: Peer) {
        p.authPending = true
        val gen = ++p.authGeneration
        worker.execute {
            try {
                val kp = trusted.identity()
                val pub = PeerIdentityProtocol.encodedKey(kp.public)
                val nonce = trusted.randomNonce()
                main.post {
                    if (peers[p.endpoint] !== p || gen != p.authGeneration ||
                        !p.authPending || !started) return@post
                    p.keyPair = kp
                    p.localPublic = pub
                    p.localNonce = nonce
                    fun hello(): JSONObject = JSONObject()
                        .put("type", "auth_hello")
                        .put("v", PeerIdentityProtocol.PROTOCOL_VERSION)
                        .put("id", deviceId).put("pub", pub).put("nonce", nonce)
                        .put("model", Build.MODEL.take(40))
                    sendControl(p, hello())
                    for (delay in listOf(450L, 1100L, 2400L, 4300L)) {
                        main.postDelayed({
                            if (p.authPending && gen == p.authGeneration &&
                                peers[p.endpoint] === p) sendControl(p, hello())
                        }, delay)
                    }
                    sendLocalProof(p)
                    verifyRemoteProof(p)
                }
            } catch (_: Exception) {
                main.post { dropPeer(p, "無法讀取裝置簽章金鑰") }
            }
        }
        main.postDelayed({
            if (peers[p.endpoint] === p && p.authPending &&
                gen == p.authGeneration) dropPeer(p, "驗證逾時")
        }, 13_000L)
    }

    private fun handleHello(p: Peer, json: JSONObject) {
        // Delayed copies of a hello are normal after the peer is authenticated.
        if (p.verified || !p.authPending) return
        if (json.optInt("v") != PeerIdentityProtocol.PROTOCOL_VERSION) {
            dropPeer(p, "身份驗證協定不相容")
            return
        }
        val id = json.optString("id")
        val pub = json.optString("pub")
        val nonce = json.optString("nonce")
        if (id != p.hint || !PeerIdentityProtocol.validNonce(nonce)) {
            dropPeer(p, "裝置聲稱身份不符")
            return
        }
        try { PeerIdentityProtocol.parseKey(pub) }
        catch (_: Exception) { dropPeer(p, "無效的身份公鑰"); return }
        if (trusted.isRecognizedHint(id) && !trusted.isTrusted(id, pub)) {
            dropPeer(p, "已記住裝置的金鑰變更")
            return
        }
        if (!trusted.isRecognizedHint(id) && !p.numericApproved) {
            dropPeer(p, "陌生裝置未核對數字")
            return
        }
        if (p.remotePublic != null && (p.remotePublic != pub || p.remoteNonce != nonce)) {
            dropPeer(p, "認證期間身份資料不一致")
            return
        }
        p.remotePublic = pub
        p.remoteNonce = nonce
        p.modelLabel = json.optString("model").trim().take(32)
            .ifBlank { p.hint }
        sendLocalProof(p)
        verifyRemoteProof(p)
    }

    private fun sendLocalProof(p: Peer) {
        val otherPub = p.remotePublic ?: return
        val otherNonce = p.remoteNonce ?: return
        val ourNonce = p.localNonce ?: return
        val ourPub = p.localPublic ?: return
        val kp = p.keyPair ?: return
        val token = p.channelToken ?: return
        worker.execute {
            try {
                val proof = PeerIdentityProtocol.proofMessage(
                    deviceId, p.hint, ourPub, otherPub, ourNonce, otherNonce, token)
                val signed = PeerIdentityProtocol.sign(kp, proof)
                main.post {
                    if (peers[p.endpoint] === p && p.authPending &&
                        p.remotePublic == otherPub && p.remoteNonce == otherNonce)
                        sendControl(p, JSONObject().put("type", "auth_proof")
                            .put("sig", signed))
                }
            } catch (_: Exception) {
                main.post { dropPeer(p, "裝置簽章失敗") }
            }
        }
    }

    private fun verifyRemoteProof(p: Peer) {
        if (!p.authPending) return
        val signature = p.remoteProof ?: return
        val otherPub = p.remotePublic ?: return
        val otherNonce = p.remoteNonce ?: return
        val ourNonce = p.localNonce ?: return
        val ourPub = p.localPublic ?: return
        val token = p.channelToken ?: return
        val proof = try {
            PeerIdentityProtocol.proofMessage(p.hint, deviceId,
                otherPub, ourPub, otherNonce, ourNonce, token)
        } catch (_: Exception) {
            dropPeer(p, "裝置身分驗證資料損壞")
            return
        }
        if (!PeerIdentityProtocol.verify(otherPub, proof, signature)) {
            dropPeer(p, "身份簽章驗證失敗")
            return
        }
        if (!trusted.isTrusted(p.hint, otherPub)) {
            if (!p.numericApproved) {
                dropPeer(p, "未授權的裝置")
                return
            }
            try { trusted.pinAfterVerifiedFirstPairing(p.hint, otherPub) }
            catch (_: Exception) { dropPeer(p, "無法記住裝置金鑰"); return }
        }
        if (!p.ownProofVerified) {
            p.ownProofVerified = true
            sendControl(p, JSONObject().put("type", "auth_ready")
                .put("bundle_v1", true).put("queue_v1", true))
        }
        finishAuthentication(p)
    }

    private fun finishAuthentication(p: Peer) {
        if (!p.authPending || !p.ownProofVerified || !p.remoteReady) return
        p.authPending = false
        p.verified = true
        updateConnectionCount()
        ui("已安全連線 " + connectedDeviceIds.size + " 台裝置（" +
            connectedDeviceNames.values.joinToString("、") + "）")
        // A newly authenticated peer may still join the open 30-second offer.
        active?.let { announceToPeer(it, p) }
    }

    private fun sendControl(p: Peer, json: JSONObject) {
        if (peers[p.endpoint] !== p || !p.nearbyReady) return
        val encoded = json.toString().toByteArray(Charsets.UTF_8)
        if (encoded.size > 16_384) return
        client.sendPayload(p.endpoint, Payload.fromBytes(encoded))
            .addOnFailureListener {
                ui("對 " + p.hint + " 傳送控制訊息失敗：" + it.message)
            }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(131072)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun beginBroadcast(
        json: JSONObject, file: File? = null, url: String? = null,
        deleteAfterTransfer: Boolean = false,
        queue: List<PreparedItem>? = null,
        targetPeerId: String? = null
    ) {
        val now = SystemClock.uptimeMillis()
        val sid = json.getString("sid")
        val batch = Outgoing(sid, json,
            MultiReceiverBatch(sid, now, fileCount = queue?.size ?: 1),
            file, url, deleteAfterTransfer, queue, targetPeerId)
        active = batch
        for (p in peers.values) announceToPeer(batch, p)
        ui(if (targetPeerId != null) "NFC 已指定接收裝置，等待原檔完成接收"
            else "已開放 30 秒多人接收；連線 " + connectedDeviceIds.size + " 台")
        visual(VisualPhase.WAITING_FOR_RECEIVER, if (targetPeerId != null)
            "NFC 碰觸驗證完成 · 正在傳給指定裝置"
            else "已分享 · 30 秒內多台可接收")
        main.postDelayed({
            if (active !== batch) return@postDelayed
            batch.policy.endUnclaimed(SystemClock.uptimeMillis())
            ui("30 秒接收時間已結束；已加入的裝置繼續傳送")
            startNextFile()
            finishBroadcastIfDone(batch)
        }, TransferPolicy.OFFER_TIMEOUT_MS)
    }

    private fun announceToPeer(batch: Outgoing, p: Peer) {
        if (!p.verified || active !== batch ||
            (batch.targetPeerId != null && batch.targetPeerId != p.hint)) return
        if (batch.queue != null && !p.supportsQueue) return
        if (batch.json.optInt("items", 1) > 1 && !p.supportsBundles) return
        val now = SystemClock.uptimeMillis()
        if (batch.policy.announce(p.endpoint, now)) {
            sendControl(p, batch.json)
            retryOffer(batch, p, 0)
        }
    }

    private fun retryOffer(batch: Outgoing, p: Peer, index: Int) {
        val delays = listOf(1400L, 3200L, 6200L, 11000L, 17000L, 24000L)
        if (index >= delays.size) return
        main.postDelayed({
            if (active !== batch || peers[p.endpoint] !== p || !p.verified ||
                !batch.policy.canAccept(SystemClock.uptimeMillis()) ||
                batch.policy.state(p.endpoint) != MultiReceiverBatch.State.OFFERED)
                return@postDelayed
            sendControl(p, batch.json)
            retryOffer(batch, p, index + 1)
        }, if (index == 0) delays[0] else delays[index] - delays[index - 1])
    }

    private fun finishBroadcastIfDone(batch: Outgoing) {
        if (active !== batch || !batch.policy.isFinished(SystemClock.uptimeMillis())) return
        val successes = batch.policy.completedCount()
        val failures = batch.policy.failedCount()
        active = null
        busy = false
        if (batch.deleteAfterTransfer) batch.file?.delete()
        batch.queue?.forEach { it.file.delete() }
        val items = batch.queue?.size ?: batch.json.optInt("items", 1)
        ui("多人傳送結束：每台 " + items + " 個檔案；成功 " +
            successes + " 台，未完成 " + failures + " 台")
        visual(if (successes > 0) VisualPhase.SEND_COMPLETE else VisualPhase.FAILURE,
            "成功 " + successes + " 台 · 失敗或未接收 " + failures + " 台")
    }

    /** NFC workflow: only send to the VERIFIED peer selected by actual contact.
     * This separate path never transcodes: PHOTO/VIDEO/DOCUMENT stay byte-exact.
     */
    fun offerNfcFile(candidate: File, originalName: String,
        peerId: String, session: String) {
        val selected = boundNfcTarget
        boundNfcTarget = null
        fun reject(reason: String) {
            candidate.delete()
            reportCaptureFailure(reason)
        }
        if (selected != (peerId to session) || !started || busy ||
            peers.values.none { it.verified && it.hint == peerId }) {
            reject("NFC 接觸驗證無效或接收端已斷線")
            return
        }
        val name = TransferPolicy.safeName(originalName)
        if (name == null || !candidate.isFile || candidate.length() <= 0L ||
            candidate.length() > TransferPolicy.MAX_BYTES) {
            reject("NFC 原始檔不存在或不符合傳送限制")
            return
        }
        busy = true
        pendingOwnedFile = candidate
        val requestEpoch = ++epoch
        worker.execute {
            try {
                val hash = sha256(candidate)
                main.post {
                    if (!started || epoch != requestEpoch || !busy ||
                        peers.values.none { it.verified && it.hint == peerId }) {
                        if (pendingOwnedFile === candidate) failPreparation("NFC 接收端已斷線")
                        return@post
                    }
                    pendingOwnedFile = null
                    beginBroadcast(JSONObject().put("type", "file_offer")
                        .put("sid", UUID.randomUUID().toString())
                        .put("name", name).put("size", candidate.length())
                        .put("hash", hash).put("items", 1)
                        .put("nfc_session", session),
                        file = candidate, deleteAfterTransfer = true,
                        targetPeerId = peerId)
                }
            } catch (error: Exception) {
                main.post { if (requestEpoch == epoch)
                    failPreparation("NFC 原檔 SHA-256 準備失敗：" + error.message) }
            }
        }
    }

    fun offerNfcLink(raw: String, peerId: String, session: String) {
        val selected = boundNfcTarget
        boundNfcTarget = null
        val url = GrabContentPolicy.safeWebUrl(raw)
        if (selected != (peerId to session) || url == null ||
            !started || busy || peers.values.none { it.verified && it.hint == peerId }) {
            reportCaptureFailure("NFC 網址無效或接收裝置已離線")
            return
        }
        busy = true
        beginBroadcast(JSONObject().put("type", "link_offer")
            .put("sid", UUID.randomUUID().toString())
            .put("url", url).put("nfc_session", session),
            url = url, targetPeerId = peerId)
    }

    fun offerLink(raw: String) {
        val url = GrabContentPolicy.safeWebUrl(raw)
        if (url == null) {
            reportCaptureFailure("找不到有效網址，無法分享")
            return
        }
        if (!isConnected || busy) {
            reportCaptureFailure("尚未連線或上一份內容正在傳送")
            return
        }
        busy = true
        val sid = UUID.randomUUID().toString()
        beginBroadcast(JSONObject().put("type", "link_offer")
            .put("sid", sid).put("url", url), url = url)
    }

    /**
     * One offer contains an ordered list of original, uncompressed files.
     * Each receiver opts in once and advances its own verified index. No
     * aggregate byte-size ceiling or ZIP/archival staging is involved.
     */
    /** NFC manual Sharesheet: one physical tap authenticates ONE chosen recipient,
     * then transfers 2–32 independent original files sequentially. No ZIP or
     * aggregate 2-GiB ceiling; non-touched peers receive no offer. */
    fun offerNfcFiles(candidates: List<StagedFile>, peerId: String, session: String) {
        val selected = boundNfcTarget
        boundNfcTarget = null
        if (selected != (peerId to session)) {
            candidates.forEach { it.file.delete() }
            reportCaptureFailure("NFC 身分驗證已失效，請重新碰觸手機")
            return
        }
        offerQueuedFiles(candidates, peerId to session)
    }

    fun offerFiles(candidates: List<StagedFile>) =
        offerQueuedFiles(candidates, null)

    private fun offerQueuedFiles(
        candidates: List<StagedFile>, nfcTarget: Pair<String, String>?
    ) {
        fun reject(reason: String) {
            candidates.forEach { it.file.delete() }
            reportCaptureFailure(reason)
        }
        if (!started || !isConnected || busy) {
            reject("尚未連線或上一批傳輸尚未完成")
            return
        }
        if (candidates.size !in 2..AirGrabBundle.MAX_FILES) {
            reject("一次請選取 2 至 ${AirGrabBundle.MAX_FILES} 個檔案")
            return
        }
        if (nfcTarget != null &&
            peers.values.none {
                it.verified && it.hint == nfcTarget.first && it.supportsQueue
            }) {
            reject("NFC 指定接收端未連線或不支援逐檔傳送；請更新接收端")
            return
        }
        if (nfcTarget == null && peers.values.none { it.verified && it.supportsQueue }) {
            reject("接收端版本尚未支援逐檔傳送，請更新兩台裝置")
            return
        }
        if (candidates.any { it.file.length() <= 0L || !it.file.isFile ||
            it.file.length() > TransferPolicy.MAX_BYTES ||
            TransferPolicy.safeName(it.name) == null }) {
            reject("包含無法讀取、空白或不支援的原始檔案")
            return
        }
        busy = true
        stagedQueue = candidates.toList()
        val requestEpoch = ++epoch
        ui("準備逐檔佇列 · 0/${candidates.size} · 正在驗證原檔")
        worker.execute {
            try {
                val ready = candidates.mapIndexed { i, staged ->
                    val name = TransferPolicy.safeName(staged.name)
                        ?: error("原始檔名無效")
                    val hash = staged.precomputedSha256?.takeIf {
                        it.matches(Regex("[0-9a-f]{64}"))
                    } ?: sha256(staged.file)
                    val item = PreparedItem(staged.file,
                        ExpectedItem(name, staged.file.length(), hash))
                    ui("準備原始檔 ${i + 1}/${candidates.size}")
                    item
                }
                val sid = UUID.randomUUID().toString()
                val list = JSONArray()
                for (item in ready) {
                    list.put(JSONObject().put("name", item.info.name)
                        .put("size", item.info.size).put("hash", item.info.sha))
                }
                val offer = JSONObject().put("type", "queue_offer")
                    .put("sid", sid).put("files", list)
                if (nfcTarget != null) offer.put("nfc_session", nfcTarget.second)
                require(offer.toString().toByteArray(Charsets.UTF_8).size <= 16_384) {
                    "檔案名稱太長，無法建立傳輸清單"
                }
                main.post {
                    if (!started || !busy || !isConnected || epoch != requestEpoch ||
                        stagedQueue == null || (nfcTarget != null &&
                            peers.values.none { it.verified && it.hint == nfcTarget.first &&
                                it.supportsQueue })) {
                        // A stopped or replaced session owns cleanup.
                        return@post
                    }
                    stagedQueue = null
                    beginBroadcast(offer, queue = ready, targetPeerId = nfcTarget?.first)
                    ui(if (nfcTarget != null)
                        "NFC 已指定接收裝置，${ready.size} 個原始檔逐檔傳送"
                        else "已建立 ${ready.size} 個檔案的逐檔佇列；30 秒內可加入接收")
                }
            } catch (error: Exception) {
                main.post {
                    if (epoch == requestEpoch) {
                        stagedQueue = null
                        candidates.forEach { it.file.delete() }
                        busy = false
                        ++epoch
                        reportCaptureFailure("建立逐檔佇列失敗：" +
                            (error.message ?: "讀取原始檔案失敗"))
                    }
                }
            }
        }
    }

    fun offerFile(
        candidate: File, originalName: String,
        multiCount: Int = 1, deleteWhenDone: Boolean = false
    ) {
        fun reject(reason: String) {
            if (deleteWhenDone) candidate.delete()
            reportCaptureFailure(reason)
        }
        if (!isConnected || busy) {
            reject("尚未連線或上一份檔案正在傳送")
            return
        }
        if (!candidate.isFile || candidate.length() <= 0 ||
            candidate.length() > TransferPolicy.MAX_BYTES) {
            reject("檔案不存在、為空或超過 " + TransferPolicy.capacityLabel())
            return
        }
        val name = TransferPolicy.safeName(originalName)
            ?: run { reject("檔案名稱無效"); return }
        if (multiCount != 1 && (multiCount !in 2..AirGrabBundle.MAX_FILES ||
            !AirGrabBundle.isBundle(name))) {
            reject("多檔封裝格式或數量無效")
            return
        }
        if (multiCount == 1 && AirGrabBundle.isBundle(name)) {
            reject("不允許以單檔模式傳送 AirGrab 封裝")
            return
        }
        if (multiCount > 1 && peers.values.none { it.verified && it.supportsBundles }) {
            reject("接收端尚未支援多檔傳輸，請先更新兩台手機")
            return
        }
        busy = true
        pendingFileCount = multiCount
        pendingOwnedFile = if (deleteWhenDone) candidate else null
        val requestEpoch = ++epoch
        val mode = VideoSendMode.fromKey(app.getSharedPreferences(
            "video_transfer", Context.MODE_PRIVATE).getString("mode", "auto"))
        worker.execute {
            // A multi-file archive is intentionally never transcoded or probed
            // as one video; each original's bytes must remain unmodified.
            val description = if (multiCount > 1) null
                else VideoFormatInspector.inspect(candidate)
            main.post {
                if (!started || !isConnected || !busy || requestEpoch != epoch) return@post
                val supportedContainer = name.endsWith(".mp4", true) ||
                    name.endsWith(".m4v", true) || name.endsWith(".mov", true)
                if (description != null && supportedContainer &&
                    mode != VideoSendMode.ORIGINAL) {
                    val video = PendingVideo(UUID.randomUUID().toString(), candidate,
                        name, description, mode)
                    pendingVideo = video
                    if (mode == VideoSendMode.COMPATIBLE ||
                        VideoCompatibilityPolicy.isRiskyVideo(description)) {
                        finishProbe(video)
                    } else {
                        for (p in peers.values.filter { it.verified }) {
                            sendControl(p, JSONObject().put("type", "video_probe")
                                .put("sid", video.sid).put("mime", description.mime)
                                .put("width", description.width)
                                .put("height", description.height)
                                .put("hdr", description.hdr))
                        }
                        ui("正在確認多台接收裝置的影片編碼能力")
                        main.postDelayed({ finishProbe(video) }, 2400L)
                    }
                } else if (description != null && mode == VideoSendMode.COMPATIBLE &&
                    !supportedContainer) {
                    failPreparation("此格式無法安全轉成 MP4，請使用原檔模式")
                } else prepareFile(candidate, name, requestEpoch)
            }
        }
    }

    private fun finishProbe(video: PendingVideo) {
        if (pendingVideo !== video) return
        pendingVideo = null
        if (!isConnected || !started || !busy) return
        val allDecodable = peers.values.filter { it.verified }.all {
            video.replies[it.endpoint] == true
        }
        if (!VideoCompatibilityPolicy.shouldConvert(
                video.mode, video.description, allDecodable)) {
            prepareFile(video.source, video.name, epoch)
            return
        }
        ui("部分裝置可能不支援此影片，正在轉換 H.264／MP4")
        visual(VisualPhase.CAPTURING, "正在產生相容影片 · 原檔保留")
        val instance = CompatibilityVideoTranscoder(app)
        transcoder = instance
        instance.export(video.source, video.description, { result ->
            if (transcoder !== instance || !started || !isConnected || !busy) {
                result.delete()
                return@export
            }
            transcoder = null
            if (result.length() > TransferPolicy.MAX_BYTES) {
                result.delete()
                failPreparation("轉檔後超過 " + TransferPolicy.capacityLabel())
            } else {
                prepareFile(result,
                    VideoCompatibilityPolicy.compatibleName(video.name), epoch)
            }
        }, { error ->
            if (transcoder === instance) {
                transcoder = null
                failPreparation("影片轉換失敗：" + error)
            }
        })
    }

    private fun failPreparation(reason: String) {
        busy = false
        pendingVideo = null
        pendingOwnedFile?.delete()
        pendingOwnedFile = null
        pendingFileCount = 1
        ++epoch
        reportCaptureFailure(reason)
    }

    private fun prepareFile(file: File, name: String, requestEpoch: Int) {
        worker.execute {
            try {
                val sha = sha256(file)
                main.post {
                    if (!started || !isConnected || !busy ||
                        requestEpoch != epoch) return@post
                    val sid = UUID.randomUUID().toString()
                    val itemCount = pendingFileCount
                    val owned = pendingOwnedFile === file
                    pendingOwnedFile = null
                    pendingFileCount = 1
                    beginBroadcast(JSONObject().put("type", "file_offer")
                        .put("sid", sid).put("name", name)
                        .put("size", file.length()).put("hash", sha)
                        .put("items", itemCount), file = file, deleteAfterTransfer = owned)
                }
            } catch (e: Exception) {
                main.post { if (requestEpoch == epoch)
                    failPreparation("無法準備原始檔案：" + e.message) }
            }
        }
    }

    private fun enoughStorageForIncoming(size: Long): Boolean = try {
        val root = Environment.getExternalStorageDirectory()
        val available = StatFs(root.absolutePath).availableBytes
        TransferPolicy.hasRoom(available, size)
    } catch (_: Exception) {
        // Storage access might be restricted; the MediaStore writer still
        // rejects storage exhaustion, and failed files are deleted.
        true
    }

    fun confirmReceive() {
        val current = offered ?: run {
            reportCaptureFailure("尚無可接收內容")
            return
        }
        val p = offeredBy?.let { peers[it] }
        if (p?.verified != true || busy) {
            reportCaptureFailure("接收裝置已離線或正在傳輸")
            return
        }
        val firstFileBytes = when (current) {
            is Offered.FileOffer -> current.size
            is Offered.QueueOffer -> current.files.first().size
            else -> 0L
        }
        if (firstFileBytes > 0L && !enoughStorageForIncoming(firstFileBytes)) {
            reportCaptureFailure("接收空間不足，請先清理裝置儲存空間")
            return
        }
        busy = true
        closeIncomingPrompt()
        onReceiverOfferState?.invoke(false)
        when (current) {
            is Offered.LinkOffer -> {
                sendControl(p, JSONObject().put("type", "link_ack")
                    .put("sid", current.sid))
                notifyLink(current.url)
                ui("已接收網址，請點通知開啟")
                visual(VisualPhase.RECEIVE_COMPLETE, "收到連結 · 點擊通知開啟")
                offered = null
                offeredBy = null
                busy = false
            }
            is Offered.QueueOffer, is Offered.FileOffer -> {
                val sid = current.sid
                incomingQueueAcknowledged = false
                val request = JSONObject().put("type", "file_ready").put("sid", sid)
                sendControl(p, request)
                for (wait in listOf(1100L, 2500L, 4500L, 8000L, 13000L, 19000L, 26000L)) {
                    main.postDelayed({
                        if (busy && offered === current && offeredBy == p.endpoint &&
                            !incomingQueueAcknowledged && incomingPayload == -1L &&
                            p.verified) {
                            sendControl(p, request)
                        }
                    }, wait)
                }
                ui("已發送接收手勢，正在等待來源裝置確認")
                visual(VisualPhase.RECEIVING, "接收請求已送出 · 等待回應", 0)
            }
        }
    }

    private fun closeIncomingPrompt() {
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
        try {
            notifications.notify(4431, Notification.Builder(app, "airgrab_receives")
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setContentTitle("AirGrab 等待接收")
                .setContentText(label.take(50) + " · 30 秒內握拳張掌")
                .setOngoing(true)
                .addAction(Notification.Action.Builder(android.R.drawable.ic_input_add,
                    "立即接收", accept).build()).build())
        } catch (_: SecurityException) {
            ui("通知權限未開，可直接用手勢接收")
        }
    }
    private fun notifyLink(url: String) {
        val safe = GrabContentPolicy.safeWebUrl(url) ?: return
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(
            "link_received", "隔空抓取網址", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(app, 115,
            Intent(Intent.ACTION_VIEW, Uri.parse(safe))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        try {
            nm.notify(4420, Notification.Builder(app, "link_received")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("已接收網頁連結")
                .setContentText("點擊在瀏覽器開啟")
                .setContentIntent(open).setAutoCancel(true).build())
        } catch (_: SecurityException) {}
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(id: String, payload: Payload) {
            main.post {
                val p = peers[id] ?: return@post
                when (payload.type) {
                    Payload.Type.BYTES -> {
                        try {
                            val bytes = payload.asBytes() ?: return@post
                            if (bytes.size > 16_384) return@post
                            val json = JSONObject(String(bytes, Charsets.UTF_8))
                            when (json.optString("type")) {
                                "auth_hello" -> if (p.authPending) handleHello(p, json)
                                "auth_proof" -> if (p.authPending) {
                                    val proof = json.optString("sig")
                                    if (proof.length in 40..512) {
                                        p.remoteProof = proof
                                        verifyRemoteProof(p)
                                    }
                                }
                                "auth_ready" -> if (p.authPending) {
                                    p.supportsBundles = json.optBoolean("bundle_v1", false)
                                    p.supportsQueue = json.optBoolean("queue_v1", false)
                                    p.remoteReady = true
                                    finishAuthentication(p)
                                }
                                else -> if (p.verified) handleControl(p, json)
                            }
                        } catch (_: Exception) {
                            ui("已拒絕異常的控制訊息")
                        }
                    }
                    Payload.Type.FILE -> {
                        if (!p.verified || !busy || offeredBy != p.endpoint ||
                            (offered !is Offered.FileOffer &&
                                offered !is Offered.QueueOffer) || incomingPayload != -1L) {
                            client.cancelPayload(payload.id)
                        } else if (offered is Offered.QueueOffer &&
                            !((offered as Offered.QueueOffer).files
                                .getOrNull(incomingQueueIndex)
                                ?.let { enoughStorageForIncoming(it.size) } == true)) {
                            client.cancelPayload(payload.id)
                            val offer = offered as Offered.QueueOffer
                            sendControl(p, JSONObject().put("type", "file_invalid")
                                .put("sid", offer.sid).put("index", incomingQueueIndex))
                            failIncoming("第 ${incomingQueueIndex + 1} 個檔案接收空間不足")
                        } else {
                            incomingFile = payload
                            incomingQueueAcknowledged = true
                            incomingPayload = payload.id
                            incomingSpeed.reset()
                            p.lastProgress = -1
                            if (incomingFinished) verifyFile()
                        }
                    }
                    else -> Unit
                }
            }
        }

        override fun onPayloadTransferUpdate(id: String, update: PayloadTransferUpdate) {
            main.post {
                val p = peers[id] ?: return@post
                val batch = active
                val isSending = batch != null && batch.policy.transferring() == id &&
                    p.outgoingPayload == update.payloadId
                val isReceiving = offeredBy == id && incomingPayload == update.payloadId
                if (!isSending && !isReceiving) return@post

                if (isReceiving && update.totalBytes > TransferPolicy.MAX_BYTES) {
                    client.cancelPayload(update.payloadId)
                    failIncoming("檔案超過 " + TransferPolicy.capacityLabel())
                    return@post
                }
                val expectedBytes = when (val activeOffer = offered) {
                    is Offered.FileOffer -> activeOffer.size
                    is Offered.QueueOffer -> activeOffer.files
                        .getOrNull(incomingQueueIndex)?.size
                    else -> null
                }
                if (isReceiving && expectedBytes != null && update.totalBytes >= 0 &&
                    update.totalBytes != expectedBytes) {
                    client.cancelPayload(update.payloadId)
                    failIncoming("傳入檔案大小不符合宣告")
                    return@post
                }

                when (update.status) {
                    PayloadTransferUpdate.Status.SUCCESS -> {
                        if (isReceiving) {
                            incomingFinished = true
                            if (incomingFile != null) verifyFile()
                        } else if (batch != null && batch.policy.bytesFinished(id)) {
                            ui("影片／檔案已送出，等待 " + p.hint + " 驗證 SHA-256")
                            val payloadId = update.payloadId
                            // Large original bundles need two sequential
                            // SHA-256 checks and MediaStore extraction. Give
                            // slow receivers more time without stalling forever.
                            val sizeMiB = ((batch.queue?.getOrNull(
                                batch.policy.nextIndex(id))?.info?.size
                                ?: batch.file?.length() ?: 0L) / (1024L * 1024L))
                                .coerceAtMost(20_000L)
                            val verifyTimeout = (60_000L + sizeMiB * 170L)
                                .coerceAtMost(600_000L)
                            main.postDelayed({
                                if (active === batch && p.outgoingPayload == payloadId &&
                                    batch.policy.transferring() == id &&
                                    batch.policy.state(id) == MultiReceiverBatch.State.VERIFYING) {
                                    failRecipient(batch, id, "接收端驗證逾時")
                                }
                            }, verifyTimeout)
                        }
                    }
                    PayloadTransferUpdate.Status.FAILURE,
                    PayloadTransferUpdate.Status.CANCELED -> {
                        if (isReceiving) failIncoming("傳輸失敗，請重新抓取")
                        else if (batch != null) failRecipient(batch, id, "傳輸中斷")
                    }
                    PayloadTransferUpdate.Status.IN_PROGRESS -> if (update.totalBytes > 0) {
                        val tracker = if (isReceiving) incomingSpeed else p.speed
                        val sample = tracker.observe(update.bytesTransferred,
                            update.totalBytes, SystemClock.uptimeMillis())
                        val percent = (update.bytesTransferred.toDouble() * 100.0 /
                            update.totalBytes).toInt().coerceIn(0, 100)
                        if (percent != p.lastProgress) {
                            p.lastProgress = percent
                            val details = TransferSpeedTracker.summary(sample)
                            val part = when (val offer = offered) {
                                is Offered.QueueOffer ->
                                    "第 ${incomingQueueIndex + 1}/${offer.files.size} 個 · "
                                else -> ""
                            }
                            val sendingPart = batch?.queue?.let {
                                "第 ${batch.policy.nextIndex(id) + 1}/${it.size} 個 · "
                            } ?: ""
                            ui(if (isReceiving) "${part}接收中 $percent%$details"
                                else "正在傳給 " + p.modelLabel +
                                    "：${sendingPart}$percent%$details")
                            if (isReceiving)
                                visual(VisualPhase.RECEIVING, "接收中 " + percent + "%", percent)
                        }
                    }
                }
            }
        }
    }

    private fun receiveOffer(p: Peer, offer: Offered, nfcSession: String? = null) {
        if (offered?.sid == offer.sid && offeredBy == p.endpoint) {
            sendControl(p, JSONObject().put("type", "offer_seen").put("sid", offer.sid))
            return
        }
        if (busy || offered != null) {
            sendControl(p, JSONObject().put("type", "busy").put("sid", offer.sid))
            return
        }
        offered = offer
        offeredBy = p.endpoint
        incomingFile = null
        incomingPayload = -1L
        incomingFinished = false
        incomingQueueAcknowledged = false
        incomingQueueIndex = 0
        onReceiverOfferState?.invoke(true)
        val name = when (offer) {
            is Offered.QueueOffer -> "${offer.files.size} 個原始檔案（逐檔接收）"
            is Offered.FileOffer -> if (offer.items > 1)
                "${offer.items} 個原始檔案" else offer.name
            is Offered.LinkOffer -> "網頁連結"
        }
        val permit = incomingNfcSession
        val autoAccept = NfcAutoAcceptPolicy.permits(
            permit?.first, permit?.second, permit?.third ?: 0L,
            p.endpoint, nfcSession, SystemClock.uptimeMillis(), p.verified)
        if (autoAccept) incomingNfcSession = null
        if (!autoAccept) notifyIncomingOffer(name)
        sendControl(p, JSONObject().put("type", "offer_seen").put("sid", offer.sid))
        if (autoAccept) {
            ui("NFC 已驗證碰觸的已配對裝置，開始接收原始內容")
            confirmReceive()
            return
        }
        ui("收到 " + name + " · 30 秒內握拳→張掌即可接收")
        visual(VisualPhase.OFFER_RECEIVED, "收到分享 · 握拳→張掌接收")
        main.postDelayed({
            if (offered === offer && offeredBy == p.endpoint && !busy) {
                offered = null
                offeredBy = null
                closeIncomingPrompt()
                onReceiverOfferState?.invoke(false)
                ui("接收請求逾時")
                visual(VisualPhase.FAILURE, "接收已逾時")
            }
        }, TransferPolicy.OFFER_TIMEOUT_MS)
    }

    private fun handleControl(p: Peer, json: JSONObject) {
        val sid = json.optString("sid")
        when (json.optString("type")) {
            "nfc_bind_request" -> {
                val nonce = json.optString("nonce")
                if (sid.length !in 12..100 || !p.verified) return
                val allowed = NfcTapSessionStore.consume(app, nonce)
                if (allowed) incomingNfcSession = Triple(
                    p.endpoint, sid, SystemClock.uptimeMillis() + 30_000L)
                sendControl(p, JSONObject().put("type",
                    if (allowed) "nfc_bind_ok" else "nfc_bind_reject").put("sid", sid))
            }
            "nfc_bind_ok", "nfc_bind_reject" -> {
                val pending = pendingNfcBind ?: return
                if (pending.endpoint != p.endpoint || pending.sid != sid) return
                pendingNfcBind = null
                val accepted = json.optString("type") == "nfc_bind_ok"
                if (accepted) boundNfcTarget = pending.peerId to pending.sid
                pending.callback(accepted, if (accepted) pending.sid else "",
                    if (accepted) "已與指定已配對裝置完成 NFC 身分驗證"
                    else "NFC 一次性驗證失敗，請重新碰觸手機")
            }
            "video_probe" -> {
                val mime = json.optString("mime")
                val width = json.optInt("width")
                val height = json.optInt("height")
                if (sid.length !in 12..100 || !mime.startsWith("video/") ||
                    width !in 1..8192 || height !in 1..8192) return
                val info = VideoDescription(mime, width, height,
                    json.optBoolean("hdr", false))
                val support = VideoFormatInspector.receiverCanDecode(info) &&
                    !VideoCompatibilityPolicy.isRiskyVideo(info)
                sendControl(p, JSONObject().put("type", "video_probe_result")
                    .put("sid", sid).put("compatible", support))
            }
            "video_probe_result" -> {
                val waiting = pendingVideo
                if (waiting != null && waiting.sid == sid) {
                    waiting.replies[p.endpoint] = json.optBoolean("compatible", false)
                    if (peers.values.filter { it.verified }.all {
                        waiting.replies.containsKey(it.endpoint) }) finishProbe(waiting)
                }
            }
            "queue_offer" -> {
                if (!p.supportsQueue || sid.length !in 12..100) return
                val raw = json.optJSONArray("files") ?: return
                if (raw.length() !in 2..AirGrabBundle.MAX_FILES) return
                val files = ArrayList<ExpectedItem>()
                for (i in 0 until raw.length()) {
                    val item = raw.optJSONObject(i) ?: return
                    val name = item.optString("name")
                    val size = item.optLong("size", -1L)
                    val hash = item.optString("hash")
                    if (!TransferPolicy.validFileOffer(sid, name, size, hash) ||
                        name != TransferPolicy.safeName(name) || size <= 0L ||
                        AirGrabBundle.isBundle(name)) return
                    files.add(ExpectedItem(name, size, hash))
                }
                receiveOffer(p, Offered.QueueOffer(sid, files),
                    json.optString("nfc_session").ifBlank { null })
            }
            "file_offer" -> {
                val name = json.optString("name")
                val size = json.optLong("size", -1)
                val hash = json.optString("hash")
                if (!TransferPolicy.validFileOffer(sid, name, size, hash)) return
                val items = json.optInt("items", 1)
                if (AirGrabBundle.isBundle(name)) {
                    if (items !in 2..AirGrabBundle.MAX_FILES || !p.supportsBundles)
                        return
                } else if (items != 1) return
                receiveOffer(p, Offered.FileOffer(sid,
                    TransferPolicy.safeName(name)!!, size, hash, items),
                    json.optString("nfc_session").ifBlank { null })
            }
            "link_offer" -> {
                val url = json.optString("url")
                if (!TransferPolicy.validLinkOffer(sid, url)) return
                receiveOffer(p, Offered.LinkOffer(sid, url),
                    json.optString("nfc_session").ifBlank { null })
            }
            "offer_seen" -> {
                val batch = active ?: return
                if (batch.sid == sid) {
                    batch.policy.seen(p.endpoint)
                    ui("已通知 " + batch.policy.members().size + " 台裝置等待接收")
                }
            }
            "file_ready" -> {
                val batch = active ?: return
                if (batch.sid != sid ||
                    (batch.file == null && batch.queue == null)) return
                if (batch.queue != null && !p.supportsQueue) return
                if (batch.policy.accept(p.endpoint, SystemClock.uptimeMillis())) {
                    ui(p.modelLabel + " 已加入傳輸佇列")
                    sendControl(p, JSONObject().put("type", "file_queued").put("sid", sid))
                    startNextFile()
                } else if (batch.policy.state(p.endpoint) in setOf(
                        MultiReceiverBatch.State.QUEUED,
                        MultiReceiverBatch.State.SENDING,
                        MultiReceiverBatch.State.VERIFYING)) {
                    // A retry of an already accepted request is idempotent.
                    sendControl(p, JSONObject().put("type", "file_queued").put("sid", sid))
                } else {
                    sendControl(p, JSONObject().put("type", "expired").put("sid", sid))
                }
            }
            "file_queued" -> if (offeredBy == p.endpoint && offered?.sid == sid && busy) {
                incomingQueueAcknowledged = true
                ui("發送端已加入隊列，等待輪到這台裝置")
            }
            "expired" -> if (offeredBy == p.endpoint && offered?.sid == sid) {
                failIncoming("已超過 30 秒接收期限")
            }
            "file_verified", "file_invalid" -> {
                val batch = active ?: return
                if (batch.sid != sid || batch.policy.transferring() != p.endpoint) return
                val index = batch.policy.nextIndex(p.endpoint)
                if (batch.queue != null && json.optInt("index", -1) != index)
                    return // stale or duplicate acknowledgement
                val ok = json.optString("type") == "file_verified"
                if (ok) {
                    if (batch.policy.complete(p.endpoint)) {
                        val verified = batch.policy.progress(p.endpoint)
                        val total = batch.queue?.size ?: 1
                        ui(p.modelLabel + "：已完成 ${verified}/${total} 個檔案")
                        if (verified == total) visual(VisualPhase.SEND_COMPLETE,
                            "裝置接收成功 · 其他接收者繼續")
                    }
                } else failRecipient(batch, p.endpoint,
                    "第 ${index + 1} 個原始檔 SHA-256 驗證失敗")
                startNextFile()
                finishBroadcastIfDone(batch)
            }
            "link_ack" -> {
                val batch = active ?: return
                if (batch.sid != sid || batch.url == null) return
                if (!batch.policy.accept(p.endpoint, SystemClock.uptimeMillis())) return
                if (batch.policy.claimNext() == p.endpoint &&
                    batch.policy.complete(p.endpoint)) {
                    ui("已傳給 " + batch.policy.completedCount() + " 台裝置 · 繼續等待其他裝置")
                    visual(VisualPhase.SEND_COMPLETE, "網址已交付 · 仍可加入接收")
                }
                finishBroadcastIfDone(batch)
            }
            "busy" -> {
                val batch = active ?: return
                if (batch.sid == sid) {
                    batch.policy.fail(p.endpoint)
                    finishBroadcastIfDone(batch)
                }
            }
        }
    }

    private fun startNextFile() {
        val batch = active ?: return
        if (batch.file == null && batch.queue == null) return
        val next = batch.policy.claimNext() ?: return
        val index = batch.policy.nextIndex(next)
        val part = batch.queue?.getOrNull(index)
        val file = part?.file ?: batch.file
        if (file == null) {
            failRecipient(batch, next, "佇列檔案不存在")
            return
        }
        val p = peers[next]
        if (p?.verified != true) {
            failRecipient(batch, next, "裝置已離線")
            return
        }
        try {
            val payload = Payload.fromFile(file)
            p.outgoingPayload = payload.id
            p.lastProgress = -1
            p.speed.reset()
            client.sendPayload(next, payload).addOnFailureListener {
                main.post {
                    if (active === batch) failRecipient(batch, next,
                        "無法送出檔案：" + it.message)
                }
            }
            val phase = batch.queue?.let {
                "第 ${index + 1}/${it.size} 個 · ${part?.info?.name}；"
            } ?: ""
            ui("開始傳給 " + p.modelLabel + "：" + phase +
                "其他接收端已排隊，互不影響")
        } catch (e: Exception) {
            failRecipient(batch, next, "檔案無法開啟：" + e.message)
        }
    }

    private fun failRecipient(batch: Outgoing, id: String, why: String) {
        if (active !== batch || !batch.policy.fail(id)) return
        peers[id]?.outgoingPayload = -1L
        ui("其中一台傳輸失敗：" + why + "；其他裝置繼續")
        startNextFile()
        finishBroadcastIfDone(batch)
    }

    private fun failIncoming(reason: String) {
        busy = false
        offered = null
        offeredBy = null
        incomingFile = null
        incomingPayload = -1
        incomingFinished = false
        incomingQueueAcknowledged = false
        incomingQueueIndex = 0
        closeIncomingPrompt()
        onReceiverOfferState?.invoke(false)
        reportCaptureFailure(reason)
    }

    /** A multi-file package is verified twice: first the authenticated outer
     * SHA-256 envelope, then each original SHA-256 from the internal manifest.
     * MediaStore rows remain hidden (IS_PENDING=1) until EVERY file passes.
     * Any failure removes all rows, including partially published ones. */
    private fun verifyBundle(
        expected: Offered.FileOffer, owner: String, payload: Payload
    ) {
        visual(VisualPhase.VERIFYING, "正在驗證 ${expected.items} 個原始檔案")
        worker.execute {
            val created = mutableListOf<Uri>()
            try {
                val source = payload.asFile()?.asUri()
                    ?: error("多檔傳輸內容不存在")
                val digest = MessageDigest.getInstance("SHA-256")
                var outerBytes = 0L
                app.contentResolver.openInputStream(source)?.use { input ->
                    val bytes = ByteArray(128 * 1024)
                    while (true) {
                        val count = input.read(bytes)
                        if (count < 0) break
                        if (count == 0) continue
                        outerBytes += count
                        check(outerBytes <= expected.size &&
                            outerBytes <= TransferPolicy.MAX_BYTES)
                        digest.update(bytes, 0, count)
                    }
                } ?: error("無法讀取多檔傳輸包")
                val outerHash = digest.digest().joinToString("") {
                    "%02x".format(it)
                }
                check(TransferPolicy.matchingHash(expected.sha,
                    outerHash, expected.size, outerBytes)) {
                    "完整傳輸包 SHA-256 驗證失敗"
                }

                val records = app.contentResolver.openInputStream(source)?.use { input ->
                    AirGrabBundle.unpack(input, expected.items, { name ->
                        val spec = MediaFilePolicy.destination(name)
                        val collection = when (spec.kind) {
                            MediaKind.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                            MediaKind.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                            MediaKind.DOWNLOAD -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
                        }
                        val uri = app.contentResolver.insert(collection, ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                            put(MediaStore.MediaColumns.MIME_TYPE, spec.mime)
                            put(MediaStore.MediaColumns.RELATIVE_PATH, spec.folder + "/")
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                        }) ?: error("無法儲存原始檔案")
                        created.add(uri)
                        app.contentResolver.openOutputStream(uri, "w")
                            ?: error("無法寫入原始檔案")
                    }, { current, total ->
                        ui("正在還原原檔 ${current}/${total} · 逐檔核對 SHA-256")
                    }, maxUnpackedBytes = expected.size)
                } ?: error("無法開啟原始檔案封裝")
                check(records.size == expected.items)
                for (uri in created) {
                    check(app.contentResolver.update(uri, ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }, null, null) > 0) { "無法發布已驗證的檔案" }
                }
                main.post {
                    val peer = peers[owner]
                    if (offered !== expected || offeredBy != owner ||
                        peer?.verified != true) {
                        created.forEach {
                            try { app.contentResolver.delete(it, null, null) }
                            catch (_: Exception) {}
                        }
                        return@post
                    }
                    sendControl(peer, JSONObject().put("type", "file_verified")
                        .put("sid", expected.sid))
                    busy = false
                    offered = null
                    offeredBy = null
                    incomingPayload = -1L
                    incomingFinished = false
                    ui("已接收 ${records.size} 個原始檔案，逐檔 SHA-256 驗證成功")
                    visual(VisualPhase.RECEIVE_COMPLETE,
                        "${records.size} 個原始檔案已完整接收")
                }
            } catch (e: Exception) {
                created.asReversed().forEach {
                    try { app.contentResolver.delete(it, null, null) }
                    catch (_: Exception) {}
                }
                main.post {
                    if (offered !== expected || offeredBy != owner) return@post
                    peers[owner]?.takeIf { it.verified }?.let { peer ->
                        sendControl(peer, JSONObject().put("type", "file_invalid")
                            .put("sid", expected.sid))
                    }
                    failIncoming("多檔驗證失敗：" + (e.message ?: "檔案不完整"))
                }
            }
        }
    }

    private fun verifyFile() {
        val offer = offered ?: return
        val index = incomingQueueIndex
        val expected = when (offer) {
            is Offered.FileOffer -> offer
            is Offered.QueueOffer -> offer.files.getOrNull(index)?.let {
                Offered.FileOffer(offer.sid, it.name, it.size, it.sha)
            } ?: return
            else -> return
        }
        val owner = offeredBy ?: return
        val payload = incomingFile ?: return
        incomingFile = null // do not verify twice
        if (expected.items > 1) {
            verifyBundle(expected, owner, payload)
            return
        }
        visual(VisualPhase.VERIFYING, "接收完畢，正在驗證 SHA-256")
        worker.execute {
            var dest: Uri? = null
            try {
                val source = payload.asFile()?.asUri() ?: error("檔案內容不存在")
                val media = MediaFilePolicy.destination(expected.name)
                val collection = when (media.kind) {
                    MediaKind.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    MediaKind.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    MediaKind.DOWNLOAD -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
                }
                dest = app.contentResolver.insert(collection, ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, expected.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, media.mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, media.folder + "/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }) ?: error("無法建立接收檔")
                val sha = MessageDigest.getInstance("SHA-256")
                var size = 0L
                app.contentResolver.openInputStream(source)?.use { input ->
                    app.contentResolver.openOutputStream(dest!!, "w")?.use { output ->
                        val buffer = ByteArray(131072)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            size += read
                            if (size > TransferPolicy.MAX_BYTES) error("檔案過大")
                            sha.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                        }
                    } ?: error("無法儲存接收檔")
                } ?: error("無法讀取傳入的檔案")
                val digest = sha.digest().joinToString("") { "%02x".format(it) }
                check(TransferPolicy.matchingHash(
                    expected.sha, digest, expected.size, size)) {
                    "檔案雜湊或大小不正確"
                }
                check(app.contentResolver.update(dest!!, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }, null, null) > 0) { "已驗證檔案無法發布到相簿或下載資料夾" }
                val playback = if (media.kind == MediaKind.VIDEO)
                    VideoPlaybackProbe.explain(app, dest!!) else null
                main.post {
                    val p = peers[owner]
                    if (offered !== offer || offeredBy != owner ||
                        p?.verified != true || incomingQueueIndex != index) return@post
                    val ack = JSONObject().put("type", "file_verified")
                        .put("sid", expected.sid)
                    if (offer is Offered.QueueOffer) ack.put("index", index)
                    sendControl(p, ack)
                    incomingPayload = -1
                    incomingFinished = false
                    incomingQueueAcknowledged = true
                    if (offer is Offered.QueueOffer) {
                        incomingQueueIndex++
                        ui("已驗證 ${incomingQueueIndex}/${offer.files.size} 個原始檔")
                        if (incomingQueueIndex == offer.files.size) {
                            busy = false
                            offered = null
                            offeredBy = null
                            visual(VisualPhase.RECEIVE_COMPLETE,
                                "${offer.files.size} 個原檔已全部驗證")
                        }
                    } else {
                        busy = false
                        offered = null
                        offeredBy = null
                        ui(playback ?: ("已接收並驗證檔案：" + expected.name))
                        visual(VisualPhase.RECEIVE_COMPLETE,
                            "接收成功 · SHA-256 已驗證")
                    }
                }
            } catch (e: Exception) {
                try { dest?.let { app.contentResolver.delete(it, null, null) } }
                catch (_: Exception) {}
                main.post {
                    if (offered !== offer || offeredBy != owner) return@post
                    peers[owner]?.let {
                        val failure = JSONObject().put("type", "file_invalid")
                            .put("sid", expected.sid)
                        if (offer is Offered.QueueOffer) failure.put("index", index)
                        sendControl(it, failure)
                    }
                    failIncoming("第 ${index + 1} 個檔案驗證失敗：" + e.message +
                        "；已成功的檔案會保留")
                }
            }
        }
    }
}
