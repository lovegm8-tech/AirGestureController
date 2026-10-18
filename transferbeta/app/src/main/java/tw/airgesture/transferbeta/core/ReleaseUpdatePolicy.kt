package tw.airgesture.transferbeta.core

/** Deliberately accepts only signed, non-prerelease GitHub APK release assets. */
object ReleaseUpdatePolicy {
    const val REPOSITORY = "lovegm8-tech/AirGestureController"
    const val LATEST_URL = "https://api.github.com/repos/$REPOSITORY/releases/latest"
    const val ASSET = "app-release.apk"
    const val MAX_APK_BYTES = 180L * 1024L * 1024L
    const val CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L

    fun isNewer(releaseTag: String, installedVersion: String): Boolean {
        fun parse(value: String): List<Int>? {
            val m = Regex("^v?(\\d{1,4})\\.(\\d{1,4})\\.(\\d{1,4})$").matchEntire(value)
                ?: return null
            return m.groupValues.drop(1).map { it.toInt() }
        }
        val remote = parse(releaseTag) ?: return false
        val local = parse(installedVersion) ?: return false
        for (i in 0..2) {
            if (remote[i] > local[i]) return true
            if (remote[i] < local[i]) return false
        }
        return false
    }

    fun isAcceptableAsset(
        name: String, url: String, digest: String, bytes: Long
    ): Boolean =
        name == ASSET &&
            url.startsWith("https://github.com/$REPOSITORY/releases/download/") &&
            Regex("^sha256:[a-fA-F0-9]{64}$").matches(digest) &&
            bytes in 1_000_000L..MAX_APK_BYTES

    fun isDownloadHostTrusted(host: String): Boolean {
        val h = host.lowercase()
        return h == "github.com" || h == "release-assets.githubusercontent.com" ||
            h == "objects.githubusercontent.com" || h == "github-releases.githubusercontent.com"
    }
}
