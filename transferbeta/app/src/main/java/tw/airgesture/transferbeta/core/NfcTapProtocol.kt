package tw.airgesture.transferbeta.core

/**
 * NFC physical-contact PROBE ONLY. This APDU does not identify or authenticate
 * a trusted Nearby device and does not carry any file bytes or image URI.
 *
 * A third-party app cannot enable reader mode while Samsung Gallery is the
 * foreground activity. Never interpret a probe response as authorization to
 * select the currently displayed Gallery image or auto-accept a file.
 */
object NfcTapProtocol {
    // Proprietary-category ISO7816 AID, not a payment AID.
    const val AID_HEX = "F041495247524142"
    const val VERSION = 1
    private val selectHeader = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00)
    private val appAid = hexBytes(AID_HEX)
    private val marker = "AIRGRAB:NFC-PROBE:1".toByteArray(Charsets.US_ASCII)
    private val ok = byteArrayOf(0x90.toByte(), 0x00)
    private val badInstruction = byteArrayOf(0x6D, 0x00)
    private val notFound = byteArrayOf(0x6A, 0x82.toByte())

    fun selectApdu(): ByteArray = selectHeader + byteArrayOf(appAid.size.toByte()) + appAid + byteArrayOf(0)

    fun respond(apdu: ByteArray?): ByteArray {
        if (apdu == null || apdu.size < 5 ||
            !apdu.copyOfRange(0, 4).contentEquals(selectHeader)) return badInstruction
        val length = apdu[4].toInt() and 0xFF
        if (length != appAid.size || apdu.size < 5 + length ||
            !apdu.copyOfRange(5, 5 + length).contentEquals(appAid)) return notFound
        return marker + ok
    }

    // The existing read-only diagnostic SELECT remains backwards compatible.
    // GET_SESSION is a separate authenticated-Nearby binding stage; it must
    // never be treated as permission to read another app's current media.
    private val sessionCommand = byteArrayOf(0x80.toByte(), 0x10, 0, 0, 0)
    fun getSessionCommand(): ByteArray = sessionCommand.copyOf()
    fun isSessionCommand(apdu: ByteArray?): Boolean =
        apdu != null && apdu.contentEquals(sessionCommand)
    fun sessionResponse(peerId: String, nonce: String): ByteArray =
        NfcTapSessionCodec.encode(peerId, nonce) + ok
    fun decodeSessionResponse(response: ByteArray?): NfcTapSessionCodec.Session? {
        if (response == null || response.size < 3 ||
            !response.copyOfRange(response.size - 2, response.size).contentEquals(ok)) return null
        return NfcTapSessionCodec.decode(response.copyOf(response.size - 2))
    }
    // A separate command for *passive browser sending*. This is not the
    // user-invoked NFC Sharesheet mode (GET_SESSION).
    private val browserSessionCommand = byteArrayOf(0x80.toByte(), 0x11, 0, 0, 0)
    fun getBrowserSessionCommand(): ByteArray = browserSessionCommand.copyOf()
    fun isBrowserSessionCommand(apdu: ByteArray?): Boolean =
        apdu != null && apdu.contentEquals(browserSessionCommand)

    fun notReadyResponse(): ByteArray = byteArrayOf(0x69, 0x85.toByte())

    fun isSuccessfulProbe(response: ByteArray?): Boolean =
        response != null && response.contentEquals(marker + ok)

    private fun hexBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0)
        return ByteArray(hex.length / 2) {
            hex.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
    }
}
