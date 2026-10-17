package tw.airgesture.transferbeta

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
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
        var lastAction: Long,
        var hubMoreClicked: Boolean = false,
        var targetClicked: Boolean = false,
        var youtubeShareScrolls: Int = 0
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
        val positions = HashMap<String, Int>()
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
                // AccessibilityNodeInfo.parent can return a fresh wrapper for the
                // same underlying control. Object identity would incorrectly
                // count one share button multiple times and reject it as ambiguous.
                val bounds = Rect().also { target.getBoundsInScreen(it) }
                val identity = "${target.viewIdResourceName}:${bounds.left}:" +
                    "${bounds.top}:${bounds.right}:${bounds.bottom}"
                val texts = listOfNotNull(label, description,
                    target.text?.toString(), target.contentDescription?.toString())
                    .filter { it.isNotBlank() }
                val ids = listOfNotNull(view, target.viewIdResourceName)
                val existing = positions[identity]
                if (existing == null) {
                    positions[identity] = nodes.size
                    nodes.add(ActionNode(
                        view ?: target.viewIdResourceName,
                        label ?: target.text?.toString(),
                        description ?: target.contentDescription?.toString(),
                        clickable = true,
                        password = node.isPassword || target.isPassword,
                        visible = node.isVisibleToUser && target.isVisibleToUser,
                        depth = depth,
                        texts = texts.distinct(),
                        ids = ids.distinct()
                    ))
                    clickable.add(target)
                } else {
                    val old = nodes[existing]
                    nodes[existing] = old.copy(
                        texts = (old.texts + texts).distinct().take(32),
                        ids = (old.ids + ids).distinct().take(24),
                        visible = old.visible || (node.isVisibleToUser && target.isVisibleToUser)
                    )
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
                    found.add(VisibleText(node.viewIdResourceName, text,
                        focused = node.isFocused, editable = node.isEditable))
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

    /**
     * The address editor can display a card for the CURRENT page with a Share
     * icon even when its URL text is ellipsized. Only consider controls in the
     * upper-right area; do not click similarly named search suggestions.
     * We reach this method only after AirGrab tapped a trusted address bar.
     */
    private fun addressCardShareIndex(
        pkg: String,
        tree: Pair<List<ActionNode>, List<AccessibilityNodeInfo>>
    ): Int? {
        if (pkg != "com.android.chrome" &&
            pkg != "com.google.android.googlequicksearchbox") return null
        val width = resources.displayMetrics.widthPixels
        val height = resources.displayMetrics.heightPixels
        val topLimit = (height * 0.45f).toInt()
        val leftLimit = (width * 0.45f).toInt()
        val restricted = tree.first.mapIndexed { index, node ->
            val rect = Rect().also { tree.second[index].getBoundsInScreen(it) }
            val onCurrentPageCard = rect.width() > 0 && rect.height() > 0 &&
                rect.top >= 0 && rect.top <= topLimit && rect.centerX() >= leftLimit
            if (onCurrentPageCard) node else node.copy(visible = false)
        }
        return ShareAutomationPolicy.addressCardShare(pkg, restricted)
    }

    private fun advanceBrowserCapture() {
        val capture = browserCapture ?: return
        val root = rootInActiveWindow
        if (root != null && root.packageName?.toString() == capture.origin) {
            val result = GrabContentPolicy.decide(
                capture.origin, readable(root), allowFocusedEditor = true)
            if (result is GrabChoice.Link) {
                clearPending()
                performGlobalAction(GLOBAL_ACTION_BACK)
                GlobalGrabBridge.result(GrabResult.Link(result.url))
                return
            }
            // The screenshot-confirmed Chrome/Google current-page row exposes
            // Share beside Copy/Edit without exposing an untruncated URL.
            if (addressCardShareIndex(capture.origin, collect(root)) != null) {
                browserCapture = null
                nextAttempt?.let { main.removeCallbacks(it) }
                nextAttempt = null
                beginShare(capture.origin, root, fromAddressCard = true)
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
        // Prefer the address editor for Chrome and Google: the current-page
        // card has the specific Share icon confirmed on both devices. The
        // browser's usual Share/overflow menu remains the fallback below.
        if (GrabContentPolicy.isBrowser(pkg)) {
            // Chromium/Samsung often show only the host in the address pill.
            // Clicking the known address control exposes a complete URL in its
            // edit field. This is initiated only by the user's grab gesture.
            val addresses = tree.first.withIndex().filter { (_, node) ->
                node.visible && node.clickable && !node.password &&
                    (GrabContentPolicy.isAddressId(node.id) ||
                     node.ids.any(GrabContentPolicy::isAddressId))
            }
            val index = addresses.singleOrNull()?.index
            if (index != null && tree.second[index].performAction(
                    AccessibilityNodeInfo.ACTION_CLICK)) {
                val now = SystemClock.uptimeMillis()
                browserCapture = BrowserCapture(pkg, now + 3000L)
                GlobalGrabBridge.result(GrabResult.ShareStarted(
                    "正在讀取瀏覽器完整網址…"))
                schedulePendingCheck()
                return
            }
        }
        beginShare(pkg, root)
    }

    private fun beginShare(
        pkg: String, root: AccessibilityNodeInfo, fromAddressCard: Boolean = false
    ) {
        val tree = collect(root)
        val plan = ShareAutomationPolicy.plan(pkg)
        if (plan == null) {
            GlobalGrabBridge.result(GrabResult.Unavailable(
                "此 App 目前無已驗證的分享規則；請用分享選單或手動選取原檔。"))
            return
        }
        val direct = if (fromAddressCard) addressCardShareIndex(pkg, tree)
            else ShareAutomationPolicy.match(plan.steps.first(), tree.first)
        val menu = if (!fromAddressCard && direct == null && GrabContentPolicy.isBrowser(pkg))
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
                else if (pkg == YouTubeLinkPolicy.PACKAGE)
                    "YouTube 分享選單沒有開啟「其他項目」；請檢查分享面板"
                else "來源 App 分享選單未能自動交付檔案；請手動選 AirGrab 測試"))
            }
        }
        flowTimeout = watchdog
        main.postDelayed(watchdog, 12_000L)
        if (tree.second[index].performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            GlobalGrabBridge.result(GrabResult.ShareStarted(
                if (GrabContentPolicy.isBrowser(pkg))
                    if (fromAddressCard) "已點選目前網頁旁的分享圖示 · 正在找 AirGrab"
                    else if (direct != null) "已開啟瀏覽器分享 · 正在找 AirGrab"
                    else "已展開瀏覽器選單 · 正在找分享功能"
                else if (pkg == YouTubeLinkPolicy.PACKAGE)
                    "已開啟 YouTube 分享面板；正在尋找「其他項目」"
                else "已開啟來源 App 分享選單；正在找 AirGrab（尚未取得原檔）"))
            schedulePendingCheck()
        } else {
            clearPending()
            GlobalGrabBridge.result(GrabResult.Unavailable("系統拒絕操作原 App 的分享按鈕"))
        }
    }

    /**
     * Reveal later apps in YouTube's horizontally scrolling share row.
     * First ask a unique accessible carousel to scroll right. If YouTube does
     * not expose a scrollable row, dispatch one bounded leftward swipe inside
     * the screenshot-confirmed lower-half share sheet. This runs only after
     * a user grab gesture and after the native YouTube sheet is verified.
     */
    private fun scrollYouTubeShareRow(root: AccessibilityNodeInfo): Boolean {
        if (root.packageName?.toString() != YouTubeLinkPolicy.PACKAGE) return false
        val height = resources.displayMetrics.heightPixels
        val width = resources.displayMetrics.widthPixels
        val nodes = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        nodes.add(root to 0)
        val candidates = ArrayList<AccessibilityNodeInfo>()
        var shareSheetMarkerInLowerHalf = false
        var inspected = 0
        while (nodes.isNotEmpty() && inspected++ < 260) {
            val (node, depth) = nodes.removeFirst()
            if (!node.isVisibleToUser || node.isPassword) continue
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            if (bounds.width() > 0 && bounds.height() > 0 &&
                bounds.top >= (height * 0.48f).toInt() &&
                bounds.top < (height * 0.94f).toInt() &&
                YouTubeLinkPolicy.isShareSheet(YouTubeLinkPolicy.PACKAGE,
                    listOfNotNull(node.text?.toString(),
                        node.contentDescription?.toString()))) {
                shareSheetMarkerInLowerHalf = true
            }
            if (node.isScrollable) {
                val inBottomSheet = bounds.top >= (height * 0.48f).toInt() &&
                    bounds.top <= (height * 0.82f).toInt() &&
                    bounds.bottom <= height &&
                    bounds.width() >= (width * 0.55f).toInt() &&
                    bounds.height() <= (height * 0.32f).toInt()
                if (inBottomSheet) candidates.add(node)
            }
            if (depth < 16) {
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { nodes.add(it to (depth + 1)) }
                }
            }
        }
        // Never swipe the underlying video or progress bar if the sheet is gone.
        if (!shareSheetMarkerInLowerHalf) return false
        if (candidates.size == 1) {
            val row = candidates.single()
            if (row.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id) ||
                row.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
        }
        // Some versions of YouTube omit scroll actions from the carousel.
        // Layout observed: share panel starts around 55% height; horizontal app
        // icons occupy around 62-72%. Avoid vertical gestures and screen edges.
        val y = height * 0.67f
        val path = Path().apply {
            moveTo(width * 0.83f, y)
            lineTo(width * 0.23f, y)
        }
        return dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 330L))
                .build(), null, null
        )
    }

    private fun advanceFlow() {
        val flow = pending ?: return
        val now = SystemClock.uptimeMillis()
        if (now >= flow.expires) {
            clearPending()
            GlobalGrabBridge.result(GrabResult.Unavailable(
                if (GrabContentPolicy.isBrowser(flow.origin))
                    "網頁連結分享逾時；未能把連結交給 AirGrab"
                else if (flow.origin == YouTubeLinkPolicy.PACKAGE)
                    "YouTube 分享面板或系統分享清單逾時；未取得影片連結"
                else "來源 App 分享選單等待逾時；尚未收到原始檔案"))
            return
        }
        if (now - flow.lastAction < 280) {
            schedulePendingCheck()
            return
        }
        // Android/Samsung resolver targets may arrive after the first event;
        // keep polling only for this user-triggered, 12-second share session.
        try {
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString()
        val (nodes, clickable) = collect(root)
        // The YouTube app's own panel (screenshots show "其他項目") opens
        // the Android system chooser, where AirGrab is an ACTION_SEND target.
        // This does not require reading the clipboard or leaving YouTube.
        if (flow.origin == YouTubeLinkPolicy.PACKAGE &&
            pkg == YouTubeLinkPolicy.PACKAGE && !flow.hubMoreClicked) {
            val visibleText = readable(root).mapNotNull { it.text }
            if (YouTubeLinkPolicy.isShareSheet(pkg, visibleText)) {
                val more = YouTubeLinkPolicy.moreAppsTarget(pkg, nodes, visibleText)
                if (more != null) {
                    if (clickable[more].performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        flow.hubMoreClicked = true
                        flow.lastAction = now
                        GlobalGrabBridge.result(GrabResult.ShareStarted(
                            "已點選 YouTube「其他項目」；正在尋找系統分享清單的 AirGrab"))
                        return
                    }
                } else if (flow.youtubeShareScrolls < 4 &&
                    scrollYouTubeShareRow(root)) {
                    flow.youtubeShareScrolls++
                    flow.lastAction = now
                    GlobalGrabBridge.result(GrabResult.ShareStarted(
                        "正在橫向滑動 YouTube 分享 App 清單，尋找「其他項目」 " +
                            "(${flow.youtubeShareScrolls}/4)"))
                    return
                }
            }
        }
        // Chrome's native Sharing Hub is not an Android resolver. Only
        // interact if its distinct accessibility sheet description is visible.
        // Never select a page element solely because it contains "AirGrab".
        if (flow.plan.kind == GrabKind.BROWSER && pkg == "com.android.chrome" &&
            ShareAutomationPolicy.isChromeSharingHub(pkg,
                readable(root).mapNotNull { it.text })) {
            if (flow.targetClicked) return
            val target = ShareAutomationPolicy.browserHubTarget(nodes)
            if (target != null &&
                clickable[target].performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                flow.targetClicked = true
                flow.lastAction = now
                GlobalGrabBridge.result(GrabResult.ShareStarted(
                    "已從 Chrome 分享面板選擇 AirGrab；等待網址交付"))
                return
            }
            if (!flow.hubMoreClicked) {
                val more = ShareAutomationPolicy.browserHubMore(nodes)
                if (more != null &&
                    clickable[more].performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    flow.hubMoreClicked = true
                    flow.lastAction = now
                    GlobalGrabBridge.result(GrabResult.ShareStarted(
                        "已展開 Chrome 的完整分享清單；正在尋找 AirGrab"))
                    return
                }
            }
        }
        // A chooser click alone is not proof of delivered content. Keep
        // watchdog active until ShareReceiverActivity confirms ACTION_SEND.
        if (ShareAutomationPolicy.isTrustedResolver(pkg)) {
            if (flow.targetClicked) return
            val index = ShareAutomationPolicy.onlyUniqueChooserTarget(pkg, nodes)
            if (index != null && clickable[index].performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                flow.targetClicked = true
                flow.lastAction = now
                GlobalGrabBridge.result(GrabResult.ShareStarted(
                    if (flow.plan.kind == GrabKind.BROWSER)
                        "已從系統分享清單點選 AirGrab；等待網址交付"
                    else "已點選 AirGrab，等待原始檔案交付"))
                return
            }
            return
        }
        if (pkg != flow.origin || flow.nextStep >= flow.plan.steps.size) return
        val index = ShareAutomationPolicy.match(flow.plan.steps[flow.nextStep], nodes) ?: return
        if (clickable[index].performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            flow.nextStep++
            flow.lastAction = now
        }
        } finally {
            if (pending === flow) schedulePendingCheck()
        }
    }

    /** Called only after our ACTION_SEND target receives a valid URL or URI.
     * Prevents the 12-second chooser watchdog from falsely reporting failure. */
    fun externalShareDelivered() {
        if (Looper.myLooper() == Looper.getMainLooper()) clearPending()
        else main.post { clearPending() }
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
