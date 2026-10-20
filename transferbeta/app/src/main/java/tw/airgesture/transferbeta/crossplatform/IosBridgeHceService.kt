package tw.airgesture.transferbeta.crossplatform

import android.nfc.cardemulation.HostApduService
import android.os.Bundle

/**
 * Separate iOS-only ISO-DEP HCE AID. DOES NOT touch NfcTapCardService,
 * NFC Android reader mode or Nearby and never accesses arbitrary on-screen data.
 */
class IosBridgeHceService : HostApduService() {
    private var selected = false
    private var helloRead = false

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        if (IosBridgeProtocol.isSelect(commandApdu)) {
            selected = true
            helloRead = false
            return IosBridgeProtocol.OK
        }
        if (!selected) return IosBridgeProtocol.NOT_READY
        if (IosBridgeProtocol.isReadHello(commandApdu)) {
            val hello = IosBridgeHost.helloForReader() ?: return IosBridgeProtocol.NOT_READY
            helloRead = true
            return hello + IosBridgeProtocol.OK
        }
        val body = IosBridgeProtocol.authBody(commandApdu)
            ?: return IosBridgeProtocol.BAD_INS
        if (!helloRead) return IosBridgeProtocol.NOT_READY
        return try {
            val response = IosBridgeHost.authenticateReader(body)
                ?: return IosBridgeProtocol.NOT_READY
            response + IosBridgeProtocol.OK
        } catch (_: Exception) {
            IosBridgeProtocol.BAD_DATA
        }
    }

    override fun onDeactivated(reason: Int) {
        selected = false
        helloRead = false
        // Do not cancel the Wi-Fi session: the phones separate after NFC tap.
    }
}
