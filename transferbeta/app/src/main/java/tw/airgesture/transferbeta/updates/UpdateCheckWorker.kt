package tw.airgesture.transferbeta.updates

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import tw.airgesture.transferbeta.core.ReleaseUpdatePolicy
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

/** Unmetered-only background polling. No downloader from untrusted FCM URLs. */
class UpdateCheckWorker(appContext: Context, parameters: WorkerParameters) :
    Worker(appContext, parameters) {
    data class Candidate(
        val tag: String,
        val url: String,
        val digest: String,
        val size: Long
    )

    override fun doWork(): Result {
        if (!UpdateCoordinator.enabled(applicationContext)) return Result.success()
        val connectivity = applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivity.activeNetwork ?: return Result.retry()
        val caps = connectivity.getNetworkCapabilities(network) ?: return Result.retry()
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
            return Result.retry()
        return try {
            val channel = UpdateCoordinator.channel(applicationContext)
            val installed = applicationContext.packageManager
                .getPackageInfo(applicationContext.packageName, 0).versionName ?: return Result.failure()
            val candidate = chooseRelease(fetchReleases(), channel, installed)
                ?: return Result.success()
            val file = UpdateCoordinator.readyFile(applicationContext)
            if (UpdateCoordinator.readyVersion(applicationContext) == candidate.tag &&
                UpdateCoordinator.readyDigest(applicationContext) == candidate.digest &&
                verifyApk(applicationContext, file, candidate.tag, candidate.digest)) {
                UpdateCoordinator.notifyReady(applicationContext, candidate.tag)
                return Result.success()
            }
            UpdateCoordinator.clearReady(applicationContext)
            val parent = file.parentFile ?: return Result.failure()
            if (!parent.isDirectory && !parent.mkdirs()) return Result.failure()
            val tmp = File(parent, "download.part")
            try {
                download(candidate, tmp)
                if (!verifyApk(applicationContext, tmp, candidate.tag, candidate.digest))
                    return Result.failure()
                if (!tmp.renameTo(file)) return Result.retry()
                UpdateCoordinator.markReady(applicationContext, candidate.tag, candidate.digest)
                UpdateCoordinator.notifyReady(applicationContext, candidate.tag)
                Result.success()
            } finally { tmp.delete() }
        } catch (_: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private fun fetchReleases(): JSONArray {
        val c = openTrusted(ReleaseUpdatePolicy.RELEASES_URL)
        return try {
            val payload = c.inputStream.bufferedReader().use { it.readText() }
            require(payload.length <= 500_000) { "Release metadata too large" }
            JSONArray(payload)
        } finally { c.disconnect() }
    }

    private fun chooseRelease(
        releases: JSONArray, channel: ReleaseUpdatePolicy.Channel, localVersion: String
    ): Candidate? {
        for (i in 0 until releases.length().coerceAtMost(15)) {
            val release = releases.optJSONObject(i) ?: continue
            if (release.optBoolean("draft", false)) continue
            val tag = release.optString("tag_name")
            if (!ReleaseUpdatePolicy.correctChannel(
                tag, release.optBoolean("prerelease", false), channel) ||
                !ReleaseUpdatePolicy.isNewer(tag, localVersion)) continue
            val assets = release.optJSONArray("assets") ?: continue
            for (j in 0 until assets.length()) {
                val asset = assets.optJSONObject(j) ?: continue
                val name = asset.optString("name")
                val url = asset.optString("browser_download_url")
                val digest = asset.optString("digest")
                val size = asset.optLong("size")
                if (ReleaseUpdatePolicy.acceptableAsset(name, url, digest, size))
                    return Candidate(tag, url, digest, size)
            }
        }
        return null
    }

    private fun openTrusted(url: String): HttpURLConnection {
        var next = url
        for (redirect in 0..5) {
            val u = URL(next)
            require(u.protocol == "https" && ReleaseUpdatePolicy.trustedDownloadHost(u.host))
            val connection = u.openConnection() as HttpURLConnection
            connection.connectTimeout = 12_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "AirGesture-Updater")
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            val status = connection.responseCode
            if (status in 300..399) {
                val redirectUrl = connection.getHeaderField("Location")
                connection.disconnect()
                require(!redirectUrl.isNullOrBlank())
                next = URL(u, redirectUrl).toString()
                continue
            }
            require(status == 200) { "GitHub returned $status" }
            return connection
        }
        throw IllegalArgumentException("Too many redirects")
    }

    private fun download(candidate: Candidate, tmp: File) {
        val connection = openTrusted(candidate.url)
        try {
            require(connection.contentLengthLong == -1L ||
                connection.contentLengthLong == candidate.size)
            var total = 0L
            val md = MessageDigest.getInstance("SHA-256")
            connection.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val bytes = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(bytes)
                        if (count < 0) break
                        total += count
                        require(total <= candidate.size &&
                            total <= ReleaseUpdatePolicy.MAX_APK_BYTES)
                        md.update(bytes, 0, count)
                        output.write(bytes, 0, count)
                    }
                }
            }
            require(total == candidate.size && total > 0)
            val sha = md.digest().joinToString("") { "%02x".format(it) }
            require("sha256:$sha".equals(candidate.digest, ignoreCase = true))
        } finally { connection.disconnect() }
    }

    companion object {
        /** Re-verify the downloaded APK immediately before showing installer UI. */
        fun verifyApk(context: Context, file: File, tag: String, digest: String): Boolean {
            if (!file.isFile || file.length() < 1_000_000L ||
                file.length() > ReleaseUpdatePolicy.MAX_APK_BYTES) return false
            return try {
                val md = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val bytes = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(bytes)
                        if (n < 0) break
                        md.update(bytes, 0, n)
                    }
                }
                val hash = md.digest().joinToString("") { "%02x".format(it) }
                if (!"sha256:$hash".equals(digest, ignoreCase = true)) return false
                val pm = context.packageManager
                val flags = PackageManager.GET_SIGNING_CERTIFICATES
                @Suppress("DEPRECATION")
                val archived = if (Build.VERSION.SDK_INT >= 33) {
                    pm.getPackageArchiveInfo(file.absolutePath,
                        PackageManager.PackageInfoFlags.of(flags.toLong()))
                } else pm.getPackageArchiveInfo(file.absolutePath, flags)
                @Suppress("DEPRECATION")
                val installed = if (Build.VERSION.SDK_INT >= 33) {
                    pm.getPackageInfo(context.packageName,
                        PackageManager.PackageInfoFlags.of(flags.toLong()))
                } else pm.getPackageInfo(context.packageName, flags)
                if (archived == null || archived.packageName != context.packageName ||
                    archived.versionName != tag.removePrefix("v") ||
                    archived.longVersionCode <= installed.longVersionCode) return false
                val oldSigners = installed.signingInfo?.apkContentsSigners
                    ?.map { it.toCharsString() }?.toSet().orEmpty()
                val newSigners = archived.signingInfo?.apkContentsSigners
                    ?.map { it.toCharsString() }?.toSet().orEmpty()
                oldSigners.isNotEmpty() && oldSigners == newSigners
            } catch (_: Exception) { false }
        }
    }
}
