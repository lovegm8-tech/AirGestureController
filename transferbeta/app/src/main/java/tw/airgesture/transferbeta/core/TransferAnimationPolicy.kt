package tw.airgesture.transferbeta.core

/**
 * Screen overlay events. These are NOT inferred from a status string.
 * A successful share-button tap is NOT a successfully obtained file or URL.
 */
enum class VisualPhase {
    CAPTURING,
    CONTENT_READY,
    WAITING_FOR_RECEIVER,
    OFFER_RECEIVED,
    RECEIVING,
    VERIFYING,
    SEND_COMPLETE,
    RECEIVE_COMPLETE,
    FAILURE
}
data class VisualEvent(val phase: VisualPhase, val label: String, val progress: Int? = null)

object TransferAnimationPolicy {
    fun isSuccess(event: VisualEvent): Boolean =
        event.phase in setOf(VisualPhase.SEND_COMPLETE, VisualPhase.RECEIVE_COMPLETE)

    fun isReceiver(event: VisualEvent): Boolean =
        event.phase in setOf(VisualPhase.OFFER_RECEIVED,
            VisualPhase.RECEIVING, VisualPhase.VERIFYING, VisualPhase.RECEIVE_COMPLETE)

    fun durationMs(event: VisualEvent): Long = when (event.phase) {
        VisualPhase.CAPTURING -> 1500L
        VisualPhase.CONTENT_READY -> 1400L
        VisualPhase.WAITING_FOR_RECEIVER -> 30_000L
        VisualPhase.OFFER_RECEIVED -> 30_000L
        VisualPhase.RECEIVING, VisualPhase.VERIFYING -> 4500L
        VisualPhase.SEND_COMPLETE, VisualPhase.RECEIVE_COMPLETE -> 1900L
        VisualPhase.FAILURE -> 1900L
    }
    fun hasReceiveCountdown(phase: VisualPhase): Boolean =
        phase == VisualPhase.WAITING_FOR_RECEIVER || phase == VisualPhase.OFFER_RECEIVED

    fun symbol(event: VisualEvent): String = when (event.phase) {
        VisualPhase.CAPTURING -> "◎"
        VisualPhase.CONTENT_READY -> "↗"
        VisualPhase.WAITING_FOR_RECEIVER -> "↗"
        VisualPhase.OFFER_RECEIVED -> "↙"
        VisualPhase.RECEIVING -> "↓"
        VisualPhase.VERIFYING -> "◉"
        VisualPhase.SEND_COMPLETE, VisualPhase.RECEIVE_COMPLETE -> "✓"
        VisualPhase.FAILURE -> "!"
    }
    fun displayProgress(event: VisualEvent): Int? =
        event.progress?.coerceIn(0, 100)?.takeIf {
            event.phase == VisualPhase.RECEIVING
        }
}
