package tw.airgesture.transferbeta.core

/** NFC must NOT auto-accept unless the Nearby endpoint is a verified peer
 * and the single-use NFC session belongs to the same authenticated endpoint.
 * The caller consumes the pending session immediately when this returns true.
 */
object NfcAutoAcceptPolicy {
    fun permits(
        approvedEndpoint: String?, approvedSession: String?, expiresAtMs: Long,
        actualEndpoint: String, offeredSession: String?, nowMs: Long,
        verified: Boolean
    ): Boolean = verified && approvedEndpoint != null &&
        approvedSession != null && offeredSession != null &&
        approvedEndpoint == actualEndpoint && approvedSession == offeredSession &&
        offeredSession.isNotBlank() && nowMs <= expiresAtMs
}
