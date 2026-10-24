package tw.airgesture.transferbeta.subtitle

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Genuine, on-device Qwen3-0.6B INT4 post-editor. Never sends transcripts to
 * network: the only network request is Android's user-initiated model download.
 * LiteRT-LM inference runs off the main thread and is strictly optional.
 */
object SubtitleLocalAi {
    private const val PREF = "floating_subtitle"
    const val MODEL_NAME = "qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm"
    const val MODEL_URL = "https://huggingface.co/litert-community/Qwen3-0.6B-int4/resolve/main/" +
            MODEL_NAME
    const val MODEL_SHA256 = "2df6821ec12702dafd33915e7a1a1adc7c4b053f3672fd9555dfaf3a114c4139"
    const val MIN_MODEL_BYTES = 320_000_000L
    const val MAX_MODEL_BYTES = 380_000_000L
    private const val DOWNLOAD_ID = "ai_model_download_id"
    @Volatile private var lastStatus: String = "未下載本地 AI 模型"
    @Volatile private var engine: Engine? = null
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "floating-local-qwen").apply { priority = Thread.NORM_PRIORITY - 2 }
    }
    private val lock = Any()
    @Volatile private var preparing = false
    @Volatile private var permanentlyInvalid = false

    @JvmStatic fun modelFile(context: Context): File =
        File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), MODEL_NAME)

    @JvmStatic fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getBoolean("ai_local_enabled", true)

    @JvmStatic fun modelInstalled(context: Context): Boolean =
        modelFile(context).let { it.isFile && it.length() in MIN_MODEL_BYTES..MAX_MODEL_BYTES }

    @JvmStatic fun status(context: Context): String {
        if (engine != null) return "本地 Qwen AI 已就緒（離線潤飾）"
        if (preparing) return lastStatus
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val id = prefs.getLong(DOWNLOAD_ID, -1L)
        if (modelInstalled(context)) return "AI 模型已下載；啟動翻譯後會載入"
        if (id > 0) {
            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            try {
                manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val code = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        val current = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        return when (code) {
                            DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PENDING ->
                                "正在下載本地 AI：${current / 1_000_000} MB" +
                                        if (total > 0) " / ${total / 1_000_000} MB" else ""
                            DownloadManager.STATUS_PAUSED -> {
                                val why = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                                val reason = when (why) {
                                    DownloadManager.PAUSED_QUEUED_FOR_WIFI ->
                                        "目前排隊等待 Wi-Fi；新版可重新按下載使用熱點"
                                    DownloadManager.PAUSED_WAITING_FOR_NETWORK ->
                                        "正在等待可上網的連線"
                                    DownloadManager.PAUSED_WAITING_TO_RETRY ->
                                        "連線暫時失敗，系統會重試"
                                    else -> "下載暫停（代碼 $why）"
                                }
                                reason
                            }
                            DownloadManager.STATUS_FAILED -> {
                                val why = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                                val reason = when (why) {
                                    DownloadManager.ERROR_INSUFFICIENT_SPACE -> "手機儲存空間不足"
                                    DownloadManager.ERROR_HTTP_DATA_ERROR -> "下載站連線中斷"
                                    DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "下載站轉址過多"
                                    else -> "下載錯誤代碼 $why"
                                }
                                "AI 模型下載失敗：$reason，請按下載重新嘗試"
                            }
                            DownloadManager.STATUS_SUCCESSFUL -> "模型檔案待檢查；需約 347 MB"
                            else -> lastStatus
                        }
                    }
                }
            } catch (_: Exception) { }
        }
        return "尚未安裝 AI 模型（約 347 MB，免費）"
    }

    /** Only invoked by a user's explicit button click. */
    @JvmStatic fun download(context: Context): String {
        if (modelInstalled(context) && !permanentlyInvalid) return "AI 模型已存在，無須重複下載"
        permanentlyInvalid = false
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val pref = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val oldId = pref.getLong(DOWNLOAD_ID, -1)
        if (oldId > 0) try { manager.remove(oldId) } catch (_: Exception) { }
        val target = modelFile(context)
        target.parentFile?.mkdirs()
        if (target.exists()) target.delete()
        return try {
            val req = DownloadManager.Request(Uri.parse(MODEL_URL))
                .setTitle("懸浮操作 · 本地 Qwen AI 約 347 MB")
                .setDescription("免費離線中文翻譯潤飾模型")
                // Android commonly marks phone hotspots as metered Wi-Fi.
                // The user explicitly clicked Download, so allow tethering
                // and mobile data rather than silently waiting for unmetered Wi-Fi.
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationUri(Uri.fromFile(target))
            val id = manager.enqueue(req)
            pref.edit().putLong(DOWNLOAD_ID, id)
                .remove("ai_model_verified_sha").apply()
            lastStatus = "已要求下載（約 347 MB），可使用 Wi-Fi／手機熱點／行動數據；請留意流量"
            lastStatus
        } catch (error: Exception) {
            "下載啟動失敗：${error.javaClass.simpleName} ${error.message ?: ""}"
        }
    }

    /**
     * Offline recovery: import the already-downloaded .litertlm file from
     * Files/Downloads, e.g. one transferred from another Android phone via
     * Nearby. Copy + verify on a worker thread; no network, no root access.
     */
    @JvmStatic fun importModel(context: Context, uri: Uri,
                               callback: java.util.function.Consumer<String>) {
        val app = context.applicationContext
        executor.execute {
            val target = modelFile(app)
            val temp = File(target.parentFile, MODEL_NAME + ".importing")
            try {
                if (engine != null) {
                    callback.accept("請先關閉懸浮翻譯，再匯入 AI 模型")
                    return@execute
                }
                target.parentFile?.mkdirs()
                temp.delete()
                lastStatus = "正在匯入模型並檢查完整性…"
                val digest = MessageDigest.getInstance("SHA-256")
                var copied = 0L
                val source = app.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("無法讀取選取的檔案")
                source.buffered().use { input ->
                    temp.outputStream().buffered().use { output ->
                        val buffer = ByteArray(131072)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            copied += read
                            if (copied > MAX_MODEL_BYTES)
                                throw IllegalArgumentException("檔案超過模型大小限制")
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                        }
                    }
                }
                if (copied !in MIN_MODEL_BYTES..MAX_MODEL_BYTES)
                    throw IllegalArgumentException("不是完整的 Qwen 模型，大小：${copied / 1_000_000} MB")
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                if (!hash.equals(MODEL_SHA256, ignoreCase = true))
                    throw IllegalArgumentException("模型 SHA256 不符，請確認選擇正確的 no-think .litertlm")
                // Prevent a concurrent Android download from overwriting import.
                val prefs = app.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                val previous = prefs.getLong(DOWNLOAD_ID, -1L)
                if (previous > 0) {
                    val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    dm.remove(previous)
                }
                if (target.exists() && !target.delete())
                    throw IllegalStateException("無法替換舊的 AI 模型")
                if (!temp.renameTo(target))
                    throw IllegalStateException("無法儲存已驗證的 AI 模型")
                val stamp = target.length().toString() + ":" + target.lastModified()
                prefs.edit().remove(DOWNLOAD_ID)
                    .putString("ai_model_verified_sha", stamp).apply()
                permanentlyInvalid = false
                lastStatus = "模型已匯入且 SHA256 驗證通過，可啟用離線 AI"
                callback.accept(lastStatus)
            } catch (e: Exception) {
                lastStatus = "模型匯入失敗：${e.message ?: e.javaClass.simpleName}"
                callback.accept(lastStatus)
            } finally {
                temp.delete()
            }
        }
    }

    @JvmStatic fun release() {
        // Closing while a generation runs risks native crashes; enqueue
        // cleanup on the engine's serial executor, not on the Android UI.
        executor.execute {
            try { engine?.close() } catch (_: Exception) { }
            engine = null
        }
    }

    /** Call only for finalized ASR sentences, never every 520ms partial. */
    @JvmStatic fun refine(context: Context, sourceLanguage: String, original: String,
                          fastChinese: String, previousChinese: String,
                          callback: java.util.function.Consumer<String>) {
        if (!enabled(context) || !modelInstalled(context) || permanentlyInvalid) return
        if (sourceLanguage != "en" && sourceLanguage != "ja") return
        if (original.length !in 5..260 || fastChinese.isBlank()) return
        val app = context.applicationContext
        executor.execute {
            if (!enabled(app)) return@execute
            try {
                if (!ensureInitialized(app)) return@execute
                val instruction = "你是繁體中文字幕校對者。只輸出一行自然、簡短、準確的台灣繁體中文譯文。" +
                        "嚴禁添加說明、引號、英文原文、說話者、標記或換行。保留人名專有名詞。" +
                        "參考上一句以理解上下文，勿憑空增加劇情。"
                val prompt = "來源語言：" + (if (sourceLanguage == "ja") "日文" else "英文") +
                        "\n上一句中文字幕：${previousChinese.take(90)}" +
                        "\n語音辨識原文：${original.take(260)}" +
                        "\n快速譯文：${fastChinese.take(130)}" +
                        "\n請直接輸出修正後的繁體中文字幕："
                val cfg = ConversationConfig(
                    systemInstruction = Contents.of(instruction),
                    samplerConfig = SamplerConfig(topK = 8, topP = 0.8, temperature = 0.15),
                    maxOutputToken = 90,
                )
                val output = engine!!.createConversation(cfg).use { conversation ->
                    conversation.sendMessage(prompt).toString()
                }
                val normalized = SubtitleAiOutputPolicy.normalize(output)
                if (SubtitleAiOutputPolicy.accept(normalized, fastChinese))
                    callback.accept(normalized)
                lastStatus = "Qwen AI 文字潤飾運作中"
            } catch (e: Throwable) {
                lastStatus = "AI 暫停，仍使用快速翻譯：${e.javaClass.simpleName}"
            }
        }
    }

    private fun ensureInitialized(context: Context): Boolean {
        if (engine != null) return true
        if (preparing || permanentlyInvalid) return false
        synchronized(lock) { if (engine != null) return true; preparing = true }
        try {
            val file = modelFile(context)
            if (!modelInstalled(context)) return false
            val pref = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val stamp = file.length().toString() + ":" + file.lastModified()
            if (pref.getString("ai_model_verified_sha", "") != stamp) {
                lastStatus = "首次使用正在驗證模型完整性…"
                val hash = MessageDigest.getInstance("SHA-256")
                file.inputStream().buffered().use { stream ->
                    val bytes = ByteArray(131072)
                    while (true) {
                        val n = stream.read(bytes)
                        if (n < 0) break
                        hash.update(bytes, 0, n)
                    }
                }
                val actual = hash.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(MODEL_SHA256, true)) {
                    permanentlyInvalid = true
                    lastStatus = "下載模型雜湊不符；請刪除重新下載"
                    return false
                }
                pref.edit().putString("ai_model_verified_sha", stamp).apply()
            }
            lastStatus = "正在載入本地 Qwen（不影響先行字幕）…"
            val instance = Engine(EngineConfig(
                modelPath = file.absolutePath,
                backend = Backend.CPU(),
                cacheDir = context.cacheDir.absolutePath,
            ))
            runBlocking { instance.initialize() }
            engine = instance
            lastStatus = "本地 Qwen AI 已就緒（離線潤飾）"
            return true
        } catch (error: Throwable) {
            lastStatus = "AI 初始化失敗：${error.javaClass.simpleName}；回退快速翻譯"
            return false
        } finally { preparing = false }
    }
}
