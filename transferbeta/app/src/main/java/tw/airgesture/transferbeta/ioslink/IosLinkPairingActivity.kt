package tw.airgesture.transferbeta.ioslink

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.net.Inet4Address
import java.net.NetworkInterface

/** Opt-in QR pairing and Android share target. No changes to camera/gesture processing. */
class IosLinkPairingActivity : ComponentActivity() {
    private fun startReceiver() {
        ContextCompat.startForegroundService(this, Intent(this, TransferService::class.java))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE) {
            val shared = intent
            Thread {
                val count = TransferStore.enqueueShare(this, shared)
                runOnUiThread {
                    if (count > 0) {
                        startReceiver()
                        Toast.makeText(this, "已加入 " + count + " 個項目，請在 iPhone 開啟隔空抓取", Toast.LENGTH_LONG).show()
                    } else Toast.makeText(this, "來源 App 沒有提供可讀取的分享內容", Toast.LENGTH_LONG).show()
                    finish()
                }
            }.start()
            return
        }
        val root = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 52, 32, 32)
        }
        root.addView(content)
        fun label(s: String): TextView = TextView(this).apply {
            text = s
            textSize = 17f
            setPadding(0, 12, 0, 12)
            content.addView(this)
        }
        fun button(s: String, onClick: () -> Unit): Button = Button(this).apply {
            text = s
            setOnClickListener { onClick() }
            content.addView(this)
        }
        label("隔空抓取：iPhone 14 配對")
        label("目前測試版使用同一個 Wi-Fi／熱點。iPhone 打開 App 掃描下方 QR Code。")
        val pairing = pairingAddress()
        if (pairing == null) {
            label("沒有偵測到 Wi-Fi 區域網路 IPv4 位址，請先連接 Wi-Fi。")
        } else {
            try {
                val matrix = QRCodeWriter().encode(pairing, BarcodeFormat.QR_CODE, 600, 600)
                val bmp = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)
                for (y in 0 until 600) for (x in 0 until 600)
                    bmp.setPixel(x, y, if (matrix.get(x, y)) android.graphics.Color.BLACK
                        else android.graphics.Color.WHITE)
                content.addView(ImageView(this).apply { setImageBitmap(bmp); adjustViewBounds = true })
            } catch (_: Exception) { label("無法產生 QR Code") }
            button("複製配對連結") {
                val mgr = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                mgr.setPrimaryClip(ClipData.newPlainText("AirGrab 配對", pairing))
                Toast.makeText(this, "已複製，請勿分享給陌生人", Toast.LENGTH_LONG).show()
            }
        }
        button("開啟 iPhone 接收／Android 接收服務") {
            startReceiver()
            Toast.makeText(this, "區域網路服務已啟動", Toast.LENGTH_SHORT).show()
        }
        button("停止區域網路接收") {
            startService(Intent(this, TransferService::class.java).setAction(TransferService.STOP))
        }
        label("Google、Chrome、YouTube、Facebook 可從各自分享選單選「隔空抓取」。影片平台一般僅提供網址，不是影片原檔。")
        label("接收到的檔案目前保存於本 App 專屬下載資料夾；照片、影片原始位元不重新壓縮。")
        setContentView(root)
    }

    private fun pairingAddress(): String? {
        val address = NetworkInterface.getNetworkInterfaces()?.toList()
            ?.filter { it.isUp && !it.isLoopback }
            ?.sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
            ?.flatMap { it.inetAddresses.toList() }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { it.isSiteLocalAddress }?.hostAddress ?: return null
        val key = android.util.Base64.encodeToString(TransferStore.key(this),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
        return "airgrab://pair?host=" + address + "&port=" + TransferService.PORT + "&key=" + key
    }
}
