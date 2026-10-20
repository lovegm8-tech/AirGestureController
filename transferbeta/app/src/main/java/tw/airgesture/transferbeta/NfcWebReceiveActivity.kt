package tw.airgesture.transferbeta

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import tw.airgesture.transferbeta.core.NfcTapProtocol
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * EXPLICIT foreground NFC web receiver. Android ReaderMode works reliably
 * only while this Activity remains in the foreground. The other phone stays
 * inside a supported browser with AirGrab's existing Nearby service active.
 *
 * A physical NFC session is *not* an authorization by itself. A URL request
 * goes only to an already verified Nearby peer and requires its one-use HCE
 * nonce to be consumed on the browser phone before the share begins.
 */
class NfcWebReceiveActivity : Activity(), NfcAdapter.ReaderCallback {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val readingTag = AtomicBoolean(false)
    private val adapter by lazy { NfcAdapter.getDefaultAdapter(this) }
    private lateinit var status: TextView
    private lateinit var transferStatus: TextView
    private var readerEnabled = false
    private var resumed = false
    private var requestPending = false
    private var lastReadAt = 0L

    private val refresh = object : Runnable {
        override fun run() {
            if (!resumed || isFinishing) return
            val manager = GlobalGestureService.transfer
            val nextStatus = when {
                !GlobalGestureService.isActive ->
                    "懸浮操作背景服務未啟動，請返回首頁啟用"
                manager?.isConnected != true ->
                    "附近傳檔尚未與信任裝置連線，請先連線後再碰觸"
                else -> "附近已驗證連線：" +
                    manager.connectedDeviceIds.size + " 台\n" +
                    (if (requestPending) "傳送進度：" + manager.status
                     else "接收待命，可以碰觸")
            }
            // Avoid waking and redrawing the foreground UI twice a second.
            // NFC is managed by Android ReaderMode, not this status timer.
            if (transferStatus.text.toString() != nextStatus) {
                transferStatus.text = nextStatus
            }
            main.postDelayed(this, 2000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(38, 42, 38, 36)
            gravity = Gravity.TOP
        }
        fun line(value: String, size: Float): TextView = TextView(this).apply {
            text = value
            textSize = size
            setPadding(0, 10, 0, 16)
        }
        panel.addView(line("NFC 網頁接收待命", 24f))
        panel.addView(line(
            "本機是接收端：請保持這個畫面開啟。\n" +
            "另一台手機是發送端：保持 Chrome、Google App 的網頁內頁或三星瀏覽器開啟，不需按分享。\n" +
            "兩台都需要開啟 NFC、解鎖、運行懸浮操作，且已完成 Nearby 信任連線。\n" +
            "兩台手機背面 NFC 天線位置靠近即可。收到網址後由原本的附近傳檔接收。", 15f))
        status = line("檢查 NFC 讀取功能…", 17f)
        panel.addView(status)
        transferStatus = line("確認附近連線…", 15f)
        panel.addView(transferStatus)
        panel.addView(Button(this).apply {
            text = "結束 NFC 網頁接收"
            setOnClickListener { finish() }
        })
        setContentView(panel)
    }

    private fun show(message: String) {
        main.post { if (!isFinishing && !isDestroyed) status.text = message }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        requestPending = false
        main.removeCallbacks(refresh)
        main.post(refresh)
        val nfc = adapter
        when {
            nfc == null -> show("本機沒有 NFC 硬體")
            !nfc.isEnabled -> show("NFC 尚未開啟，請在三星設定啟用 NFC 後返回這個畫面")
            else -> {
                try {
                    nfc.enableReaderMode(this, this,
                        NfcAdapter.FLAG_READER_NFC_A or
                        NfcAdapter.FLAG_READER_NFC_B or
                        NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
                        null)
                    readerEnabled = true
                    show("NFC 接收待命已啟動。請讓另一台開著網頁的手機背面靠近。")
                } catch (e: Exception) {
                    show("無法啟動 NFC 讀取模式：" +
                        e.javaClass.simpleName + "：" + e.message)
                }
            }
        }
    }

    override fun onTagDiscovered(tag: Tag) {
        if (!resumed || !readingTag.compareAndSet(false, true)) return
        val now = SystemClock.uptimeMillis()
        if (now - lastReadAt < 1300L) {
            readingTag.set(false)
            return
        }
        lastReadAt = now
        val iso = IsoDep.get(tag)
        if (iso == null) {
            show("已偵測到 NFC，但不是支援的手機 ISO-DEP 卡片")
            readingTag.set(false)
            return
        }
        show("① NFC 已感應；正在確認對方是否開啟支援的網頁…")
        worker.execute {
            var peerId: String? = null
            var nonce: String? = null
            var message = "NFC 通訊未完成"
            try {
                iso.connect()
                iso.timeout = 2600
                val select = iso.transceive(NfcTapProtocol.selectApdu())
                if (NfcTapProtocol.isSuccessfulProbe(select)) {
                    show("② 對方回應懸浮操作；正在取得一次性網頁分享驗證…")
                    val reply = iso.transceive(NfcTapProtocol.getBrowserSessionCommand())
                    val session = NfcTapProtocol.decodeSessionResponse(reply)
                    if (session != null) {
                        peerId = session.peerId
                        nonce = session.nonce
                    } else message = "NFC 已接通，但對方未開著支援的瀏覽器、背景服務未啟動或附近尚未連線"
                } else {
                    message = "NFC 已感應，但對方未回應懸浮操作 NFC 協定"
                }
            } catch (e: Exception) {
                message = "NFC 讀取失敗：" + e.javaClass.simpleName +
                    "（調整兩台背面位置後重試）"
            } finally {
                try { iso.close() } catch (_: Exception) {}
            }
            val id = peerId
            val token = nonce
            main.post {
                readingTag.set(false)
                if (!resumed || isFinishing || isDestroyed) return@post
                if (id == null || token == null) {
                    show(message)
                    return@post
                }
                val manager = GlobalGestureService.transfer
                if (!GlobalGestureService.isActive || manager?.isConnected != true) {
                    show("③ NFC 身分取得成功，但接收端尚未建立 Nearby 信任連線")
                    return@post
                }
                requestPending = true
                show("③ NFC 一次性身分已取得；正在要求網頁手機送出網址…")
                manager.requestNfcBrowserLink(id, token)
            }
        }
    }

    override fun onPause() {
        resumed = false
        main.removeCallbacks(refresh)
        if (readerEnabled) {
            try { adapter?.disableReaderMode(this) } catch (_: Exception) {}
            readerEnabled = false
        }
        super.onPause()
    }

    override fun onDestroy() {
        main.removeCallbacks(refresh)
        worker.shutdown()
        super.onDestroy()
    }
}
