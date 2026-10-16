package tw.airgesture.transferbeta.core

/** Tracks a single 30-second offer shared by a sender among independently verified peers.
 * No peer's failure cancels another peer; large file sends are serialized. */
class MultiReceiverBatch(
    val session: String,
    private val offeredAtMs: Long,
    private val acceptWindowMs: Long = TransferPolicy.OFFER_TIMEOUT_MS
) {
    enum class State { OFFERED, SEEN, QUEUED, SENDING, VERIFYING, COMPLETE, FAILED }
    private val receivers = linkedMapOf<String, State>()
    private var sendingTo: String? = null

    fun canAccept(nowMs: Long) = nowMs >= offeredAtMs &&
        nowMs - offeredAtMs < acceptWindowMs
    fun announce(id: String, nowMs: Long): Boolean {
        if (!canAccept(nowMs) || id.isBlank() || id in receivers) return false
        receivers[id] = State.OFFERED
        return true
    }
    fun state(id: String): State? = receivers[id]
    fun members(): Map<String, State> = receivers.toMap()
    fun seen(id: String) {
        if (receivers[id] == State.OFFERED) receivers[id] = State.SEEN
    }
    fun accept(id: String, nowMs: Long): Boolean {
        if (!canAccept(nowMs)) return false
        if (receivers[id] !in setOf(State.OFFERED, State.SEEN)) return false
        receivers[id] = State.QUEUED
        return true
    }
    fun claimNext(): String? {
        if (sendingTo != null) return null
        val id = receivers.entries.firstOrNull { it.value == State.QUEUED }?.key
            ?: return null
        receivers[id] = State.SENDING
        sendingTo = id
        return id
    }
    fun transferring(): String? = sendingTo
    fun bytesFinished(id: String): Boolean {
        if (sendingTo != id || receivers[id] != State.SENDING) return false
        receivers[id] = State.VERIFYING
        return true
    }
    fun complete(id: String): Boolean {
        if (sendingTo != id || receivers[id] !in setOf(State.VERIFYING, State.SENDING))
            return false
        receivers[id] = State.COMPLETE
        sendingTo = null
        return true
    }
    fun fail(id: String): Boolean {
        val previous = receivers[id] ?: return false
        if (previous == State.COMPLETE || previous == State.FAILED) return false
        receivers[id] = State.FAILED
        if (sendingTo == id) sendingTo = null
        return true
    }
    fun endUnclaimed(nowMs: Long) {
        if (canAccept(nowMs)) return
        for ((id, state) in receivers.toMap())
            if (state == State.OFFERED || state == State.SEEN) receivers[id] = State.FAILED
    }
    fun isFinished(nowMs: Long): Boolean {
        if (canAccept(nowMs) || sendingTo != null) return false
        return receivers.values.none { it == State.QUEUED }
    }
    fun completedCount() = receivers.values.count { it == State.COMPLETE }
    fun failedCount() = receivers.values.count { it == State.FAILED }
}
