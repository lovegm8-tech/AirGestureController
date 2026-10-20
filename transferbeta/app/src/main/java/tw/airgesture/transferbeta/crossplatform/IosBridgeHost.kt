package tw.airgesture.transferbeta.crossplatform

import android.content.Context
import android.os.SystemClock
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * One explicitly armed, short-lived iPhone bridge session.
 * Owns NO Android Nearby peer, gesture state, or Android NFC HCE AID.
 */
object IosBridgeHost {
    private val worker = Executors.newSingleThreadExecutor()
    private data class Armed(
        val server: ServerSocket, val session: IosBridgeProtocol.Session,
        val payload: IosBridgePayload, val onStatus: (String) -> Unit
    )
    @Volatile private var current: Armed? = null

    private fun wlanIpv4(): ByteArray? {
        val networks = NetworkInterface.getNetworkInterfaces()?.toList() ?: return null
        return networks.filter { nic ->
            nic.isUp && !nic.isLoopback &&
                (nic.name.startsWith("wlan") || nic.name.startsWith("swlan") ||
                    nic.name.startsWith("ap"))
        }.flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }?.address
    }

    @Synchronized fun arm(context: Context, payload: IosBridgePayload,
                          onStatus: (String) -> Unit): String {
        stop()
        val ip = wlanIpv4() ?: error(
            "尚未取得 Wi-Fi 區域網路 IPv4；先讓 Android 與 iPhone 連上同一 Wi-Fi 或熱點")
        val socket = ServerSocket(0).also { it.soTimeout = IosBridgeProtocol.ARM_LIFETIME_MS.toInt() }
        try {
            val sid = IosBridgeProtocol.fresh(16)
            val nonce = IosBridgeProtocol.fresh(16)
            val secret = IosBridgeProtocol.fresh(32)
            val hello = IosBridgeProtocol.hello(
                sid, ip, socket.localPort, nonce, secret, IosBridgeIdentity.publicX963())
            val session = IosBridgeProtocol.Session(
                sid, nonce, secret, hello, SystemClock.elapsedRealtime())
            val armed = Armed(socket, session, payload, onStatus)
            current = armed
            worker.execute {
                try {
                    onStatus("跨平台 NFC 已待命；請在 iPhone 14 App 開啟讀卡並碰觸 Android 背面")
                    socket.accept().use { client ->
                        check(current === armed && !session.isExpired(SystemClock.elapsedRealtime()))
                        onStatus("iPhone Wi-Fi 已連線，驗證 NFC 會話與逐檔傳輸…")
                        onStatus(IosWifiTransfer(context.applicationContext)
                            .run(client, session, payload, onStatus))
                    }
                } catch (_: SocketTimeoutException) {
                    if (current === armed) onStatus("iPhone NFC 待命已逾時；請重新啟動")
                } catch (e: Exception) {
                    if (current === armed) onStatus("iPhone 傳送失敗：" +
                        (e.message ?: e.javaClass.simpleName))
                } finally {
                    if (current === armed) stop()
                }
            }
            return ip.joinToString(".") { (it.toInt() and 255).toString() } +
                ":" + socket.localPort
        } catch (e: Exception) {
            socket.close()
            throw e
        }
    }

    @Synchronized fun helloForReader(): ByteArray? {
        val a = current ?: return null
        return a.session.takeUnless {
            it.isExpired(SystemClock.elapsedRealtime()) || it.authorized
        }?.hello?.clone()
    }

    /** Called only by the DISTINCT iOS HCE service after SELECT/GET_HELLO. */
    @Synchronized fun authenticateReader(body: ByteArray): ByteArray? {
        val armed = current ?: return null
        val session = armed.session
        if (session.isExpired(SystemClock.elapsedRealtime())) return null
        val iosNonce = body.copyOfRange(0, 16)
        val proof = body.copyOfRange(16, 48)
        if (session.authorized ||
            !IosBridgeProtocol.authenticate(session, iosNonce, proof)) return null
        // Sign hello + nonce: iOS can pin the stable Android identity key.
        val signature = IosBridgeIdentity.sign(session.hello + iosNonce)
        require(signature.size in 64..80)
        session.iosNonce = iosNonce
        session.authorized = true
        armed.onStatus("NFC 配對已驗證；正在等待 iPhone 連接本地 Wi-Fi")
        return byteArrayOf(signature.size.toByte()) + signature
    }

    @Synchronized fun stop() {
        val old = current ?: return
        current = null
        try { old.server.close() } catch (_: Exception) {}
        if (old.payload is IosBridgePayload.Files) {
            old.payload.originals.forEach { it.file.delete() }
        }
    }
}
