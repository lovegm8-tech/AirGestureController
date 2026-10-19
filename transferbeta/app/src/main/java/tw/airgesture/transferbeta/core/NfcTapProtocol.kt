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

    fun isSuccessfulProbe(response: ByteArray?): Boolean =
        response != null && response.contentEquals(marker + ok)

    private fun hexBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0)
        return ByteArray(hex.length / 2) {
            hex.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
    }
}
