package tw.airgesture.transferbeta

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import java.text.DateFormat
import java.util.Date
import android.content.Intent
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import tw.airgesture.transferbeta.core.AirGrabBundle
import tw.airgesture.transferbeta.core.NfcTapProtocol
import tw.airgesture.transferbeta.core.ShareEntryPolicy
import tw.airgesture.transferbeta.core.SharedTextPolicy
import tw.airgesture.transferbeta.core.TransferPolicy
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Explicit Gallery/Files/Browsers Sharesheet action, followed by NFC contact.
 *
 * Android prevents a third-party NFC reader from operating while Samsung
 * Gallery is foreground. Gallery MUST explicitly share a content:// URI to
 * this Activity first; this screen then owns reader-mode until the NFC tap.
 * We never infer the foreground image from screenshots, OCR, MediaStore or
 * accessibility. An original file is copied byte-for-byte while the temporary
 * Sharesheet URI grant is valid; Nearby sends the checksum-verified original.
 */
class NfcShareActivity : Activity(), NfcAdapter.ReaderCallback {
    private val io = Executors.newSingleThreadExecutor()
    private val nfc by lazy { NfcAdapter.getDefaultAdapter(this) }
    private val awaitingBinding = AtomicBoolean(false)
    private lateinit var status: TextView
    private var source: File? = null
    private var originalName: String? = null
    private var multiSources: List<MultiPeerTransferManager.StagedFile>? = null
    private var webLink: String? = null
    private var prepared = false
    private var readerActive = false
    private var resumed = false
    private var transferredOwnership = false
    private val uiHandler = Handler(Looper.getMainLooper())
    private var readerSetupAttempted = false
    private var readerEpoch = 0
    private var nfcAutoRetryDone = false
    @Volatile private var nfcTagSeen = false

    /** Local diagnostics only: no file contents, peer identity or session nonce. */
    private fun traceNfc(event: String) {
        synchronized(NfcShareActivity::class.java) {
            val prefs = getSharedPreferences("nfc_reader_diagnostics", MODE_PRIVATE)
            val stamp = DateFormat.getDateTimeInstance().format(Date())
            val history = prefs.getString("events", "").orEmpty()
            prefs.edit().putString("events", (history + "$stamp  $event\n").takeLast(3600)).apply()
        }
    }

    /** Restart only this Activity's reader session, never the system NFC switch. */
    private fun restartReader(reason: String) {
        if (!resumed || !prepared || transferredOwnership || isFinishing) return
        if (awaitingBinding.get()) {
            update("NFC 正在驗證接收裝置，請稍候")
            return
        }
        val adapter = nfc
        if (adapter == null || !adapter.isEnabled) {
            traceNfc("RESTART_BLOCKED_NFC_OFF")
            update("手機 NFC 未開啟，請先在系統設定中啟用")
            return
        }
        ++readerEpoch
        try {
            adapter.disableReaderMode(this)
        } catch (e: Exception) {
            traceNfc("DISABLE_READER_${e.javaClass.simpleName}")
        }
        readerActive = false
        traceNfc("RESTART_READER_$reason")
        update("正在重新初始化 NFC 讀取模式…")
        uiHandler.postDelayed({
            if (resumed && prepared && !transferredOwnership && !isFinishing) {
                enableReaderIfReady()
            }
        }, 300L)
    }

    private fun scheduleReaderNoTagRecovery() {
        val generation = ++readerEpoch
        uiHandler.postDelayed({
            if (generation == readerEpoch && readerActive && resumed &&
                !nfcTagSeen && !nfcAutoRetryDone && !awaitingBinding.get() &&
                !transferredOwnership) {
                nfcAutoRetryDone = true
                traceNfc("NO_TAG_12S_AUTO_RETRY")
                restartReader("12 秒未感應")
            }
        }, 12_000L)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val area = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(38, 38, 38, 32)
            gravity = Gravity.TOP
        }
        val title = TextView(this).apply {
            text = "懸浮操作 · NFC 碰一碰"
            textSize = 23f
            setPadding(0, 4, 0, 20)
        }
        area.addView(title)
        area.addView(TextView(this).apply {
            text = "先在相簿／檔案 App 選取 1～32 個檔案，按分享並選「NFC 碰一碰」，再將背面靠近接收手機。\n" +
                "只傳給 NFC 碰觸且已完成原本配對驗證的裝置；無須手勢。\n" +
                "若對方的傳檔背景服務尚未啟動、螢幕鎖定或 Nearby 未連線，請先排除後重試。"
            textSize = 15f
            setPadding(0, 4, 0, 18)
        })
        status = TextView(this).apply {
            text = "正在確認分享的原始檔…"
            textSize = 17f
            setPadding(0, 14, 0, 20)
        }
        area.addView(status)
        area.addView(Button(this).apply {
            text = "重新初始化 NFC 讀取模式"
            setOnClickListener { restartReader("使用者手動重試") }
        })
        area.addView(Button(this).apply {
            text = "查看 NFC 診斷紀錄"
            setOnClickListener {
                val events = getSharedPreferences("nfc_reader_diagnostics", MODE_PRIVATE)
                    .getString("events", null).orEmpty()
                AlertDialog.Builder(this@NfcShareActivity)
                    .setTitle("NFC 讀取診斷（只存在本機）")
                    .setMessage(events.ifBlank { "目前尚無紀錄" })
                    .setPositiveButton("關閉", null).show()
            }
        })
        area.addView(Button(this).apply {
            text = "取消 NFC 傳送"
            setOnClickListener { finish() }
        })
        setContentView(area)
        when (intent.action) {
            Intent.ACTION_SEND -> prepareSource(intent)
            Intent.ACTION_SEND_MULTIPLE -> prepareMultipleSource(intent)
            else -> update("請從三星相簿／檔案管理員使用「分享 → NFC 碰一碰」")
        }
    }

    private fun update(message: String) {
        runOnUiThread { if (!isFinishing) status.text = message }
    }

    private fun prepareSource(shared: Intent) {
        val text = shared.getStringExtra(Intent.EXTRA_TEXT)
            ?: shared.clipData?.getItemAt(0)?.text?.toString()
        val link = SharedTextPolicy.onlyWebLink(text)
        if (shared.type?.startsWith("text/") == true && link != null) {
            webLink = link
            prepared = true
            enableReaderIfReady()
            return
        }
        @Suppress("DEPRECATION")
        val extra = if (Build.VERSION.SDK_INT >= 33)
            shared.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else shared.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        val clips = (0 until (shared.clipData?.itemCount ?: 0))
            .map { shared.clipData?.getItemAt(it)?.uri?.toString() }
        val uri = ShareEntryPolicy.chooseSingleOriginal(
            shared.action, extra?.toString(), clips)?.let(Uri::parse)
        if (uri == null) {
            if (link != null) {
                webLink = link
                prepared = true
                enableReaderIfReady()
            } else update("分享內容沒有提供原始檔讀取權限，無法碰觸傳送。")
            return
        }
        io.execute {
            var cacheFile: File? = null
            try {
                val proposed = contentResolver.query(uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: "original-file"
                val safe = TransferPolicy.safeName(proposed)
                    ?: error("無法讀取原始檔名")
                val cache = File(cacheDir, "nfc-original-staging").apply { mkdirs() }
                // Previous process interruptions may have left orphan staging
                // files. Do not touch files from recent active transfers.
                cache.listFiles()?.filter { it.isFile &&
                    System.currentTimeMillis() - it.lastModified() > 86_400_000L
                }?.forEach { it.delete() }
                cacheFile = File.createTempFile("nfc-original-", ".bin", cache)
                var size = 0L
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(cacheFile).buffered().use { out ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            if (n == 0) continue
                            size += n
                            check(size <= TransferPolicy.MAX_BYTES) {
                                "檔案超過 " + TransferPolicy.capacityLabel() + " 上限"
                            }
                            out.write(buffer, 0, n)
                        }
                    }
                } ?: error("三星相簿未授權讀取原檔")
                check(size > 0) { "分享原始檔是空的" }
                val preparedFile = requireNotNull(cacheFile)
                cacheFile = null
                runOnUiThread {
                    if (isFinishing || isDestroyed) preparedFile.delete()
                    else {
                        source = preparedFile
                        originalName = safe
                        prepared = true
                        enableReaderIfReady()
                    }
                }
            } catch (e: Exception) {
                cacheFile?.delete()
                update("準備原始檔失敗：" + (e.message ?: "讀取權限不足"))
            }
        }
    }

    /**
     * Stage every original separately while Android grants the temporary
     * Sharesheet URIs. Calculate each checksum during the staging copy so
     * the NFC session is not consumed by a second full-file hash pass.
     * The receiver gets a single authenticated Nearby queue, not a ZIP.
     */
    @Suppress("DEPRECATION")
    private fun prepareMultipleSource(shared: Intent) {
        val explicit = if (Build.VERSION.SDK_INT >= 33)
            shared.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else shared.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
        val clips = (0 until (shared.clipData?.itemCount ?: 0))
            .map { shared.clipData?.getItemAt(it)?.uri?.toString() }
        val originals = ShareEntryPolicy.chooseMultipleOriginals(
            shared.action, explicit?.map { it.toString() }, clips
        )?.map(Uri::parse)
        if (originals == null) {
            update("NFC 多檔請一次選取 2～${AirGrabBundle.MAX_FILES} 個不重複的原始檔案")
            return
        }
        update("準備 ${originals.size} 個原始檔案；逐檔讀取並驗證…")
        io.execute {
            val staged = mutableListOf<MultiPeerTransferManager.StagedFile>()
            try {
                val cache = File(cacheDir, "nfc-original-staging").apply { mkdirs() }
                cache.listFiles()?.filter { it.isFile &&
                    System.currentTimeMillis() - it.lastModified() > 86_400_000L
                }?.forEach { it.delete() }
                for ((index, uri) in originals.withIndex()) {
                    val proposed = contentResolver.query(uri,
                        arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    } ?: "original-file-${index + 1}"
                    val name = TransferPolicy.safeName(proposed)
                        ?: error("第 ${index + 1} 個檔案名稱無效")
                    val file = File.createTempFile("nfc-part-", ".bin", cache)
                    staged.add(MultiPeerTransferManager.StagedFile(file, name))
                    val digest = MessageDigest.getInstance("SHA-256")
                    var size = 0L
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(file).buffered().use { output ->
                            val buffer = ByteArray(128 * 1024)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                if (n == 0) continue
                                size += n.toLong()
                                check(size <= TransferPolicy.MAX_BYTES) {
                                    "第 ${index + 1} 個檔案超過裝置上限"
                                }
                                digest.update(buffer, 0, n)
                                output.write(buffer, 0, n)
                            }
                        }
                    } ?: error("第 ${index + 1} 個檔案沒有原始檔讀取權限")
                    check(size > 0) { "第 ${index + 1} 個檔案為空" }
                    staged[index] = MultiPeerTransferManager.StagedFile(file, name,
                        digest.digest().joinToString("") { "%02x".format(it) })
                    update("已備妥 ${index + 1}/${originals.size} 個原始檔案")
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed) staged.forEach { it.file.delete() }
                    else {
                        multiSources = staged.toList()
                        prepared = true
                        enableReaderIfReady()
                    }
                }
            } catch (e: Exception) {
                staged.forEach { it.file.delete() }
                update("NFC 多檔準備失敗：" + (e.message ?: "無法讀取原始檔案"))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        enableReaderIfReady()
    }

    private fun enableReaderIfReady() {
        if (!resumed || !prepared || readerActive || transferredOwnership) return
        // Each explicit share starts with a clean app-owned NFC reader session.
        // The public Android API cannot toggle the device-wide NFC radio.
        if (!readerSetupAttempted) {
            readerSetupAttempted = true
            restartReader("傳送前預備")
            return
        }
        val manager = GlobalGestureService.transfer
        if (!GlobalGestureService.isActive || manager == null || !manager.isConnected) {
            update("尚未連線到已配對裝置。請先在兩台手機啟動附近傳檔、解鎖並完成配對；再返回此畫面碰觸。")
            // Reader can still run: when a verified peer comes online later the
            // handler will check the connection again before NFC binding.
        }
        val adapter = nfc
        if (adapter == null) { update("這支手機沒有 NFC 讀卡功能"); return }
        if (!adapter.isEnabled) { update("請先在三星設定中開啟 NFC，返回此畫面再試"); return }
        try {
            adapter.enableReaderMode(this, this,
                NfcAdapter.FLAG_READER_NFC_A or
                    NfcAdapter.FLAG_READER_NFC_B or
                    NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null)
            readerActive = true
            traceNfc("READER_ENABLED")
            scheduleReaderNoTagRecovery()
            update(when {
                multiSources != null ->
                    "${multiSources?.size} 個原始檔已備妥，請碰觸接收手機背面 NFC 位置。"
                source != null ->
                    "原始檔「${originalName}」已備妥（${source?.length()} bytes），請碰觸接收手機背面 NFC 位置。"
                else -> "網址已備妥，請碰觸接收手機背面 NFC 位置。"
            })
        } catch (e: Exception) {
            traceNfc("READER_ENABLE_ERROR_${e.javaClass.simpleName}")
            update("無法啟動 NFC 讀卡模式：" + e.message)
        }
    }

    override fun onTagDiscovered(tag: Tag) {
        nfcTagSeen = true
        traceNfc("TAG_DISCOVERED")
        if (!awaitingBinding.compareAndSet(false, true) || !prepared ||
            transferredOwnership) return
        val iso = IsoDep.get(tag)
        if (iso == null) {
            traceNfc("TAG_NOT_ISODEP")
            awaitingBinding.set(false)
            update("已感應 NFC，但不是另一支安裝懸浮操作的手機；請調整位置。")
            return
        }
        try {
            iso.connect()
            iso.timeout = 2600
            if (!NfcTapProtocol.isSuccessfulProbe(
                    iso.transceive(NfcTapProtocol.selectApdu()))) {
                traceNfc("HCE_SELECT_FAILED")
                awaitingBinding.set(false)
                update("碰觸成功，但對方沒有回應懸浮操作協定。")
                return
            }
            val session = NfcTapProtocol.decodeSessionResponse(
                iso.transceive(NfcTapProtocol.getSessionCommand()))
            if (session == null) {
                traceNfc("HCE_SESSION_UNAVAILABLE")
                awaitingBinding.set(false)
                update("對方 NFC 已回應，但背景傳檔未啟動或沒有已驗證的 Nearby 連線。")
                return
            }
            runOnUiThread {
                val manager = GlobalGestureService.transfer
                if (!prepared || manager?.isConnected != true || isFinishing) {
                    awaitingBinding.set(false)
                    update("請先與接收手機建立 Nearby 已配對連線。")
                    return@runOnUiThread
                }
                update("NFC 已感應 · 正在透過已配對連線確認接收裝置…")
                manager.bindNfcTap(session.peerId, session.nonce) { ok, ticket, message ->
                    if (isFinishing) {
                        if (!ok) awaitingBinding.set(false)
                        return@bindNfcTap
                    }
                    if (!ok) {
                        traceNfc("NEARBY_BIND_FAILED")
                        awaitingBinding.set(false)
                        update(message + "，可重新碰觸")
                        return@bindNfcTap
                    }
                    val file = source
                    val name = originalName
                    val many = multiSources
                    if (many != null) {
                        transferredOwnership = true
                        multiSources = null
                        manager.offerNfcFiles(many, session.peerId, ticket)
                    } else if (file != null && name != null) {
                        transferredOwnership = true
                        source = null
                        manager.offerNfcFile(file, name, session.peerId, ticket)
                    } else if (webLink != null) {
                        transferredOwnership = true
                        manager.offerNfcLink(webLink!!, session.peerId, ticket)
                    } else {
                        awaitingBinding.set(false)
                        update("分享的檔案尚未準備好，請重新操作")
                        return@bindNfcTap
                    }
                    traceNfc("NFC_BIND_SUCCESS_TRANSFER_STARTED")
                    update("NFC 身分驗證成功，已開始對指定手機傳送；接收端會驗證原始檔 SHA-256。")
                    window.decorView.postDelayed({ if (!isFinishing) finish() }, 1500L)
                }
            }
        } catch (e: Exception) {
            traceNfc("TAG_COMM_ERROR_${e.javaClass.simpleName}")
            awaitingBinding.set(false)
            update("NFC 碰觸失敗：" + (e.message ?: e.javaClass.simpleName) +
                "；請確認 NFC 已開啟並稍微移動兩支手機的位置")
        } finally {
            try { iso.close() } catch (_: Exception) { }
        }
    }

    override fun onPause() {
        resumed = false
        ++readerEpoch
        uiHandler.removeCallbacksAndMessages(null)
        if (readerActive) {
            try { nfc?.disableReaderMode(this) } catch (_: Exception) { }
            readerActive = false
            traceNfc("READER_DISABLED_ON_PAUSE")
        }
        super.onPause()
    }

    override fun onDestroy() {
        if (!transferredOwnership) {
            source?.delete()
            multiSources?.forEach { it.file.delete() }
        }
        io.shutdown()
        super.onDestroy()
    }
}
