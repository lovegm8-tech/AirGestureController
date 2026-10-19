package tw.airgesture.transferbeta

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import tw.airgesture.transferbeta.core.NfcTapProtocol

/**
 * NFC HCE probe: another phone using foreground ReaderMode may SELECT our AID
 * while this phone remains unlocked in Gallery. This does NOT transmit a photo.
 */
class NfcTapCardService : HostApduService() {
    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        val reply = NfcTapProtocol.respond(commandApdu)
        if (NfcTapProtocol.isSuccessfulProbe(reply)) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putLong(LAST_PROBE_TIME, System.currentTimeMillis()).apply()
        }
        return reply
    }

    override fun onDeactivated(reason: Int) = Unit

    companion object {
        private const val PREFS = "nfc_tap_diagnostic"
        private const val LAST_PROBE_TIME = "last_hce_probe"

        fun lastProbeAt(context: android.content.Context): Long =
            context.getSharedPreferences(PREFS, MODE_PRIVATE)
                .getLong(LAST_PROBE_TIME, 0L)
    }
}
