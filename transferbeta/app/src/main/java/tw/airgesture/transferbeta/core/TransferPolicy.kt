package tw.airgesture.transferbeta.core

/** Pure transport envelope validation. JSON parsing and Nearby are adapters. */
object TransferPolicy {
    // FILE payloads stream from disk. Allow large original videos without
    // changing codecs or the authenticated multi-device Nearby protocol.
    // No 2-GiB application cap. Use a high overflow-safe technical ceiling;
    // real acceptance is limited by file availability and free disk capacity.
    const val MAX_BYTES = Long.MAX_VALUE / 4L
    const val MIN_FREE_HEADROOM = 64L * 1024L * 1024L

    // Nearby stores an incoming FILE in temporary storage before the verified
    // copy is made to MediaStore. Budget both copies to avoid disk exhaustion.
    fun hasRoom(availableBytes: Long, fileSize: Long): Boolean =
        availableBytes >= MIN_FREE_HEADROOM && fileSize > 0L &&
            fileSize <= MAX_BYTES &&
            fileSize <= (availableBytes - MIN_FREE_HEADROOM) / 2L

    fun capacityLabel(): String = "可用儲存空間／裝置限制"
    const val OFFER_TIMEOUT_MS = 30_000L
    fun safeName(raw: String?): String? {
        val str = raw?.substringAfterLast('/')?.substringAfterLast('\\')
            ?.replace(Regex("[\\x00-\\x1f]"), "_")?.trim()?.take(120)
        return str?.takeIf { it.isNotEmpty() && it != "." && it != ".." }
    }
    fun validFileOffer(session: String, rawName: String?, count: Long, sha: String): Boolean =
        session.length in 12..100 && safeName(rawName) != null &&
            count in 0..MAX_BYTES && Regex("[0-9a-f]{64}").matches(sha)

    fun validLinkOffer(session: String, url: String): Boolean =
        session.length in 12..100 && GrabContentPolicy.safeWebUrl(url) == url

    fun matchingHash(expected: String, actual: String, expectedSize: Long, actualSize: Long): Boolean =
        expected == actual && expectedSize == actualSize && expected.length == 64
}
