package tw.airgesture.transferbeta.core

import android.app.NotificationManager
import android.content.Context
import androidx.work.WorkManager
import java.io.File

/**
 * One-time migration for users who opted in to the retired auto-updater.
 * Only cancels old update jobs/notifications and removes cached APKs.
 * No network access, update checks, or downloads are performed.
 *
 * WorkManager is deliberately retained for one migration version so its
 * previously persisted periodic/one-shot jobs can be cancelled safely.
 */
object LegacyUpdateCleanup {
    private const val PREF = "release_update_settings"
    private const val MIGRATED = "legacy_updater_removed_v1"

    fun run(context: Context) {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (prefs.getBoolean(MIGRATED, false)) return

        // Disable persisted opt-in before doing anything that might be delayed.
        prefs.edit()
            .putBoolean("enabled", false)
            .remove("ready_version")
            .remove("ready_sha256")
            .putBoolean(MIGRATED, true)
            .apply()

        // Pending WorkManager tasks may survive APK upgrades in its database.
        WorkManager.getInstance(context)
            .cancelUniqueWork("airgesture-daily-update-check")
        WorkManager.getInstance(context)
            .cancelUniqueWork("airgesture-update-check")

        File(File(context.cacheDir, "updates"), "update.apk").delete()
        File(File(context.cacheDir, "updates"), "download.part").delete()
        val notifications = context.getSystemService(NotificationManager::class.java)
        notifications?.cancel(7603)
        notifications?.deleteNotificationChannel("airgesture_updates")
    }
}
