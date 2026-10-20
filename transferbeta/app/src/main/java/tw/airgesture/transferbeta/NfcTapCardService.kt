package tw.airgesture.transferbeta

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import tw.airgesture.transferbeta.core.NfcTapProtocol

/**
 * NFC HCE probe: another phone using foreground ReaderMode may SELECT our AID
 * while this phone remains unlocked in Gallery. This does NOT transmit a photo.
 */
class NfcTapCardService : HostApduService() {
    private var selected = false

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        // Preserve the previous diagnostic SELECT reply and follow it with a
        // separate session APDU. The reader still must be in OUR app foreground.
        if (NfcTapProtocol.isBrowserSessionCommand(commandApdu)) {
            if (!selected || !GlobalGestureService.isActive ||
                GlobalGestureService.transfer?.isConnected != true ||
                GlobalGrabAccessibilityService.foregroundPackage != "com.android.chrome") {
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
