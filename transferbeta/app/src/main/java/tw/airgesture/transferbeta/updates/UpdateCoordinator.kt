package tw.airgesture.transferbeta.updates

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import tw.airgesture.transferbeta.R
import tw.airgesture.transferbeta.core.ReleaseUpdatePolicy
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * No update mechanism is active without explicit user opt-in.
 * Phone-side Firebase config is a public identifier, NEVER a server secret.
 * Actual APKs must come from official GitHub Releases with matching digest
 * and installed-app signer, not from an FCM-supplied URL.
 */
object UpdateCoordinator {
    private const val PREF = "release_update_settings"
    private const val ENABLED = "enabled"
    private const val CHANNEL = "channel"
    private const val VERSION = "ready_version"
    private const val DIGEST = "ready_sha256"
    private const val CHECK_WORK = "airgesture-update-check"
    private const val PERIODIC_WORK = "airgesture-daily-update-check"
    private const val NOTICE_CHANNEL = "airgesture_updates"
    private const val NOTICE_ID = 7603
    private const val HISTORY = "update_history"

    /** Local, bounded record of user-opted-in update checks and downloads. */
    @Synchronized
    fun record(context: Context, step: String) {
        val pref = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val time = java.text.SimpleDateFormat("MM/dd HH:mm",
            java.util.Locale.TAIWAN).format(java.util.Date())
        val entry = "$time · " + step.take(95).replace('\n', ' ')
        val previous = pref.getString(HISTORY, "")?.lines()
            ?.filter { it.isNotBlank() } ?: emptyList()
        pref.edit().putString(HISTORY,
            (listOf(entry) + previous.filterNot { it.substringAfter(" · ") == step })
                .take(12).joinToString("\n")).apply()
    }

    fun history(context: Context): String = context
        .getSharedPreferences(PREF, Context.MODE_PRIVATE)
        .getString(HISTORY, null)?.takeIf { it.isNotBlank() }
        ?: "尚無更新檢查紀錄（自動更新預設關閉）"


    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(ENABLED, false)

    fun channel(context: Context): ReleaseUpdatePolicy.Channel =
        if (context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getString(CHANNEL, "test") == "stable")
            ReleaseUpdatePolicy.Channel.STABLE else ReleaseUpdatePolicy.Channel.TEST

    fun readyVersion(context: Context): String? =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(VERSION, null)

    fun readyFile(context: Context): File = File(File(context.cacheDir, "updates"), "update.apk")

    fun clearReady(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .remove(VERSION).remove(DIGEST).apply()
        readyFile(context).delete()
    }

    fun readyDigest(context: Context): String? =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(DIGEST, null)

    fun markReady(context: Context, version: String, digest: String) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(VERSION, version).putString(DIGEST, digest).apply()
        record(context, "v$version 已下載、簽章與 SHA-256 驗證通過；待使用者確認安裝")
    }

    private fun firebaseAvailable(context: Context): Boolean =
        FirebaseApp.getApps(context).isNotEmpty()

    fun configure(
        context: Context,
        enabled: Boolean,
        newChannel: ReleaseUpdatePolicy.Channel
    ) {
        val oldEnabled = enabled(context)
        val oldChannel = channel(context)
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean(ENABLED, enabled)
            .putString(CHANNEL,
                if (newChannel == ReleaseUpdatePolicy.Channel.STABLE) "stable" else "test")
            .apply()
        if (!enabled || oldChannel != newChannel) clearReady(context)
        if (firebaseAvailable(context)) {
            val messaging = FirebaseMessaging.getInstance()
            if (oldEnabled && (!enabled || oldChannel != newChannel))
                messaging.unsubscribeFromTopic(oldChannel.topic)
            messaging.isAutoInitEnabled = enabled
            if (enabled) messaging.subscribeToTopic(newChannel.topic)
        }
        record(context, if (enabled)
            "已啟用更新追蹤：" + if (newChannel == ReleaseUpdatePolicy.Channel.STABLE)
                "正式機" else "測試機"
            else "使用者已停用更新追蹤")
        if (enabled) {
            schedulePeriodic(context)
            checkNow(context)
        } else {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK)
            WorkManager.getInstance(context).cancelUniqueWork(CHECK_WORK)
        }
    }

    /** Called from existing app startup, not on every camera frame. */
    fun initialize(context: Context) {
        if (!enabled(context)) return
        schedulePeriodic(context)
        if (firebaseAvailable(context)) {
            FirebaseMessaging.getInstance().apply {
                isAutoInitEnabled = true
                subscribeToTopic(channel(context).topic)
            }
        }
    }

    fun schedulePeriodic(context: Context) {
        if (!enabled(context)) return
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED).build()
        val work = PeriodicWorkRequestBuilder<UpdateCheckWorker>(
            ReleaseUpdatePolicy.CHECK_INTERVAL_HOURS, TimeUnit.HOURS
        ).setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, work)
    }

    fun checkNow(context: Context) {
        if (!enabled(context)) return
        record(context, "已排程檢查更新；等待符合 Wi-Fi／網路條件")
        val work = OneTimeWorkRequestBuilder<UpdateCheckWorker>()
            .setConstraints(Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED).build()).build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            CHECK_WORK, ExistingWorkPolicy.KEEP, work)
    }

    fun notifyReady(context: Context, version: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            NOTICE_CHANNEL, "懸浮操作版本更新", NotificationManager.IMPORTANCE_DEFAULT))
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) return
        val intent = Intent(context, UpdateInstallActivity::class.java)
        val pending = PendingIntent.getActivity(context, 94, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(NOTICE_ID, NotificationCompat.Builder(context, NOTICE_CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("懸浮操作可更新：$version")
            .setContentText("APK 已安全下載並驗證，點擊確認安裝")
            .setContentIntent(pending).setAutoCancel(true).build())
    }
}
