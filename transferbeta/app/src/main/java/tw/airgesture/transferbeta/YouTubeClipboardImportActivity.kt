package tw.airgesture.transferbeta

import android.app.Activity
import android.content.ClipboardManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import tw.airgesture.transferbeta.core.YouTubeLinkPolicy

/**
 * Short-lived foreground clipboard reader for ONE user-initiated YouTube grab.
 * Android 10+ prevents background clipboard monitoring. We only inspect the
 * current clip once after this Activity actually acquires focus; no listener.
 */
class YouTubeClipboardImportActivity : Activity() {
    companion object {
        const val EXTRA_CLICKED_AT = "youtube_copy_clicked_at"
    }
    private val main = Handler(Looper.getMainLooper())
    private var attempted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null ||
            intent.getLongExtra(EXTRA_CLICKED_AT, 0L) <= 0L) {
            finishQuietly()
            return
        }
        main.postDelayed({
            if (!attempted) {
                attempted = true
                fail("AirGrab 無法取得前景焦點，不能讀取 YouTube 複製的網址")
            }
        }, 2600L)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || attempted || isFinishing) return
        attempted = true
        // Let YouTube finish populating the clipboard before the single read.
        main.postDelayed({ importOnce() }, 220L)
    }

    private fun importOnce() {
        if (isFinishing) return
        val transfer = GlobalGestureService.transfer
        if (!GlobalGestureService.isActive || transfer == null || !transfer.isConnected) {
            fail("AirGrab 未連線，已停止 YouTube 網址抓取")
            return
        }
        try {
            val clipboard = getSystemService(ClipboardManager::class.java)
            val clip = clipboard.primaryClip
            val text = if (clip?.itemCount == 1) {
                clip.getItemAt(0).text?.toString()
            } else null
            val timestamp = clipboard.primaryClipDescription?.timestamp ?: 0L
            val url = YouTubeLinkPolicy.freshVideoLink(
                text,
                intent.getLongExtra(EXTRA_CLICKED_AT, 0L),
                timestamp,
                System.currentTimeMillis()
            )
            if (url != null) {
                GlobalGrabBridge.result(GrabResult.Link(url))
                finishQuietly()
            } else {
                fail("未取得這次複製的 YouTube 影片網址；已停止，避免誤傳舊連結")
            }
        } catch (_: SecurityException) {
            fail("Android 限制存取剪貼簿，請改用系統分享選擇 AirGrab")
        } catch (_: Exception) {
            fail("YouTube 網址讀取失敗；沒有傳送剪貼簿內容")
        }
    }

    private fun fail(reason: String) {
        GlobalGrabBridge.result(GrabResult.Unavailable(reason))
        finishQuietly()
    }

    private fun finishQuietly() {
        if (isFinishing) return
        if (isTaskRoot) finishAndRemoveTask() else finish()
        overridePendingTransition(0, 0)
    }
}
