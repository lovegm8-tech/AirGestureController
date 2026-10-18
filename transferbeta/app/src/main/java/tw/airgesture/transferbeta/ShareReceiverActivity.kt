package tw.airgesture.transferbeta

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.widget.Toast
import tw.airgesture.transferbeta.core.AirGrabBundle
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
            GlobalGestureService.transfer?.reportCaptureFailure(message)
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
        if (incoming?.action != Intent.ACTION_SEND &&
            incoming?.action != Intent.ACTION_SEND_MULTIPLE) {
            finish()
            return
        }
        val transfer = GlobalGestureService.transfer
        if (!GlobalGestureService.isActive || transfer == null || !transfer.isConnected) {
            notifyAndFinish("AirGrab 尚未連線，請先開啟隔空抓取再分享")
            return
        }
        if (incoming.action == Intent.ACTION_SEND_MULTIPLE) {
            receiveMultiple(incoming)
            return
        }
        // Browser Sharesheets can attach incidental ClipData previews alongside
        // the genuine URL. Explicit text/plain with one safe HTTPS link is a
        // web link, not a photo/document even when ClipData carries a URI.
        val sharedText = incoming.getStringExtra(Intent.EXTRA_TEXT)
            ?: incoming.clipData?.getItemAt(0)?.text?.toString()
        val safeLink = SharedTextPolicy.onlyWebLink(sharedText)
        if (incoming.type?.startsWith("text/") == true && safeLink != null) {
            GlobalGrabBridge.accessibility?.get()?.externalShareDelivered()
            transfer.offerLink(safeLink)
            finish()
            return
        }
        @Suppress("DEPRECATION")
        val stream = if (Build.VERSION.SDK_INT >= 33) {
            incoming.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else incoming.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri

        // ACTION_SEND's EXTRA_STREAM is authoritative: Samsung Gallery may add
        // auxiliary ClipData entries (including previews) for one selected photo.
        val clipUris = (0 until (incoming.clipData?.itemCount ?: 0))
            .map { incoming.clipData?.getItemAt(it)?.uri }
        val chosen = ShareEntryPolicy.chooseSingleOriginal(
            incoming.action, stream?.toString(), clipUris.map { it?.toString() })
        val uri = chosen?.let(Uri::parse)
        if (uri != null) {
            GlobalGrabBridge.accessibility?.get()?.externalShareDelivered()
            transfer.reportShareStarted("三星相簿已交付照片 · 正在讀取原始檔…")
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
                    val temp = File.createTempFile("share-", ".tmp", cache)
                    staged = temp
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(temp).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var bytes = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                bytes += count.toLong()
                                if (bytes > TransferPolicy.MAX_BYTES)
                                    error("檔案超過 " + TransferPolicy.capacityLabel() + " 的傳送限制")
                                output.write(buffer, 0, count)
                            }
                            check(bytes > 0L) { "原始檔案是空的" }
                        }
                    } ?: error("沒有原始檔案讀取權限")
                    val candidate = temp
                    main.post {
                        val ready = GlobalGestureService.transfer
                        if (GlobalGestureService.isActive && ready?.isConnected == true) {
                            ready.reportShareStarted("已取得三星相簿原檔 · 正在建立接收請求")
                            ready.offerFile(candidate, safe, deleteWhenDone = true)
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
            if (safeLink != null) {
                GlobalGrabBridge.accessibility?.get()?.externalShareDelivered()
                transfer.offerLink(safeLink)
                finish()
            } else notifyAndFinish("分享來源沒有提供唯一有效網址或原始檔；請檢查分享入口")
        }
    }

    /** Multi-share uses staged originals and a per-file queue, not a ZIP.
     * The receiver approves one session and verifies each file independently. */
    @Suppress("DEPRECATION")
    private fun receiveMultiple(incoming: Intent) {
        val explicit = if (Build.VERSION.SDK_INT >= 33)
            incoming.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else incoming.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
        val clip = (0 until (incoming.clipData?.itemCount ?: 0))
            .map { incoming.clipData?.getItemAt(it)?.uri?.toString() }
        val originals = ShareEntryPolicy.chooseMultipleOriginals(
            incoming.action, explicit?.map { it.toString() }, clip
        )?.map(Uri::parse)
        if (originals == null) {
            notifyAndFinish("請一次選取 2～${AirGrabBundle.MAX_FILES} 個原始檔案；不可包含重複或無權限的項目")
            return
        }
        val transfer = GlobalGestureService.transfer
        if (!GlobalGestureService.isActive || transfer?.isConnected != true) {
            notifyAndFinish("尚未連線接收設備")
            return
        }
        GlobalGrabBridge.accessibility?.get()?.externalShareDelivered()
        handling = true
        transfer.reportShareStarted("正在逐檔準備 ${originals.size} 個原始檔案…")
        io.execute {
            val staged = mutableListOf<MultiPeerTransferManager.StagedFile>()
            try {
                val cache = File(cacheDir, "sharesheet-staging").apply { mkdirs() }
                for ((index, uri) in originals.withIndex()) {
                    val name = TransferPolicy.safeName(readName(uri))
                        ?: error("第 ${index + 1} 個檔案名稱無效")
                    // Stage EACH original independently, without a combined
                    // ZIP. Android's temporary Sharesheet URI access can expire
                    // after the transparent share Activity closes.
                    val temp = File.createTempFile("airgrab-part-", ".tmp", cache)
                    staged.add(MultiPeerTransferManager.StagedFile(temp, name))
                    var copied = 0L
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(temp).buffered().use { output ->
                            val buffer = ByteArray(128 * 1024)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                if (n == 0) continue
                                copied += n
                                check(copied <= TransferPolicy.MAX_BYTES) {
                                    "單檔超過裝置可處理的大小"
                                }
                                output.write(buffer, 0, n)
                            }
                        }
                    } ?: error("第 ${index + 1} 個原始檔案沒有讀取權限")
                    require(copied > 0) { "第 ${index + 1} 個原始檔案是空的" }
                    main.post {
                        GlobalGestureService.transfer?.reportShareStarted(
                            "準備原始檔 ${index + 1}/${originals.size} · 逐檔佇列")
                    }
                }
                main.post {
                    val ready = GlobalGestureService.transfer
                    if (GlobalGestureService.isActive && ready?.isConnected == true) {
                        ready.offerFiles(staged.toList())
                        finish() // Background queue owns all staged originals.
                    } else {
                        staged.forEach { it.file.delete() }
                        notifyAndFinish("準備完成，但接收裝置已斷線")
                    }
                }
            } catch (e: Exception) {
                staged.forEach { it.file.delete() }
                notifyAndFinish("多檔抓取失敗：" + (e.message ?: "原始檔案讀取失敗"))
            }
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
