package tw.airgesture.transferbeta.subtitle

import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import android.os.SystemClock
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

/**
 * Opt-in model installation. The official archive is SHA-256 verified, and only
 * expected model/token files are extracted into the app-private directory.
 * No model weights in the APK; inference and transcripts remain fully offline.
 */
object SubtitleSenseVoiceModel {
    private const val PREFS = "sense_voice_asr"
    private const val DOWNLOAD_KEY = "download_id"
    private const val MODEL_DIR = "sensevoice-int8-2024-07-17"
    private const val ARCHIVE = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2"
    private const val SHA256 = "7d1efa2138a65b0b488df37f8b89e3d91a60676e416f515b952358d83dfd347e"
    private const val URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" + ARCHIVE
    private val installer = Executors.newSingleThreadExecutor()
    @Volatile private var installing = false
    @Volatile private var message = ""

    @JvmStatic fun modelFolder(context: Context): File = File(context.filesDir, MODEL_DIR)
    private fun archiveFile(context: Context): File =
        File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), ARCHIVE)

    @JvmStatic fun installed(context: Context): Boolean {
        val folder = modelFolder(context)
        return File(folder, ".ready").isFile &&
            File(folder, "model.int8.onnx").length() > 150_000_000L &&
            File(folder, "tokens.txt").length() > 50_000L
    }

    @JvmStatic fun status(context: Context): String {
        if (installed(context)) return "SenseVoice INT8 已安裝（英文／日文／韓文）"
        if (installing) return "SenseVoice 正在驗證並解壓（請勿同時啟動字幕）"
        val archive = archiveFile(context)
        if (archive.exists() && archive.length() > 160_000_000L)
            return "已下載壓縮包，按「驗證並安裝」即可啟用"
        val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(DOWNLOAD_KEY, -1L)
        if (id > 0L) {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val st = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    if (st == DownloadManager.STATUS_RUNNING || st == DownloadManager.STATUS_PENDING ||
                            st == DownloadManager.STATUS_PAUSED) {
                        val received = cursor.getLong(cursor.getColumnIndexOrThrow(
                            DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        val total = cursor.getLong(cursor.getColumnIndexOrThrow(
                            DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        return "下載中：${received / 1_000_000} / ${if (total > 0) total / 1_000_000 else "?"} MB"
                    }
                    if (st == DownloadManager.STATUS_FAILED) return "下載失敗，請重新下載"
                }
            }
        }
        return if (message.isNotEmpty()) message else "尚未下載 SenseVoice 模型（約 163 MB 壓縮包）"
    }

    @JvmStatic fun download(context: Context): String {
        if (installed(context)) return "SenseVoice 已安裝，無需重複下載"
        if (installing) return "正在安裝中"
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = prefs.getLong(DOWNLOAD_KEY, -1L)
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        if (id > 0L) dm.remove(id)
        val archive = archiveFile(context)
        if (archive.exists()) archive.delete()
        val req = DownloadManager.Request(android.net.Uri.parse(URL))
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setTitle("SenseVoice INT8 離線語音辨識")
            .setDescription("來自 k2-fsa 官方模型，下載約 163 MB")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, ARCHIVE)
        val newId = dm.enqueue(req)
        prefs.edit().putLong(DOWNLOAD_KEY, newId).apply()
        message = ""
        return "已開始下載約 163 MB 模型壓縮包，完成後請按「驗證並安裝」"
    }

    @JvmStatic fun install(context: Context, callback: java.util.function.Consumer<String>) {
        if (installed(context)) { callback.accept("SenseVoice 已安裝"); return }
        if (SubtitleCaptureService.running) {
            callback.accept("請先停止懸浮翻譯，再安裝 ASR 模型")
            return
        }
        if (installing) { callback.accept("正在安裝中"); return }
        installing = true
        val app = context.applicationContext
        installer.execute {
            val output = File(app.filesDir, "$MODEL_DIR.pending")
            try {
                val source = archiveFile(app)
                require(source.isFile && source.length() > 160_000_000L) { "模型尚未下載完成" }
                val md = MessageDigest.getInstance("SHA-256")
                source.inputStream().buffered().use { input ->
                    val block = ByteArray(128 * 1024)
                    while (true) {
                        val n = input.read(block)
                        if (n < 0) break
                        md.update(block, 0, n)
                    }
                }
                val actual = md.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                require(actual.equals(SHA256, ignoreCase = true)) { "SHA-256 不一致，檔案不完整或來源錯誤" }
                output.deleteRecursively()
                require(output.mkdirs()) { "無法建立私有模型資料夾" }
                val names = mutableSetOf<String>()
                BZip2CompressorInputStream(source.inputStream().buffered()).use { bz ->
                    TarArchiveInputStream(bz).use { tar ->
                        var entry = tar.nextTarEntry
                        while (entry != null) {
                            if (entry.isFile) {
                                val name = entry.name.substringAfterLast('/')
                                if (name == "model.int8.onnx" || name == "tokens.txt") {
                                    require(names.add(name)) { "壓縮包含重複模型檔" }
                                    val target = File(output, name)
                                    target.outputStream().buffered().use { out ->
                                        val buffer = ByteArray(128 * 1024)
                                        var total = 0L
                                        while (true) {
                                            val n = tar.read(buffer)
                                            if (n < 0) break
                                            total += n
                                            require(total < 320_000_000L) { "模型檔大小超過上限" }
                                            out.write(buffer, 0, n)
                                        }
                                    }
                                }
                            }
                            entry = tar.nextTarEntry
                        }
                    }
                }
                require(File(output, "model.int8.onnx").length() > 150_000_000L &&
                    File(output, "tokens.txt").length() > 50_000L) { "缺少完整 model.int8.onnx 或 tokens.txt" }
                val installed = modelFolder(app)
                installed.deleteRecursively()
                require(output.renameTo(installed)) { "無法完成原子資料夾搬移" }
                File(installed, ".ready").writeText("sha256:$SHA256\n")
                source.delete()
                message = "SenseVoice 安裝完成"
                callback.accept(message)
            } catch (ex: Exception) {
                output.deleteRecursively()
                message = "SenseVoice 安裝失敗：" + (ex.message ?: ex.javaClass.simpleName)
                callback.accept(message)
            } finally {
                installing = false
            }
        }
    }
}
