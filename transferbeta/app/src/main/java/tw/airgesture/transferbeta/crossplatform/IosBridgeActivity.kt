package tw.airgesture.transferbeta.crossplatform

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.view.View
import tw.airgesture.transferbeta.core.GrabContentPolicy
import tw.airgesture.transferbeta.core.ShareEntryPolicy
import tw.airgesture.transferbeta.core.SharedTextPolicy
import tw.airgesture.transferbeta.core.TransferPolicy
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Separate opt-in iPhone share/receive UI. Existing hand gesture capture,
 * Android NFC share, and Nearby manager are not referenced or modified.
 */
class IosBridgeActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var openLinkButton: Button
    private var receivedUrl: String? = null
    private var armed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 48, 36, 32)
        }
        container.addView(TextView(this).apply {
            text = "懸浮操作 · iPhone 14 跨平台 NFC（開發版）"
            textSize = 21f
        })
        container.addView(TextView(this).apply {
            text = "雙方先接上同一個 Wi-Fi／熱點。Android 在此頁待命，" +
                "由 iPhone App 在前景開啟 Core NFC ISO7816 讀卡，碰觸 Android 背面。" +
                "\nNFC 僅配對，原檔透過獨立加密 Wi-Fi 傳送，不使用 Nearby。" +
                "\n此功能不會自行讀取目前瀏覽器、相簿或 YouTube 畫面。"
            textSize = 15f
        })
        status = TextView(this).apply {
            text = "正在準備跨平台連線…"
            textSize = 17f
            setPadding(0, 24, 0, 24)
        }
        container.addView(status)
        openLinkButton = Button(this).apply {
            text = "開啟 iPhone 傳來的網址"
            visibility = View.GONE
            setOnClickListener {
                val url = GrabContentPolicy.safeWebUrl(receivedUrl) ?: return@setOnClickListener
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (_: Exception) {
                    show("網址已收到，但手機找不到可開啟此網址的應用程式：" + url)
                }
            }
        }
        container.addView(openLinkButton)
        container.addView(Button(this).apply {
            text = "取消 iPhone NFC 待命"
            setOnClickListener { finish() }
        })
        setContentView(container)
        when (intent.action) {
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> prepareShare(intent)
            else -> arm(IosBridgePayload.Receive)
        }
    }
    private fun show(message: String) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            status.text = message
            if (message.startsWith("已接收 iPhone 網址：")) {
                val url = GrabContentPolicy.safeWebUrl(
                    message.removePrefix("已接收 iPhone 網址："))
                if (url != null) {
                    receivedUrl = url
                    openLinkButton.visibility = View.VISIBLE
                }
            }
        }
    }
    private fun arm(payload: IosBridgePayload) {
        if (isFinishing || isDestroyed) {
            if (payload is IosBridgePayload.Files)
                payload.originals.forEach { it.file.delete() }
            return
        }
        try {
            val address = IosBridgeHost.arm(applicationContext, payload, ::show)
            armed = true
            show("Android 已待命（" + address +
                "）。在 iPhone App 開啟 NFC 讀卡並碰觸 Android。")
        } catch (e: Exception) {
            if (payload is IosBridgePayload.Files)
                payload.originals.forEach { it.file.delete() }
            show("跨平台啟動失敗：" + (e.message ?: "無法開啟 Wi-Fi 或 NFC"))
        }
    }
    private fun readName(uri: Uri): String {
        return contentResolver.query(uri,
            arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "original-file"
    }
    @Suppress("DEPRECATION")
    private fun prepareShare(incoming: Intent) {
        val text = incoming.getStringExtra(Intent.EXTRA_TEXT)
            ?: incoming.clipData?.getItemAt(0)?.text?.toString()
        if (incoming.action == Intent.ACTION_SEND && incoming.type?.startsWith("text/") == true) {
            SharedTextPolicy.onlyWebLink(text)?.let { url ->
                arm(IosBridgePayload.Url(url)); return
            }
        }
        val explicitOne: Uri? = if (Build.VERSION.SDK_INT >= 33)
            incoming.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else incoming.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        val explicitMany: ArrayList<Uri>? = if (Build.VERSION.SDK_INT >= 33)
            incoming.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else incoming.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
        val clips = (0 until (incoming.clipData?.itemCount ?: 0))
            .map { incoming.clipData?.getItemAt(it)?.uri?.toString() }
        val urls = when (incoming.action) {
            Intent.ACTION_SEND -> ShareEntryPolicy.chooseSingleOriginal(
                incoming.action, explicitOne?.toString(), clips)?.let { listOf(Uri.parse(it)) }
            Intent.ACTION_SEND_MULTIPLE -> ShareEntryPolicy.chooseMultipleOriginals(
                incoming.action, explicitMany?.map { it.toString() }, clips
            )?.map(Uri::parse)
            else -> null
        }
        if (urls == null || urls.size !in 1..IosBridgeProtocol.MAX_FILES) {
            show("分享內容必須是單一有效網址，或 1～32 個可讀取的原始檔案")
            return
        }
        show("準備 " + urls.size + " 個原始檔案…")
        io.execute {
            val staged = mutableListOf<IosBridgePayload.Original>()
            try {
                val cache = File(cacheDir, "iosbridge-staging").apply { mkdirs() }
                cache.listFiles()?.filter {
                    it.isFile && System.currentTimeMillis() - it.lastModified() > 86_400_000L
                }?.forEach { it.delete() }
                urls.forEachIndexed { index, uri ->
                    val name = TransferPolicy.safeName(readName(uri))
                        ?: error("原檔名稱無效")
                    val file = File.createTempFile("ios-part-", ".tmp", cache)
                    // Stage immediately; temporary Android ShareSheet URI grants
                    // must not be held until the iPhone later reads NFC.
                    staged.add(IosBridgePayload.Original(file, name, ""))
                    var size = 0L
                    val sha = MessageDigest.getInstance("SHA-256")
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(file).buffered().use { output ->
                            val buffer = ByteArray(128 * 1024)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (count == 0) continue
                                size += count
                                check(size in 1..TransferPolicy.MAX_BYTES)
                                sha.update(buffer, 0, count)
                                output.write(buffer, 0, count)
                            }
                        }
                    } ?: error("原始檔沒有分享讀取權限")
                    require(size > 0)
                    val hash = sha.digest().joinToString("") { "%02x".format(it) }
                    staged[index] = IosBridgePayload.Original(file, name, hash)
                    show("準備原始檔 " + (index + 1) + "/" + urls.size)
                }
                runOnUiThread { arm(IosBridgePayload.Files(staged.toList())) }
            } catch (e: Exception) {
                staged.forEach { it.file.delete() }
                show("跨平台原始檔準備失敗：" + (e.message ?: "無法讀取原檔"))
            }
        }
    }
    override fun onDestroy() {
        if (armed) IosBridgeHost.stop()
        io.shutdown()
        super.onDestroy()
    }
}
