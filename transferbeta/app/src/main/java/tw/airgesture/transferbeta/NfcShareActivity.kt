package tw.airgesture.transferbeta

import android.app.Activity
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
import tw.airgesture.transferbeta.core.NfcTapProtocol
import tw.airgesture.transferbeta.core.ShareEntryPolicy
import tw.airgesture.transferbeta.core.SharedTextPolicy
import tw.airgesture.transferbeta.core.TransferPolicy
import java.io.File
import java.io.FileOutputStream
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
    private var webLink: String? = null
    private var prepared = false
    private var readerActive = false
    private var resumed = false
    private var transferredOwnership = false

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
            text = "先在相簿／檔案 App 按分享，選「NFC 碰一碰」，再把手機背面靠近 S25。\n" +
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
            text = "取消 NFC 傳送"
            setOnClickListener { finish() }
        })
        setContentView(area)
        if (intent.action != Intent.ACTION_SEND) {
            update("請從三星相簿／檔案管理員使用「分享 → NFC 碰一碰」")
            return
        }
        prepareSource(intent)
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

    override fun onResume() {
        super.onResume()
        resumed = true
        enableReaderIfReady()
    }

    private fun enableReaderIfReady() {
        if (!resumed || !prepared || readerActive || transferredOwnership) return
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
            update(if (source != null)
                "原始檔「${originalName}」已備妥（${source?.length()} bytes），請碰觸 S25 背面 NFC 位置。"
                else "網址已備妥，請碰觸 S25 背面 NFC 位置。")
        } catch (e: Exception) {
            update("無法啟動 NFC 讀卡模式：" + e.message)
        }
    }

    override fun onTagDiscovered(tag: Tag) {
        if (!awaitingBinding.compareAndSet(false, true) || !prepared ||
            transferredOwnership) return
        val iso = IsoDep.get(tag)
        if (iso == null) {
            awaitingBinding.set(false)
            update("已感應 NFC，但不是另一支安裝懸浮操作的手機；請調整位置。")
            return
        }
        try {
            iso.connect()
            iso.timeout = 2600
            if (!NfcTapProtocol.isSuccessfulProbe(
                    iso.transceive(NfcTapProtocol.selectApdu()))) {
                awaitingBinding.set(false)
                update("碰觸成功，但對方沒有回應懸浮操作協定。")
                return
            }
            val session = NfcTapProtocol.decodeSessionResponse(
                iso.transceive(NfcTapProtocol.getSessionCommand()))
            if (session == null) {
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
                        awaitingBinding.set(false)
                        update(message + "，可重新碰觸")
                        return@bindNfcTap
                    }
                    val file = source
                    val name = originalName
                    if (file != null && name != null) {
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
                    update("NFC 身分驗證成功，已開始對指定手機傳送；接收端會驗證原始檔 SHA-256。")
                    window.decorView.postDelayed({ if (!isFinishing) finish() }, 1500L)
                }
            }
        } catch (e: Exception) {
            awaitingBinding.set(false)
            update("NFC 碰觸失敗：" + (e.message ?: e.javaClass.simpleName) +
                "；請確認 NFC 已開啟並稍微移動兩支手機的位置")
        } finally {
            try { iso.close() } catch (_: Exception) { }
        }
    }

    override fun onPause() {
        resumed = false
        if (readerActive) {
            try { nfc?.disableReaderMode(this) } catch (_: Exception) { }
            readerActive = false
        }
        super.onPause()
    }

    override fun onDestroy() {
        if (!transferredOwnership) source?.delete()
        io.shutdown()
        super.onDestroy()
    }
}
