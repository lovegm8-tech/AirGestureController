package tw.airgesture.transferbeta.subtitle

import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import java.io.File
import java.util.concurrent.Executors
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

/** User-initiated offline model installation. No model weights are bundled in APK. */
object SubtitleJapaneseModel {
    private const val ARCHIVE = "sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01.tar.bz2"
    private const val URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" + ARCHIVE
    private const val PREFS = "japanese_reazon_asr"
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var installing = false

    @JvmStatic fun modelFolder(context: Context): File = File(context.filesDir, "reazonspeech-ja")
    private fun archive(context: Context): File =
        File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), ARCHIVE)

    @JvmStatic fun installed(context: Context): Boolean {
        val dir = modelFolder(context)
        return File(dir, ".ready").isFile &&
            listOf("encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt")
                .all { File(dir, it).isFile && File(dir, it).length() > 0 }
    }

    @JvmStatic fun status(context: Context): String {
        if (installed(context)) return "日語 ReazonSpeech 日語專用模型已安裝"
        if (installing) return "日語 ReazonSpeech 模型解壓安裝中"
        if (archive(context).length() > 100_000_000L) return "下載完成，可驗證並安裝"
        val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("download_id", -1L)
        if (id > 0L) {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    if (status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PENDING)
                        return "日語 ReazonSpeech 模型下載中"
                    if (status == DownloadManager.STATUS_FAILED) return "日語 ReazonSpeech 模型下載失敗"
                }
            }
        }
        return "日語 ReazonSpeech 日語專用模型未安裝（日語 INT8）"
    }

    @JvmStatic fun download(context: Context): String {
        if (installed(context)) return "日語 ReazonSpeech 已安裝"
        if (installing) return "正在安裝中"
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = prefs.getLong("download_id", -1L)
        if (old > 0L) dm.remove(old)
        archive(context).delete()
        val request = DownloadManager.Request(android.net.Uri.parse(URL))
            .setTitle("日語 ReazonSpeech 日語專用辨識模型")
            .setDescription("官方離線模型，日語 INT8")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, ARCHIVE)
        prefs.edit().putLong("download_id", dm.enqueue(request)).apply()
        return "已開始下載 日語 ReazonSpeech 日語模型"
    }

    @JvmStatic fun install(context: Context, callback: java.util.function.Consumer<String>) {
        if (SubtitleCaptureService.running) {
            callback.accept("請先停止懸浮翻譯再安裝")
            return
        }
        if (installed(context)) { callback.accept("日語 ReazonSpeech 已安裝"); return }
        if (installing) { callback.accept("正在安裝中"); return }
        installing = true
        val app = context.applicationContext
        worker.execute {
            val pending = File(app.filesDir, "reazonspeech-ja.pending")
            try {
                val input = archive(app)
                require(input.isFile && input.length() in 100_000_000L..1_000_000_000L) {
                    "模型壓縮包不完整或大小異常"
                }
                pending.deleteRecursively()
                require(pending.mkdirs()) { "無法建立模型目錄" }
                // Accept other encoder epoch names within this pinned official archive.
                val found = mutableSetOf<String>()
                BZip2CompressorInputStream(input.inputStream().buffered()).use { bz ->
                    TarArchiveInputStream(bz).use { tar ->
                        var entry = tar.nextTarEntry
                        while (entry != null) {
                            if (entry.isFile) {
                                val name = entry.name.substringAfterLast('/')
                                val targetName = when {
                                    name == "tokens.txt" -> "tokens.txt"
                                    name.startsWith("encoder-") && name.endsWith(".int8.onnx") -> "encoder.onnx"
                                    name.startsWith("decoder-") && name.endsWith(".int8.onnx") -> "decoder.onnx"
                                    name.startsWith("joiner-") && name.endsWith(".int8.onnx") -> "joiner.onnx"
                                    else -> null
                                }
                                if (targetName != null && found.add(targetName)) {
                                    File(pending, targetName).outputStream().buffered().use { out ->
                                        val buffer = ByteArray(65536)
                                        var total = 0L
                                        while (true) {
                                            val n = tar.read(buffer)
                                            if (n < 0) break
                                            total += n
                                            require(total < 300_000_000L) { "模型檔超過安全上限" }
                                            out.write(buffer, 0, n)
                                        }
                                    }
                                }
                            }
                            entry = tar.nextTarEntry
                        }
                    }
                }
                require(found.size == 4) { "模型壓縮包缺少 encoder／decoder／joiner／tokens" }
                val folder = modelFolder(app)
                folder.deleteRecursively()
                require(pending.renameTo(folder)) { "無法完成模型安裝" }
                File(folder, ".ready").writeText("official release: $ARCHIVE\n")
                input.delete()
                callback.accept("日語 ReazonSpeech 日語模型安裝完成")
            } catch (error: Exception) {
                pending.deleteRecursively()
                callback.accept("日語 ReazonSpeech 安裝失敗：" + (error.message ?: "未知錯誤"))
            } finally {
                installing = false
            }
        }
    }
}
