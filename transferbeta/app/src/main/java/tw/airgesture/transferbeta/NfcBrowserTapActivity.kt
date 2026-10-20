package tw.airgesture.transferbeta

import android.app.Activity
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import tw.airgesture.transferbeta.core.NfcTapProtocol
import java.util.concurrent.Executors

/**
 * Android system TECH_DISCOVERED launches this small transparent Activity for
 * a real ISO-DEP tap. ReaderMode is deliberately NOT used: it cannot run with
 * Chrome in front. We do not open a browser or infer its URL on this device.
 *
 * Only a verified Nearby peer can request a one-use authenticated web share.
 * Phone-to-phone discovery and Activity dispatch are Samsung/Android dependent.
 */
class NfcBrowserTapActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(incoming: Intent?) {
        if (handled || incoming?.action != NfcAdapter.ACTION_TECH_DISCOVERED) {
            finish()
            return
        }
        handled = true
        @Suppress("DEPRECATION")
        val tag = if (Build.VERSION.SDK_INT >= 33)
            incoming.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        else incoming.getParcelableExtra(NfcAdapter.EXTRA_TAG) as? Tag
        val iso = tag?.let(IsoDep::get)
        if (iso == null) { finish(); return }
        worker.execute {
            var session: tw.airgesture.transferbeta.core.NfcTapSessionCodec.Session? = null
            try {
                iso.connect()
                iso.timeout = 2600
                if (NfcTapProtocol.isSuccessfulProbe(
                        iso.transceive(NfcTapProtocol.selectApdu()))) {
                    session = NfcTapProtocol.decodeSessionResponse(
                        iso.transceive(NfcTapProtocol.getBrowserSessionCommand()))
                }
            } catch (_: Exception) {
                // Unrecognized ISO-DEP tags and unsupported NFC routing
                // never authorize any Nearby payload or prompt.
            } finally {
                try { iso.close() } catch (_: Exception) {}
            }
            val authorized = session
            main.post {
                if (!isFinishing && authorized != null) {
                    val manager = GlobalGestureService.transfer
                    if (GlobalGestureService.isActive && manager?.isConnected == true) {
                        manager.requestNfcBrowserLink(authorized.peerId, authorized.nonce)
                    } else {
                        Toast.makeText(this,
                            "請先在兩支手機啟動懸浮操作並完成附近配對",
                            Toast.LENGTH_LONG).show()
                    }
                }
                if (!isFinishing) finish()
            }
        }
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }
}
