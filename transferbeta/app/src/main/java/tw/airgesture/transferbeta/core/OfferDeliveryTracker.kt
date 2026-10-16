package tw.airgesture.transferbeta.core

/**
 * Tracks delivery of a short Nearby offer, separately from file transfer.
 * Hand gesture / local preparation is never proof that the peer received it.
 */
class OfferDeliveryTracker(
    val session: String,
    private val startedAt: Long,
    private val expiryMs: Long = 30_000L
) {
    private var peerAcknowledged = false
    var attempts: Int = 0
        private set

    fun recordAttempt(nowMs: Long): Boolean {
        if (!shouldRetry(nowMs)) return false
        attempts++
        return true
    }

    fun acknowledge(receivedSession: String): Boolean {
        if (receivedSession != session || attempts == 0) return false
        peerAcknowledged = true
        return true
    }

    fun wasAcknowledged(): Boolean = peerAcknowledged
    fun shouldRetry(nowMs: Long): Boolean =
        !peerAcknowledged && nowMs >= startedAt && nowMs - startedAt < expiryMs
    fun hasExpired(nowMs: Long): Boolean = nowMs >= startedAt && nowMs - startedAt >= expiryMs
}
