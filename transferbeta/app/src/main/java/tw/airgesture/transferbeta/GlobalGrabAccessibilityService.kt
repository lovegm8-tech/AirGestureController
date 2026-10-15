package tw.airgesture.transferbeta

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import tw.airgesture.transferbeta.core.GrabChoice
import tw.airgesture.transferbeta.core.GrabContentPolicy
import tw.airgesture.transferbeta.core.VisibleText
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.util.ArrayDeque
import java.util.concurrent.Executors

/**
 * Reads the active window ONLY after an explicit user grab gesture.
 * Can screenshot visible pixels with the Accessibility permission on Android 11+;
 * screenshot bytes must never be labelled "original file".
 */
class GlobalGrabAccessibilityService : AccessibilityService() {
    private val io = Executors.newSingleThreadExecutor()
    override fun onServiceConnected() {
        super.onServiceConnected()
        GlobalGrabBridge.accessibility = WeakReference(this)
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No continuous traversal or OCR: screen content is examined only on gesture.
    }
    override fun onInterrupt() {}

    fun grabVisibleContent() {
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString()
        if (pkg == packageName) {
            GlobalGrabBridge.result(GrabResult.Unavailable("請切回正在觀看照片或影片的 App"))
            return
        }
        val nodes = mutableListOf<VisibleText>()
        if (root != null) {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            while (queue.isNotEmpty() && nodes.size < 180) {
                val node = queue.removeFirst()
                nodes.add(VisibleText(node.viewIdResourceName, node.text?.toString(), node.isPassword))
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.addLast(it) }
                }
            }
        }
        when (val result = GrabContentPolicy.decide(pkg, nodes)) {
            is GrabChoice.Link -> GlobalGrabBridge.result(GrabResult.Link(result.url))
            is GrabChoice.ScreenSnapshot -> captureScreenPixels()
            is GrabChoice.Unavailable -> GlobalGrabBridge.result(
                GrabResult.Unavailable(result.reason))
        }
    }

    private fun captureScreenPixels() {
        if (Build.VERSION.SDK_INT < 30) {
            GlobalGrabBridge.result(GrabResult.Unavailable("此 Android 版本不支援無障礙畫面擷取"))
            return
        }
        takeScreenshot(Display.DEFAULT_DISPLAY, ContextCompat.getMainExecutor(this),
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val hardware = screenshot.hardwareBuffer
                    try {
                        val wrapped = Bitmap.wrapHardwareBuffer(hardware, screenshot.colorSpace)
                            ?: error("未取得畫面影像")
                        val pixels = wrapped.copy(Bitmap.Config.ARGB_8888, false)
                            ?: error("無法複製畫面")
                        io.execute {
                            val target = File(cacheDir, "global-grabs").apply { mkdirs() }
                            val output = File.createTempFile("screen-", ".png", target)
                            try {
                                FileOutputStream(output).use {
                                    check(pixels.compress(Bitmap.CompressFormat.PNG, 100, it))
                                }
                                GlobalGrabBridge.result(GrabResult.ScreenSnapshot(output))
                            } catch (e: Exception) {
                                output.delete()
                                GlobalGrabBridge.result(GrabResult.Unavailable(
                                    "畫面存檔失敗：" + e.message))
                            } finally {
                                pixels.recycle()
                            }
                        }
                    } catch (e: Exception) {
                        GlobalGrabBridge.result(GrabResult.Unavailable(
                            "無法擷取畫面（可能是受保護內容）：" + e.message))
                    } finally {
                        hardware.close()
                    }
                }
                override fun onFailure(errorCode: Int) {
                    GlobalGrabBridge.result(GrabResult.Unavailable(
                        "畫面截取失敗，錯誤代碼：" + errorCode))
                }
            })
    }
    override fun onDestroy() {
        GlobalGrabBridge.accessibility = null
        io.shutdown()
        super.onDestroy()
    }
}
