package tw.airgesture.transferbeta.core

/**
 * NFC contact only selects a candidate peer. Confirmation must be made on the
 * ALREADY authenticated Nearby endpoint before any original file is offered.
 * No image bytes or access URI are ever sent via an NFC APDU.
 */
object NfcTapSessionCodec {
    private const val PREFIX = "AIRGRAB2"
    private val idPattern = Regex("[A-Za-z0-9-]{12}")
    private val noncePattern = Regex("[0-9a-f]{32}")
    data class Session(val peerId: String, val nonce: String)

    fun encode(peerId: String, nonce: String): ByteArray {
        require(idPattern.matches(peerId) && noncePattern.matches(nonce))
        return "$PREFIX|$peerId|$nonce".toByteArray(Charsets.US_ASCII)
    }

    fun decode(bytes: ByteArray?): Session? {
        if (bytes == null || bytes.size !in 54..64) return null
        val text = bytes.toString(Charsets.US_ASCII)
        val parts = text.split('|')
        if (parts.size != 3 || parts[0] != PREFIX ||
            !idPattern.matches(parts[1]) || !noncePattern.matches(parts[2])) return null
        return Session(parts[1], parts[2])
    }
}
