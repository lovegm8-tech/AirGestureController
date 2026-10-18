package tw.airgesture.transferbeta.core

/**
 * Passive Nearby FILE payload throughput meter.
 * Uses existing progress callbacks: no extra radio, CPU polling or camera work.
 */
class TransferSpeedTracker {
    data class Snapshot(val mebibytesPerSecond: Double, val remainingSeconds: Long?)
    private var firstAt = -1L
    private var firstBytes = 0L
    private var latestAt = -1L
    private var latestBytes = 0L

    fun reset() {
        firstAt = -1L
        firstBytes = 0L
        latestAt = -1L
        latestBytes = 0L
    }

    fun observe(bytes: Long, total: Long, nowMs: Long): Snapshot? {
        if (total <= 0L || bytes < 0L || bytes > total || nowMs < 0L) return null
        if (firstAt < 0L || nowMs < latestAt || bytes < latestBytes) {
            firstAt = nowMs
            firstBytes = bytes
            latestAt = nowMs
            latestBytes = bytes
            return null
        }
        val elapsed = nowMs - firstAt
        latestAt = nowMs
        latestBytes = bytes
        if (elapsed < 1000L || bytes <= firstBytes) return null
        val perSecond = (bytes - firstBytes).toDouble() * 1000.0 / elapsed
        if (perSecond <= 0.0) return null
        return Snapshot(
            perSecond / (1024.0 * 1024.0),
            ((total - bytes) / perSecond).toLong().coerceAtLeast(0L)
        )
    }

    companion object {
        fun summary(snapshot: Snapshot?): String {
            if (snapshot == null) return ""
            val speed = "%.1f".format(java.util.Locale.US, snapshot.mebibytesPerSecond)
            val remaining = snapshot.remainingSeconds
            return if (remaining == null) " · $speed MiB/s"
                else " · $speed MiB/s · 約 ${if (remaining < 60L) "${remaining}秒"
                    else "${(remaining + 59L) / 60L}分鐘"}"
        }
    }
}
