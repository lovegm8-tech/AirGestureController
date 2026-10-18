package tw.airgesture.transferbeta.updates

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.core.content.FileProvider

/** Requires the phone user's confirmation; never uses hidden install APIs. */
class UpdateInstallActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val file = UpdateCoordinator.readyFile(this)
        val digest = UpdateCoordinator.readyDigest(this)
        val version = UpdateCoordinator.readyVersion(this)
        if (!UpdateCoordinator.enabled(this) ||
            version.isNullOrBlank() || digest.isNullOrBlank() || !file.exists() ||
            !UpdateCheckWorker.verifyApk(this, file, version, digest)) {
            UpdateCoordinator.clearReady(this)
            AlertDialog.Builder(this).setMessage("更新檔案已失效，請重新檢查更新。")
                .setPositiveButton("確認") { _, _ -> finish() }.show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("安裝懸浮操作 $version")
            .setMessage("新版已下載並驗證簽章。Android 可能要求允許從此來源安裝，以及確認更新。")
            .setPositiveButton("安裝") { _, _ ->
                if (!packageManager.canRequestPackageInstalls()) {
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName")))
                    finish()
                } else {
                    val uri = FileProvider.getUriForFile(this,
                        "$packageName.updates", file)
                    startActivity(Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                        data = uri
                        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                        putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                    })
                    finish()
                }
            }
            .setNegativeButton("稍後", { _, _ -> finish() })
            .show()
    }
}
