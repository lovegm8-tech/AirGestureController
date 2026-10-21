package tw.airgesture.transferbeta

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import tw.airgesture.transferbeta.core.NfcTapProtocol

/**
 * HCE identity challenge for explicit user-selected NFC shares.
 * NFC itself never sends media; a single-use nonce binds the
 * already-verified Nearby destination after a physical tap.
 */
class NfcTapCardService : HostApduService() {
    private var selected = false
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var lastNearbyRecoveryAt = 0L

    /** NFC contact can wake a receiver's trusted-peer discovery, not system NFC. */
    private fun tryRecoverNearby() {
        if (!GlobalGestureService.isActive) return
        val now = SystemClock.uptimeMillis()
        if (now - lastNearbyRecoveryAt < 12_000L) return
        lastNearbyRecoveryAt = now
        main.post {
            val manager = GlobalGestureService.transfer
            if (GlobalGestureService.isActive && manager != null && !manager.isConnected) {
                manager.refresh()
            }
        }
    }

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        // Only explicit NFC Sharesheet sessions may request an identity token.
        if (NfcTapProtocol.isSessionCommand(commandApdu)) {
            if (!selected || !GlobalGestureService.isActive ||
                GlobalGestureService.transfer?.isConnected != true) {
                tryRecoverNearby()
                return NfcTapProtocol.notReadyResponse()
            }
            val prefs = getSharedPreferences("device", MODE_PRIVATE)
            val localId = prefs.getString("id", null) ?: return NfcTapProtocol.notReadyResponse()
            return try {
                NfcTapProtocol.sessionResponse(localId,
                    NfcTapSessionStore.issue(applicationContext))
            } catch (_: IllegalArgumentException) {
                NfcTapProtocol.notReadyResponse()
            }
        }
        val reply = NfcTapProtocol.respond(commandApdu)
        selected = NfcTapProtocol.isSuccessfulProbe(reply)
        if (selected) {
            // The receiver can wake a trusted-peer search on physical NFC contact.
            if (GlobalGestureService.transfer?.isConnected != true) tryRecoverNearby()
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putLong(LAST_PROBE_TIME, System.currentTimeMillis()).apply()
        }
        return reply
    }

    override fun onDeactivated(reason: Int) { selected = false }

    companion object {
        private const val PREFS = "nfc_tap_diagnostic"
        private const val LAST_PROBE_TIME = "last_hce_probe"

        fun lastProbeAt(context: android.content.Context): Long =
            context.getSharedPreferences(PREFS, MODE_PRIVATE)
                .getLong(LAST_PROBE_TIME, 0L)
    }
}
