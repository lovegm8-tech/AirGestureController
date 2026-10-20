package tw.airgesture.transferbeta.crossplatform

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import tw.airgesture.transferbeta.core.GrabContentPolicy
import tw.airgesture.transferbeta.core.MediaFilePolicy
import tw.airgesture.transferbeta.core.MediaKind
import tw.airgesture.transferbeta.core.TransferPolicy
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.Socket
import java.security.MessageDigest

/** The capture layer supplies only verified, staged originals or a validated URL. */
sealed class IosBridgePayload {
    data object Receive : IosBridgePayload()
    data class Url(val url: String) : IosBridgePayload()
    data class Files(val originals: List<Original>) : IosBridgePayload()
    data class Original(val file: File, val name: String, val sha256: String) {
        val size: Long get() = file.length()
    }
}

/**
 * Encrypted length-framed TCP exchange over existing local Wi-Fi.
 * No Nearby classes or Android gesture dependency. Receiver publishes files
 * only after each hash verifies; a failed batch rolls back all received URIs.
 */
class IosWifiTransfer(private val context: Context) {
    private class Framed(socket: Socket, session: IosBridgeProtocol.Session) {
        private val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        private val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
        private val cipher = IosBridgeProtocol.FrameCipher(session, androidSends = true)
        fun send(tag: Int, body: ByteArray) {
            require(tag in 1..2 && body.size <= IosBridgeProtocol.CHUNK_BYTES)
            val encrypted = cipher.seal(byteArrayOf(tag.toByte()) + body)
            output.writeInt(encrypted.size)
            output.write(encrypted)
            output.flush()
        }
        fun receive(): Pair<Int, ByteArray> {
            val length = input.readInt()
            require(length in 17..IosBridgeProtocol.MAX_FRAME_BYTES) { "錯誤 Wi-Fi 封包長度" }
            val encrypted = ByteArray(length)
            input.readFully(encrypted)
            val decoded = cipher.open(encrypted)
            require(decoded.isNotEmpty())
            val type = decoded[0].toInt() and 0xff
            require(type in 1..2)
            return type to decoded.copyOfRange(1, decoded.size)
        }
        fun sendControl(json: JSONObject) {
            val bytes = json.toString().toByteArray(Charsets.UTF_8)
            require(bytes.size <= IosBridgeProtocol.MAX_CONTROL_BYTES)
            send(1, bytes)
        }
        fun readControl(expected: String? = null): JSONObject {
            val (tag, body) = receive()
            require(tag == 1 && body.size <= IosBridgeProtocol.MAX_CONTROL_BYTES)
            val json = JSONObject(String(body, Charsets.UTF_8))
            if (json.optString("type") == "error")
                error("iPhone 拒絕傳送：" + json.optString("reason"))
            if (expected != null) require(json.optString("type") == expected) {
                "通訊階段不一致，預期 " + expected
            }
            return json
        }
        fun readBinary(): ByteArray {
            val (tag, body) = receive()
            require(tag == 2 && body.isNotEmpty() &&
                body.size <= IosBridgeProtocol.CHUNK_BYTES)
            return body
        }
    }
    private fun sha256(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }
    private fun hex(digest: MessageDigest): String = sha256(digest.digest())
    private fun validHash(sha: String): Boolean = sha.matches(Regex("[0-9a-f]{64}"))
    private fun fileList(items: List<IosBridgePayload.Original>): JSONArray =
        JSONArray().also { array ->
            items.forEach {
                array.put(JSONObject().put("name", it.name).put("size", it.size)
                    .put("sha256", it.sha256))
            }
        }
    private fun assertEntries(items: List<IosBridgePayload.Original>) {
        require(items.size in 1..IosBridgeProtocol.MAX_FILES)
        items.forEach {
            require(TransferPolicy.safeName(it.name) == it.name && it.file.isFile &&
                it.size in 1..TransferPolicy.MAX_BYTES && validHash(it.sha256))
        }
    }

    fun run(socket: Socket, session: IosBridgeProtocol.Session,
            payload: IosBridgePayload, update: (String) -> Unit): String {
        socket.soTimeout = IosBridgeProtocol.TCP_TIMEOUT_MS
        val auth = DataInputStream(socket.getInputStream())
        val magic = ByteArray(4).also(auth::readFully)
        require(magic.contentEquals("AGW1".toByteArray(Charsets.US_ASCII))) {
            "Wi-Fi 連線協定錯誤"
        }
        val sid = ByteArray(16).also(auth::readFully)
        val iosNonce = ByteArray(16).also(auth::readFully)
        val proof = ByteArray(32).also(auth::readFully)
        require(MessageDigest.isEqual(sid, session.sid) &&
            IosBridgeProtocol.authenticateTcp(session, iosNonce, proof)) {
            "Wi-Fi 配對驗證失敗"
        }
        val stream = Framed(socket, session)
        val role = if (payload is IosBridgePayload.Receive) "android_receiver"
            else "android_sender"
        stream.sendControl(JSONObject().put("type", "hello").put("major", 1)
            .put("minor", 0).put("sid", sha256(session.sid))
            .put("role", role).put("max_files", IosBridgeProtocol.MAX_FILES))
        val ready = stream.readControl("ready")
        require(ready.optInt("major") == 1) { "iOS 傳輸協定版本不相容" }
        when (payload) {
            is IosBridgePayload.Files -> sendFiles(stream, payload.originals, update)
            is IosBridgePayload.Url -> sendUrl(stream, payload.url)
            IosBridgePayload.Receive -> receiveContent(stream, update)
        }
        return "iPhone Wi-Fi 傳輸完成，已通過安全性與資料完整性驗證"
    }
    private fun sendUrl(stream: Framed, raw: String) {
        val url = GrabContentPolicy.safeWebUrl(raw) ?: error("無效網址")
        stream.sendControl(JSONObject().put("type", "offer")
            .put("mode", "url").put("url", url))
        stream.readControl("accept")
        stream.sendControl(JSONObject().put("type", "complete"))
        stream.readControl("complete_ok")
    }
    private fun sendFiles(stream: Framed, items: List<IosBridgePayload.Original>,
                          update: (String) -> Unit) {
        assertEntries(items)
        val offer = JSONObject().put("type", "offer")
            .put("mode", "files").put("files", fileList(items))
        require(offer.toString().toByteArray().size <= IosBridgeProtocol.MAX_CONTROL_BYTES)
        stream.sendControl(offer)
        stream.readControl("accept")
        val buffer = ByteArray(IosBridgeProtocol.CHUNK_BYTES)
        for ((index, file) in items.withIndex()) {
            update("iPhone：正在傳送原檔 " + (index + 1) + "/" + items.size)
            stream.sendControl(JSONObject().put("type", "file_start").put("index", index))
            var total = 0L
            val digest = MessageDigest.getInstance("SHA-256")
            file.file.inputStream().buffered().use { input ->
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n == 0) continue
                    total += n
                    require(total <= file.size)
                    digest.update(buffer, 0, n)
                    stream.send(2, buffer.copyOf(n))
                }
            }
            require(total == file.size && hex(digest) == file.sha256) {
                "本機原始檔於準備後發生變更"
            }
            stream.sendControl(JSONObject().put("type", "file_end").put("index", index))
            val ack = stream.readControl("file_ok")
            require(ack.optInt("index", -1) == index)
        }
        stream.sendControl(JSONObject().put("type", "complete"))
        stream.readControl("complete_ok")
    }
    private fun receiveContent(stream: Framed, update: (String) -> Unit) {
        val offer = stream.readControl("offer")
        when (offer.optString("mode")) {
            "url" -> {
                val url = GrabContentPolicy.safeWebUrl(offer.optString("url"))
                    ?: error("iPhone 傳來不安全的網址")
                stream.sendControl(JSONObject().put("type", "accept"))
                stream.readControl("complete")
                stream.sendControl(JSONObject().put("type", "complete_ok"))
                update("已接收 iPhone 網址：" + url)
            }
            "files" -> receiveFiles(stream, offer, update)
            else -> error("不支援的 iPhone 分享內容")
        }
    }
    private data class Expected(val name: String, val size: Long, val hash: String)
    private fun receiveFiles(stream: Framed, offer: JSONObject,
                             update: (String) -> Unit) {
        val input = offer.optJSONArray("files") ?: error("缺少傳輸清單")
        require(input.length() in 1..IosBridgeProtocol.MAX_FILES)
        val expected = (0 until input.length()).map { i ->
            val item = input.getJSONObject(i)
            val name = item.getString("name")
            val size = item.getLong("size")
            val hash = item.getString("sha256")
            require(TransferPolicy.safeName(name) == name &&
                size in 1..TransferPolicy.MAX_BYTES && validHash(hash))
            Expected(name, size, hash)
        }
        // MediaStore keeps a temporary second copy before publish; budget headroom.
        val total = expected.sumOf { it.size }
        require(total >= 0 && total <= Environment.getExternalStorageDirectory().usableSpace -
            TransferPolicy.MIN_FREE_HEADROOM) { "Android 剩餘空間不足" }
        val created = mutableListOf<Uri>()
        try {
            stream.sendControl(JSONObject().put("type", "accept"))
            expected.forEachIndexed { index, item ->
                val start = stream.readControl("file_start")
                require(start.optInt("index", -1) == index)
                val spec = MediaFilePolicy.destination(item.name)
                val collection = when (spec.kind) {
                    MediaKind.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    MediaKind.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    MediaKind.DOWNLOAD -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
                }
                val row = context.contentResolver.insert(collection, ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, item.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, spec.mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, spec.folder + "/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }) ?: error("無法建立接收檔案")
                created.add(row)
                update("iPhone：接收原檔 " + (index + 1) + "/" + expected.size)
                var size = 0L
                val hash = MessageDigest.getInstance("SHA-256")
                context.contentResolver.openOutputStream(row, "w")?.use { output ->
                    while (size < item.size) {
                        val bytes = stream.readBinary()
                        require(bytes.size <= item.size - size) { "收到超出宣告大小的內容" }
                        output.write(bytes)
                        hash.update(bytes)
                        size += bytes.size
                    }
                } ?: error("無法儲存 iPhone 原始檔")
                val end = stream.readControl("file_end")
                require(end.optInt("index", -1) == index &&
                    size == item.size && hex(hash) == item.hash) {
                    "原始檔 SHA-256 或大小不符"
                }
                stream.sendControl(JSONObject().put("type", "file_ok").put("index", index))
            }
            stream.readControl("complete")
            for (row in created) {
                require(context.contentResolver.update(row, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }, null, null) > 0)
            }
            stream.sendControl(JSONObject().put("type", "complete_ok"))
            update("已驗證並儲存 " + expected.size + " 個原始檔案")
        } catch (e: Exception) {
            created.forEach { try { context.contentResolver.delete(it, null, null) }
                catch (_: Exception) {} }
            throw e
        }
    }
}
