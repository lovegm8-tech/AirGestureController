package tw.airgesture.transferbeta

import java.io.File
import java.lang.ref.WeakReference

/** In-process prototype bridge. App process and its original Nearby connection must stay alive. */
sealed class GrabResult {
    data class Link(val url: String) : GrabResult()
    data class ScreenSnapshot(val file: File) : GrabResult() // never an original
    data class Unavailable(val reason: String) : GrabResult()
}

object GlobalGrabBridge {
    @Volatile var accessibility: WeakReference<GlobalGrabAccessibilityService>? = null
    @Volatile var onGrabResult: ((GrabResult) -> Unit)? = null
    @Volatile var onReceiveGesture: (() -> Unit)? = null

    fun onGrabGesture() {
        val service = accessibility?.get()
        if (service == null) {
            onGrabResult?.invoke(GrabResult.Unavailable(
                "請先到 Android 無障礙設定，啟用「隔空抓取」；原檔存取仍受到 Android 限制"))
            return
        }
        service.grabVisibleContent()
    }

    fun onReleaseGesture() { onReceiveGesture?.invoke() }
    fun result(result: GrabResult) { onGrabResult?.invoke(result) }
}
