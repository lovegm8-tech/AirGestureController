package tw.airgesture.transferbeta

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.widget.Toast
import tw.airgesture.transferbeta.core.ShareEntryPolicy
import tw.airgesture.transferbeta.core.SharedTextPolicy
import tw.airgesture.transferbeta.core.TransferPolicy
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * Minimal, translucent system Sharesheet target.
 *
 * Importing a photo/document must never open the AirGrab dashboard. The source
 * app stays visible behind this transparent Activity while its temporary
 * content:// permission remains active, until a private staging copy is made.
 * Its background service performs the actual Nearby transfer later.
 */
class ShareReceiverActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var handling = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        receive(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receive(intent)
    }

    private fun notifyAndFinish(message: String) {
        main.post {
            if (!isFinishing) {
                Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun receive(incoming: Intent?) {
        if (handling) {
            notifyAndFinish("已有一個檔案正在準備中，請稍後再試")
            return
        }
        if (incoming?.action != Intent.ACTION_SEND) {
            finish()
            return
        }
        val transfer = GlobalGestureService.transfer
        if (!GlobalGestureService.isActive || transfer == null || !transfer.isConnected) {
            notifyAndFinish("AirGrab 尚未連線，請先開啟隔空抓取再分享")
            return
        }
        @Suppress("DEPRECATION")
        val stream = if (Build.VERSION.SDK_INT >= 33) {
            incoming.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else incoming.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri

        // Deliberately support exactly one original file or one unambiguous link.
        val candidates = listOfNotNull(stream, incoming.clipData?.getItemAt(0)?.uri)
        val uri = candidates.firstOrNull { it.scheme == "content" }
        val uriCount = incoming.clipData?.itemCount ?: 0
        if (uri != null) {
            if (uriCount > 1 || !ShareEntryPolicy.allowedFileScheme(uri.scheme)) {
                notifyAndFinish("目前一次只能隔空抓取一個原始檔案")
                return
            }
            handling = true
            io.execute {
                var staged: File? = null
                try {
                    val name = readName(uri)
                    val safe = TransferPolicy.safeName(name)
                        ?: error("無效的檔案名稱")
                    val cache = File(cacheDir, "sharesheet-staging").apply { mkdirs() }
                    // Best-effort cleanup of older completed sends; never touch recent files
                    // that might still be transferring in the background.
                    cache.listFiles()?.filter {
                        it.isFile && System.currentTimeMillis() - it.lastModified() > 86_400_000L
                    }?.forEach { it.delete() }
                    staged = File.createTempFile("share-", ".tmp", cache)
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(staged).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var bytes = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                bytes += count.toLong()
                                if (bytes > TransferPolicy.MAX_BYTES)
                                    error("檔案超過 256 MB 的傳送限制")
                                output.write(buffer, 0, count)
                            }
                            check(bytes > 0L) { "原始檔案是空的" }
                        }
                    } ?: error("沒有原始檔案讀取權限")
                    val candidate = staged
                    main.post {
                        val ready = GlobalGestureService.transfer
                        if (GlobalGestureService.isActive && ready?.isConnected == true) {
                            ready.offerFile(candidate, safe)
                            // Ownership transferred to background transport; do not delete here.
                            staged = null
                            finish()
                        } else {
                            candidate.delete()
                            notifyAndFinish("檔案已讀取，但對方已斷線，請重新連線")
                        }
                    }
                } catch (e: Exception) {
                    staged?.delete()
                    notifyAndFinish("原始檔案無法傳送：" + (e.message ?: "讀取失敗"))
                }
            }
        } else {
            val text = incoming.getStringExtra(Intent.EXTRA_TEXT)
                ?: incoming.clipData?.getItemAt(0)?.text?.toString()
            val url = SharedTextPolicy.onlyWebLink(text)
            if (url != null) {
                transfer.offerLink(url)
                finish()
            } else notifyAndFinish("未取得可傳送的原始檔案或唯一網址")
        }
    }

    private fun readName(uri: Uri): String {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                } ?: "document.bin"
        } catch (_: Exception) { "document.bin" }
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }
}
