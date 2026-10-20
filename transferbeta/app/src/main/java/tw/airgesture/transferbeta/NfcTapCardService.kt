package tw.airgesture.transferbeta

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import tw.airgesture.transferbeta.core.NfcTapProtocol
import tw.airgesture.transferbeta.core.NfcWebSharePolicy

/**
 * Responds to a trusted peer's NFC tap while this phone is displaying
 * a supported browser. NFC conveys a one-time nonce; the existing verified
 * Nearby channel carries only an authorized current-page URL.
 */
class NfcTapCardService : HostApduService() {
    private var selected = false

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        // Preserve the manual Sharesheet NFC flow and diagnostic SELECT while
        // allowing background HCE for the separate authenticated web tap.
        if (NfcTapProtocol.isBrowserSessionCommand(commandApdu)) {
            if (!selected || !GlobalGestureService.isActive ||
                GlobalGestureService.transfer?.isConnected != true ||
                !NfcWebSharePolicy.allowsForeground(
                    GlobalGrabAccessibilityService.foregroundPackage)) {
                return NfcTapProtocol.notReadyResponse()
            }
            val localId = getSharedPreferences("device", MODE_PRIVATE)
                .getString("id", null) ?: return NfcTapProtocol.notReadyResponse()
            return try {
                NfcTapProtocol.sessionResponse(localId,
                    NfcTapSessionStore.issue(applicationContext))
            } catch (_: IllegalArgumentException) {
                NfcTapProtocol.notReadyResponse()
            }
        }
        if (NfcTapProtocol.isSessionCommand(commandApdu)) {
            if (!selected || !GlobalGestureService.isActive ||
                GlobalGestureService.transfer?.isConnected != true) {
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
