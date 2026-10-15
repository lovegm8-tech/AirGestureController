package tw.airgesture.transferbeta

import java.io.File
import java.lang.ref.WeakReference
import android.os.Handler
import android.os.Looper

/** In-process prototype bridge. App process and its original Nearby connection must stay alive. */
sealed class GrabResult {
    data class Link(val url: String) : GrabResult()
    data class ShareStarted(val detail: String) : GrabResult()
    data class ScreenSnapshot(val file: File) : GrabResult() // never an original
    data class Unavailable(val reason: String) : GrabResult()
}

object GlobalGrabBridge {
    @Volatile var accessibility: WeakReference<GlobalGrabAccessibilityService>? = null
    @Volatile var onGrabResult: ((GrabResult) -> Unit)? = null
    @Volatile var onReceiveGesture: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    fun onGrabGesture() {
        main.post {
            val service = accessibility?.get()
            if (service == null) {
                onGrabResult?.invoke(GrabResult.Unavailable(
                    "請先在 Android 無障礙設定啟用「隔空抓取」"))
            } else service.grabVisibleContent()
        }
    }

    fun onReleaseGesture() { onReceiveGesture?.invoke() }
    fun result(result: GrabResult) { onGrabResult?.invoke(result) }
}
