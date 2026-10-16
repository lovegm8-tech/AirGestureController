package tw.airgesture.transferbeta

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import tw.airgesture.transferbeta.core.*
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.util.ArrayDeque
import java.util.concurrent.Executors

/**
 * Only inspects the active window after a deliberate grab gesture. It never
 * monitors text continuously. It performs at most a bounded known share flow,
 * does not silently change document access, and refuses ambiguous share buttons.
 */
class GlobalGrabAccessibilityService : AccessibilityService() {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var pending: RunningShare? = null
    private var nextAttempt: Runnable? = null
    private var flowTimeout: Runnable? = null
    private var browserCapture: BrowserCapture? = null
    private data class BrowserCapture(val origin: String, val expiry: Long)
    private data class RunningShare(
        val origin: String,
        val plan: SharePlan,
        var nextStep: Int,
        val expires: Long,
        var lastAction: Long
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        GlobalGrabBridge.accessibility = WeakReference(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Expensive node traversal only while a bounded, user-initiated share is pending.
        if (event == null || (pending == null && browserCapture == null)) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return
        schedulePendingCheck()
    }

    override fun onInterrupt() { clearPending() }

    private fun schedulePendingCheck() {
        if (nextAttempt != null) return
        val call = Runnable {
            nextAttempt = null
            if (browserCapture != null) advanceBrowserCapture() else advanceFlow()
        }
        nextAttempt = call
        main.postDelayed(call, 320)
    }

    private fun clearPending() {
        flowTimeout?.let { main.removeCallbacks(it) }
        flowTimeout = null
        nextAttempt?.let { main.removeCallbacks(it) }
        nextAttempt = null
        pending = null
        browserCapture = null
    }

    private fun collect(root: AccessibilityNodeInfo?): Pair<List<ActionNode>, List<AccessibilityNodeInfo>> {
        if (root == null) return Pair(emptyList(), emptyList())
        val seen = HashSet<Int>()
        val nodes = ArrayList<ActionNode>(160)
        val clickable = ArrayList<AccessibilityNodeInfo>(160)
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(Pair(root, 0))
        while (queue.isNotEmpty() && nodes.size < 180) {
            val (node, depth) = queue.removeFirst()
            val view = node.viewIdResourceName
            val description = node.contentDescription?.toString()
            val label = node.text?.toString()
            val target = when {
                node.isClickable -> node
                node.parent?.isClickable == true -> node.parent
                else -> null
            }
            if (target != null) {
                // Avoid matching both a label and its parent as separate buttons.
                val identity = System.identityHashCode(target)
                if (seen.add(identity)) {
                    nodes.add(ActionNode(
                        view ?: target.viewIdResourceName,
                        label ?: target.text?.toString(),
                        description ?: target.contentDescription?.toString(),
                        clickable = true,
                        password = node.isPassword || target.isPassword,
                        visible = node.isVisibleToUser && target.isVisibleToUser,
                        depth = depth
                    ))
                    clickable.add(target)
                }
            }
            if (depth < 18) for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(Pair(it, depth + 1)) }
            }
        }
        return Pair(nodes, clickable)
    }

    private fun readable(root: AccessibilityNodeInfo?): List<VisibleText> {
        if (root == null) return emptyList()
        val found = ArrayList<VisibleText>()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        while (queue.isNotEmpty() && found.size < 240) {
            val (node, depth) = queue.removeFirst()
            if (node.isVisibleToUser && !node.isPassword) {
                val text = node.text?.toString() ?: node.contentDescription?.toString()
                if (!text.isNullOrBlank()) {
                    found.add(VisibleText(node.viewIdResourceName, text))
                }
            }
            if (depth < 18) for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it to (depth + 1)) }
            }
        }
        return found
    }

    private fun completeBrowserCapture() {
        val capture = browserCapture ?: return
        browserCapture = null
        nextAttempt?.let { main.removeCallbacks(it) }
        nextAttempt = null
        // Only close an address editor that AirGrab itself opened. Never press
        // Back from an arbitrary browser screen or guess a destination URL.
        performGlobalAction(GLOBAL_ACTION_BACK)
        main.postDelayed({
            val root = rootInActiveWindow
            if (root?.packageName?.toString() == capture.origin) beginShare(capture.origin, root)
            else GlobalGrabBridge.result(GrabResult.Unavailable(
                "無法讀取完整網址；請保持瀏覽器頁面開啟後重試"))
        }, 480L)
    }

    private fun advanceBrowserCapture() {
        val capture = browserCapture ?: return
        val root = rootInActiveWindow
        if (root != null && root.packageName?.toString() == capture.origin) {
            val result = GrabContentPolicy.decide(capture.origin, readable(root))
            if (result is GrabChoice.Link) {
                clearPending()
                performGlobalAction(GLOBAL_ACTION_BACK)
                GlobalGrabBridge.result(GrabResult.Link(result.url))
                return
            }
        }
        if (SystemClock.uptimeMillis() >= capture.expiry) completeBrowserCapture()
        else schedulePendingCheck()
    }

    fun grabVisibleContent() {
        clearPending()
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString()
        if (pkg == packageName || pkg.isNullOrBlank()) {
            GlobalGrabBridge.result(GrabResult.Unavailable(
                "找不到其他 App 的有效視窗；請先切到要抓取的畫面"))
            return
        }
        val tree = collect(root)
        val plainText = readable(root)
        when (val decision = GrabContentPolicy.decide(pkg, plainText)) {
            is GrabChoice.Link -> {
                GlobalGrabBridge.result(GrabResult.Link(decision.url))
                return
            }
            else -> Unit
        }
        if (GrabContentPolicy.isBrowser(pkg)) {
            // Chromium/Samsung often show only the host in the address pill.
            // Clicking the known address control exposes a complete URL in its
            // edit field. This is initiated only by the user's grab gesture.
            val addresses = tree.first.withIndex().filter { (_, node) ->
                node.visible && node.clickable && !node.password &&
                    GrabContentPolicy.isAddressId(node.id)
            }
            val index = addresses.singleOrNull()?.index
            if (index != null && tree.second[index].performAction(
                    AccessibilityNodeInfo.ACTION_CLICK)) {
                val now = SystemClock.uptimeMillis()
                browserCapture = BrowserCapture(pkg, now + 1800L)
                GlobalGrabBridge.result(GrabResult.ShareStarted(
                    "正在讀取瀏覽器完整網址…"))
                schedulePendingCheck()
                return
            }
        }
        beginShare(pkg, root)
    }

    private fun beginShare(pkg: String, root: AccessibilityNodeInfo) {
        val tree = collect(root)
        val plan = ShareAutomationPolicy.plan(pkg)
        if (plan == null) {
            GlobalGrabBridge.result(GrabResult.Unavailable(
                "此 App 目前無已驗證的分享規則；請用分享選單或手動選取原檔。"))
            return
        }
        val direct = ShareAutomationPolicy.match(plan.steps.first(), tree.first)
        val menu = if (direct == null && GrabContentPolicy.isBrowser(pkg))
            ShareAutomationPolicy.browserOverflow(tree.first) else null
        val index = direct ?: menu
        if (index == null) {
            GlobalGrabBridge.result(GrabResult.Unavailable(
                if (GrabContentPolicy.isBrowser(pkg))
                    "瀏覽器目前沒有可讀取的完整網址，也找不到可信任的分享選項；請在網址列開啟完整網址後重試"
                else "在目前畫面找不到唯一的「分享」入口；未執行自動點擊。"))
            return
        }
        val now = SystemClock.uptimeMillis()
        val flow = RunningShare(pkg, plan, if (direct != null) 1 else 0,
            now + 12_000, now)
        pending = flow
        // Accessibility events are not guaranteed after the Samsung Sharesheet
        // appears. Ensure a definite diagnostic even if no more events arrive.
        val watchdog = Runnable {
            if (pending === flow) {
                clearPending()
                GlobalGrabBridge.result(GrabResult.Unavailable(
                    if (GrabContentPolicy.isBrowser(pkg))
                    "瀏覽器分享選單尚未把連結交給 AirGrab；請確認分享介面有 AirGrab"
                else "相簿分享選單未能自動交付照片給 AirGrab；請確認分享選單有 AirGrab，" +
                    "或手動點選 AirGrab 測試原檔傳送"))
            }
        }
        flowTimeout = watchdog
        main.postDelayed(watchdog, 12_000L)
        if (tree.second[index].performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            GlobalGrabBridge.result(GrabResult.ShareStarted(
                if (GrabContentPolicy.isBrowser(pkg))
                    if (direct != null) "已開啟瀏覽器分享 · 正在找 AirGrab"
                    else "已展開瀏覽器選單 · 正在找分享功能"
                else "已開啟三星相簿分享選單；正在找 AirGrab（尚未取得照片原檔）"))
            schedulePendingCheck()
        } else {
            clearPending()
            GlobalGrabBridge.result(GrabResult.Unavailable("系統拒絕操作原 App 的分享按鈕"))
        }
    }

    private fun advanceFlow() {
        val flow = pending ?: return
        val now = SystemClock.uptimeMillis()
        if (now >= flow.expires) {
            clearPending()
            GlobalGrabBridge.result(GrabResult.Unavailable(
                if (GrabContentPolicy.isBrowser(flow.origin))
                    "網頁連結分享逾時；未能把連結交給 AirGrab"
                else "相簿分享選單等待逾時；尚未收到原始檔案，請手動選 AirGrab 測試"))
            return
        }
        if (now - flow.lastAction < 280) {
            schedulePendingCheck()
            return
        }
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString()
        val (nodes, clickable) = collect(root)
        // Share target selection is only allowed from recognized Android resolver packages.
        if (ShareAutomationPolicy.isTrustedResolver(pkg)) {
            val index = ShareAutomationPolicy.onlyUniqueChooserTarget(pkg, nodes)
            if (index != null && clickable[index].performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                clearPending()
                GlobalGrabBridge.result(GrabResult.ShareStarted(
                    "已點選 AirGrab，等待三星相簿交付照片原檔（尚未送達對方）"))
                return
            }
            // Device-specific resolver may not expose target; never guess a screen position.
            return
        }
        if (pkg != flow.origin || flow.nextStep >= flow.plan.steps.size) return
        val index = ShareAutomationPolicy.match(flow.plan.steps[flow.nextStep], nodes) ?: return
        if (clickable[index].performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            flow.nextStep++
            flow.lastAction = now
            schedulePendingCheck()
        }
    }

    /**
     * Screenshot is an explicit last-resort action, not automatic downgrade.
     * Requires user-granted accessibility permission; protected content can fail.
     */
    fun takeFallbackScreenshot() {
        clearPending()
        val foreground = rootInActiveWindow?.packageName?.toString()
        if (foreground == packageName || foreground == "com.android.systemui") {
            GlobalGrabBridge.result(GrabResult.Unavailable(
                "目前顯示的是隔空傳檔或系統通知面板，請返回原畫面再截圖"))
            return
        }
        if (Build.VERSION.SDK_INT < 30) {
            GlobalGrabBridge.result(GrabResult.Unavailable("Android 版本不支援無障礙畫面擷取"))
            return
        }
        takeScreenshot(Display.DEFAULT_DISPLAY, ContextCompat.getMainExecutor(this),
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val hardware = screenshot.hardwareBuffer
                    try {
                        val wrapped = Bitmap.wrapHardwareBuffer(hardware, screenshot.colorSpace)
                            ?: error("無法取得畫面影像")
                        val pixels = wrapped.copy(Bitmap.Config.ARGB_8888, false)
                            ?: error("無法擷取畫面像素")
                        io.execute {
                            val dir = File(cacheDir, "global-grabs").apply { mkdirs() }
                            val dest = File.createTempFile("screen-", ".png", dir)
                            try {
                                FileOutputStream(dest).use {
                                    check(pixels.compress(Bitmap.CompressFormat.PNG, 100, it))
                                }
                                GlobalGrabBridge.result(GrabResult.ScreenSnapshot(dest))
                            } catch (t: Exception) {
                                dest.delete()
                                GlobalGrabBridge.result(GrabResult.Unavailable(
                                    "截圖儲存失敗：" + t.message))
                            } finally { pixels.recycle() }
                        }
                    } catch (t: Exception) {
                        GlobalGrabBridge.result(GrabResult.Unavailable(
                            "無法擷取此畫面：" + t.message))
                    } finally { hardware.close() }
                }
                override fun onFailure(errorCode: Int) {
                    GlobalGrabBridge.result(GrabResult.Unavailable(
                        "畫面被系統拒絕擷取，錯誤：" + errorCode))
                }
            })
    }

    override fun onDestroy() {
        clearPending()
        GlobalGrabBridge.accessibility = null
        io.shutdown()
        super.onDestroy()
    }
}
