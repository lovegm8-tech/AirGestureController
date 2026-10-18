package tw.airgesture.controller.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Environment
import org.json.JSONObject
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Explicitly started, opt-in local receiver. No traffic leaves the local network.
 * This is a preview implementation, not yet connected to the existing camera gestures.
 */
class TransferService : Service() {
    private val active = AtomicBoolean(false)
    private val workers = Executors.newFixedThreadPool(3)
    private var listener: ServerSocket? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { stopSelf(); return START_NOT_STICKY }
        if (active.compareAndSet(false, true)) {
            val channel = "airgrab_local"
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(NotificationChannel(channel, "隔空抓取區域網路傳輸",
                    NotificationManager.IMPORTANCE_LOW))
            val notification = Notification.Builder(this, channel)
                .setContentTitle("隔空抓取：區域網路接收已開啟")
                .setContentText("僅已配對裝置可傳輸；可回 App 關閉")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .build()
            startForeground(4701, notification)
            thread(name = "airgrab-listener", isDaemon = true) {
                try {
                    ServerSocket(PORT).use { server ->
                        listener = server
                        while (active.get()) {
                            val socket = server.accept()
                            workers.execute { socket.use(::handle) }
                        }
                    }
                } catch (_: Exception) {
                    // Socket closes when the user disables the service.
                } finally { listener = null; active.set(false); stopSelf() }
            }
        }
        return START_NOT_STICKY
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 60_000
        try {
            val channel = SecureChannel(socket.getInputStream(), socket.getOutputStream(),
                TransferStore.key(this))
            val request = channel.receiveJson()
            when (request.optString("op")) {
                "list" -> channel.sendJson(JSONObject().put("offers", TransferStore.list()))
                "get" -> {
                    val offer = TransferStore.get(request.optString("id"))
                    if (offer == null) {
                        channel.sendJson(JSONObject().put("error", "expired_or_missing"))
                        return
                    }
                    val hash = TransferStore.digest(offer.file)
                    channel.sendJson(JSONObject().put("status", "file")
                        .put("name", offer.name).put("mime", offer.mime)
                        .put("size", offer.file.length()).put("sha256", hash))
                    offer.file.inputStream().buffered().use { source ->
                        val buffer = ByteArray(SecureChannel.CHUNK_SIZE)
                        while (true) {
                            val n = source.read(buffer)
                            if (n < 0) break
                            channel.sendBytes(buffer, n)
                        }
                    }
                    channel.sendJson(JSONObject().put("status", "end"))
                }
                "put" -> receiveFile(channel, request)
                else -> channel.sendJson(JSONObject().put("error", "unknown_command"))
            }
        } catch (_: Exception) {
            // Peer disconnected or failed authentication. Do not report success.
        }
    }

    private fun receiveFile(channel: SecureChannel, request: JSONObject) {
        val size = request.optLong("size", -1)
        val expected = request.optString("sha256").lowercase()
        if (size !in 0..MAX_UPLOAD || !expected.matches(Regex("[0-9a-f]{64}"))) {
            channel.sendJson(JSONObject().put("error", "bad_metadata"))
            return
        }
        val name = TransferStore.safeName(request.optString("name", "file"))
        val dir = (getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?:
            File(filesDir, "received")).apply { mkdirs() }
        val temp = File.createTempFile("incoming-", ".part", dir)
        channel.sendJson(JSONObject().put("status", "ready"))
        var remaining = size
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            temp.outputStream().buffered().use { dest ->
                while (remaining > 0) {
                    val frame = channel.receive()
                    require(frame.type == 2 && frame.payload.isNotEmpty()
                        && frame.payload.size <= remaining) { "Bad data frame" }
                    dest.write(frame.payload)
                    digest.update(frame.payload)
                    remaining -= frame.payload.size
                }
            }
            val end = channel.receiveJson()
            require(end.optString("status") == "end") { "Missing end frame" }
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            require(expected == actual) { "SHA-256 mismatch" }
            val output = File(dir, System.currentTimeMillis().toString() + "-" + name)
            check(temp.renameTo(output)) { "Cannot commit verified file" }
            channel.sendJson(JSONObject().put("status", "ok").put("saved", output.name))
        } catch (_: Exception) {
            temp.delete()
            try { channel.sendJson(JSONObject().put("error", "transfer_failed")) } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        active.set(false)
        try { listener?.close() } catch (_: Exception) {}
        workers.shutdownNow()
        super.onDestroy()
    }

    companion object {
        const val PORT = 48773
        const val STOP = "tw.airgesture.controller.transfer.STOP"
        private const val MAX_UPLOAD = 10L * 1024 * 1024 * 1024
    }
}
