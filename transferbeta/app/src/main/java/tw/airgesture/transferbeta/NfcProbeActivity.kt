package tw.airgesture.transferbeta

import android.app.Activity
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import tw.airgesture.transferbeta.core.NfcTapProtocol
import java.text.DateFormat
import java.util.Date

/**
 * Isolated hardware feasibility experiment. Never auto-sends or accepts files.
 * Android enableReaderMode works ONLY while this Activity is foreground.
 * S26 Ultra Gallery in front is therefore explicitly NOT a supported trigger.
 */
class NfcProbeActivity : Activity(), NfcAdapter.ReaderCallback {
    private lateinit var output: TextView
    private val adapter by lazy { NfcAdapter.getDefaultAdapter(this) }
    @Volatile private var reading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 40, 36, 28)
            gravity = Gravity.TOP
        }
        fun line(value: String, size: Float): TextView = TextView(this).apply {
            text = value
            textSize = size
            setPadding(0, 12, 0, 12)
        }
        panel.addView(line("NFC 碰一碰 · 技術驗證", 24f))
        panel.addView(line(
            "這是雙機 NFC 接觸測試，不是照片傳送。\n" +
                "S26 Ultra：停留在這個畫面（讀卡端）。\n" +
                "S25：亮屏解鎖，可停留在三星相簿（卡片模擬端）。\n" +
                "兩機背面 NFC 天線靠近，檢查下方結果。\n\n" +
                "注意：若 S26 Ultra 切回三星相簿，本 App 的 NFC 讀卡模式就會停用；" +
                "NFC 無法取得相簿當前照片的原檔授權。" , 15f))
        output = line("等待 NFC 硬體檢查…", 17f)
        panel.addView(output)
        setContentView(panel)
    }

    override fun onResume() {
        super.onResume()
        val nfc = adapter
        val hce = packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)
        val peerSeenAt = NfcTapCardService.lastProbeAt(this)
        val previous = if (peerSeenAt > 0)
            "\n本機上次回應 NFC：${DateFormat.getDateTimeInstance().format(Date(peerSeenAt))}"
        else ""
        when {
            nfc == null -> output.text = "這支手機未提供 NFC 硬體。"
            !nfc.isEnabled -> output.text = "NFC 已關閉，請先在 Android 設定中開啟。"
            else -> {
                output.text = "NFC 已開啟；本機 HCE：${if (hce) "支援" else "不支援"}。" +
                    "\n等待另一台安裝本 App 的手機靠近…\n" +
                    "只驗證 NFC 碰觸，不驗證 Nearby 配對身分。$previous"
                try {
                    nfc.enableReaderMode(
                        this, this,
                        NfcAdapter.FLAG_READER_NFC_A or
                            NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
                        null
                    )
                    reading = true
                } catch (e: Exception) {
                    output.text = "無法啟用 NFC 讀卡：${e.javaClass.simpleName}：${e.message}"
                }
            }
        }
    }

    override fun onPause() {
        if (reading) {
            try { adapter?.disableReaderMode(this) } catch (_: Exception) { }
            reading = false
        }
        super.onPause()
    }

    override fun onTagDiscovered(tag: Tag) {
        val iso = IsoDep.get(tag)
        if (iso == null) {
            runOnUiThread { output.text = "已感應到 NFC，但不是本 App 的 ISO-DEP 卡片服務。" }
            return
        }
        val result = try {
            iso.connect()
            iso.timeout = 2500
            val answer = iso.transceive(NfcTapProtocol.selectApdu())
            if (NfcTapProtocol.isSuccessfulProbe(answer)) {
                "NFC 接觸成功：對方回應懸浮操作測試協定。\n" +
                    "注意：尚未驗證配對身分，也尚未取得任何照片；不會發送檔案。"
            } else "NFC 已觸發，但對方未回應懸浮操作的測試協定。"
        } catch (e: Exception) {
            "NFC 通訊未完成：${e.javaClass.simpleName}：${e.message}"
        } finally {
            try { iso.close() } catch (_: Exception) { }
        }
        runOnUiThread { if (!isFinishing) output.text = result }
    }
}
