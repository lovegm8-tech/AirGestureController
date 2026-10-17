package tw.airgesture.transferbeta

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Point
import android.media.AudioManager
import android.app.KeyguardManager
import android.os.PowerManager
import android.view.KeyEvent
import android.view.WindowManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
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
    companion object {
        @Volatile private var current: WeakReference<GlobalGrabAccessibilityService>? = null
        @Volatile var foregroundPackage: String? = null
            private set
        private const val YOUTUBE = "com.google.android.youtube"

        fun isYouTubeForeground(): Boolean = foregroundPackage == YOUTUBE
        fun sendControl(command: FloatingControlRouter.Command) {
            current?.get()?.let { service ->
                service.main.post {
                    if (current?.get() === service) service.executeControl(command)
                }
            }
        }
        fun setCursor(cursor: PointerDwellEngine.Result?) {
            current?.get()?.let { service ->
                service.main.post {
                    if (current?.get() !== service) return@post
                    if (cursor == null) {
                        service.pointerOverlay?.hide()
                    } else {
                        if (service.pointerOverlay == null)
                            service.pointerOverlay = FloatingPointerOverlay(service)
                        val position = service.pointerOverlay?.update(cursor.x, cursor.y,
                            cursor.progress, cursor.click)
                        if (cursor.click && position != null)
                            service.performControlTap(position.first, position.second)
                    }
                }
            }
        }
    }
    private val io = Executors.newSingleThreadExecutor()
    private var pointerOverlay: FloatingPointerOverlay? = null
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
        var youtubeShareScrolls: Int = 0,
        var tiktokShareScrolls: Int = 0,
        var galleryImageChoiceClicked: Boolean = false
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        GlobalGrabBridge.accessibility = WeakReference(this)
        current = WeakReference(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Expensive node traversal only while a bounded, user-initiated share is pending.
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
            foregroundPackage = event.packageName?.toString()
        if (pending == null && browserCapture == null) return
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

    /**
     * Some Chrome builds show the current page's unlabeled Share icon between
     * Copy and Edit. Infer it only inside the address editor that AirGrab opened,
     * using the positions of TWO uniquely labeled adjacent buttons. This is a
     * fallback for missing accessibility labels, not general coordinate tapping.
     */
    private fun chromeShareCardPoint(
        pkg: String,
        tree: Pair<List<ActionNode>, List<AccessibilityNodeInfo>>
    ): ChromeCardPoint? {
        if (pkg != "com.android.chrome") return null
        val controls = tree.first.mapIndexed { i, n ->
            val bounds = Rect().also { tree.second[i].getBoundsInScreen(it) }
            ChromeCardControl(
                label = listOfNotNull(n.label, n.hint).firstOrNull()
                    ?: n.texts.firstOrNull() ?: "",
                x = bounds.exactCenterX(),
                y = bounds.exactCenterY(),
                visible = n.visible && bounds.width() > 0 && bounds.height() > 0,
                clickable = n.clickable && !n.password
            )
        }
        return ChromeShareCardPolicy.inferSharePoint(
            pkg, browserCapture?.origin == pkg,
            resources.displayMetrics.widthPixels.toFloat(),
            resources.displayMetrics.heightPixels.toFloat(), controls
        )
    }

    private fun tapChromeShareCard(point: ChromeCardPoint): Boolean {
        val path = Path().apply { moveTo(point.x, point.y) }
        return dispatchGesture(
            GestureDescription.Builder().addStroke(
                GestureDescription.StrokeDescription(path, 0L, 70L)
            ).build(), null, null
        )
    }

    /**
     * Chrome's address search screen may launch Samsung Keyboard, making the
     * IME the active accessibility root. Access Chrome's unique underlying
     * application window only for the deliberate, short browserCapture flow.
     * Other apps and unrelated windows are never searched.
     */
    private fun rootForBrowserCapture(capture: BrowserCapture): AccessibilityNodeInfo? {
        val activeRoot = rootInActiveWindow
        if (activeRoot?.packageName?.toString() == capture.origin) return activeRoot
        if (capture.origin != "com.android.chrome") return null
        val windowsNow = windows ?: return null
        val hasKeyboard = windowsNow.any { win ->
            win.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
        }
        val chromeRoots = windowsNow.filter {
            it.type == AccessibilityWindowInfo.TYPE_APPLICATION
        }.mapNotNull { it.root }.filter { root ->
            root.packageName?.toString() == capture.origin && root.isVisibleToUser
        }
        return if (ChromeShareCardPolicy.mayUseChromeWindowBehindKeyboard(
                capture.origin, activeRoot?.packageName?.toString(),
                hasKeyboard, chromeRoots.size))
            chromeRoots.single() else null
    }

    private fun advanceBrowserCapture() {
        val capture = browserCapture ?: return
        val root = rootForBrowserCapture(capture)
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
            val tree = collect(root)
            if (addressCardShareIndex(capture.origin, tree) != null ||
                chromeShareCardPoint(capture.origin, tree) != null) {
                nextAttempt?.let { main.removeCallbacks(it) }
                nextAttempt = null
                beginShare(capture.origin, root, fromAddressCard = true)
                browserCapture = null
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
        val chromeUnlabeledShare = if (fromAddressCard && index == null)
            chromeShareCardPoint(pkg, tree) else null
        if (index == null && chromeUnlabeledShare == null) {
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
                else if (TikTokSharePolicy.isTikTok(pkg))
                    "TikTok 分享選單未找到「更多」，或未將網址交付 AirGrab"
                else if (pkg == SamsungGalleryScreenshotSharePolicy.GALLERY_PACKAGE)
                    "三星相簿尚未將截圖交付 AirGrab；若有選擇視窗，請選「圖像」"
                else "來源 App 分享選單未能自動交付檔案；請手動選 AirGrab 測試"))
            }
        }
        flowTimeout = watchdog
        main.postDelayed(watchdog, 12_000L)
        if ((index != null &&
                tree.second[index].performAction(AccessibilityNodeInfo.ACTION_CLICK)) ||
            (chromeUnlabeledShare != null && tapChromeShareCard(chromeUnlabeledShare))) {
            GlobalGrabBridge.result(GrabResult.ShareStarted(
                if (GrabContentPolicy.isBrowser(pkg))
                    if (fromAddressCard) "已啟動 Chrome 當前網頁分享圖示 · 正在找 AirGrab"
                    else if (direct != null) "已開啟瀏覽器分享 · 正在找 AirGrab"
                    else "已展開瀏覽器選單 · 正在找分享功能"
                else if (pkg == YouTubeLinkPolicy.PACKAGE)
                    "已開啟 YouTube 分享面板；正在尋找「其他項目」"
                else if (TikTokSharePolicy.isTikTok(pkg))
                    "已開啟 TikTok 分享面板；正在尋找「更多」"
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

    /**
     * TikTok puts "更多" at the end of the horizontal app row, which is lower
     * on screen than YouTube's. Only called inside the confirmed TikTok "傳送至"
     * panel after the user's explicit grab. Never swipe the underlying video.
     */
    private fun scrollTikTokShareRow(root: AccessibilityNodeInfo): Boolean {
        if (!TikTokSharePolicy.isTikTok(root.packageName?.toString())) return false
        val width = resources.displayMetrics.widthPixels
        val height = resources.displayMetrics.heightPixels
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        val candidates = ArrayList<AccessibilityNodeInfo>()
        var inspected = 0
        while (queue.isNotEmpty() && inspected++ < 260) {
            val (node, depth) = queue.removeFirst()
            if (!node.isVisibleToUser || node.isPassword) continue
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            if (node.isScrollable && bounds.width() >= (width * 0.55f).toInt() &&
                bounds.top >= (height * 0.68f).toInt() &&
                bounds.top <= (height * 0.86f).toInt() &&
                bounds.bottom <= (height * 0.90f).toInt() &&
                bounds.height() <= (height * 0.24f).toInt()) {
                candidates.add(node)
            }
            if (depth < 16) {
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it to (depth + 1)) }
                }
            }
        }
        if (candidates.size == 1) {
            val row = candidates.single()
            if (row.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id) ||
                row.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
        }
        // The native TikTok sheet in S26 Ultra screenshots places the app row
        // around 78-84% of the display. A single horizontal swipe here cannot
        // touch the video progress bar or the separate action row below.
        val y = height * 0.80f
        val path = Path().apply {
            moveTo(width * 0.83f, y)
            lineTo(width * 0.23f, y)
        }
        return dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 330L))
                .build(), null, null)
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
                else if (TikTokSharePolicy.isTikTok(flow.origin))
                    "TikTok 分享／系統清單逾時；沒有收到影片連結"
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
        // Galaxy Gallery recognizes a URL inside some screenshots and opens
        // "螢幕截圖分享形式：圖像 / 網頁連結". When a grab began in
        // Samsung Gallery, prefer the original screenshot image. Only click
        // inside the uniquely identified modal, never a generic "圖像" label.
        if (flow.origin == SamsungGalleryScreenshotSharePolicy.GALLERY_PACKAGE &&
            !flow.galleryImageChoiceClicked) {
            val visibleText = readable(root).mapNotNull { it.text }
            if (SamsungGalleryScreenshotSharePolicy.isChoiceDialog(
                    flow.origin, pkg, visibleText)) {
                val idx = SamsungGalleryScreenshotSharePolicy.imageTarget(
                    flow.origin, pkg, visibleText, nodes)
                if (idx != null) {
                    val bounds = Rect().also { clickable[idx].getBoundsInScreen(it) }
                    val screenHeight = resources.displayMetrics.heightPixels
                    val insideModal = bounds.width() > 0 && bounds.height() > 0 &&
                        bounds.top >= (screenHeight * 0.50f).toInt() &&
                        bounds.bottom <= screenHeight
                    if (insideModal &&
                        clickable[idx].performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        flow.galleryImageChoiceClicked = true
                        flow.lastAction = now
                        GlobalGrabBridge.result(GrabResult.ShareStarted(
                            "已選擇「圖像」，正在開啟三星相簿原始截圖分享選單"))
                        return
                    }
                }
                // A confirmed image/link dialog must never fall through to the
                // generic Android chooser or click an unrelated control.
                return
            }
        }
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
        // TikTok uses its own "傳送至" panel, with "更多" at the far
        // right of the horizontal app carousel. Only this distinct package
        // and sheet may trigger a bounded leftward swipe.
        if (TikTokSharePolicy.isTikTok(flow.origin) && pkg == flow.origin &&
            !flow.hubMoreClicked) {
            val visibleText = readable(root).mapNotNull { it.text }
            if (TikTokSharePolicy.isNativeShareSheet(pkg, visibleText)) {
                val height = resources.displayMetrics.heightPixels.toFloat()
                val candidates = nodes.mapIndexed { i, node ->
                    val rect = Rect().also { clickable[i].getBoundsInScreen(it) }
                    TikTokShareCandidate(node, rect.exactCenterY() / height)
                }
                val more = TikTokSharePolicy.moreAppsTarget(pkg, visibleText, candidates)
                if (more != null) {
                    if (clickable[more].performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        flow.hubMoreClicked = true
                        flow.lastAction = now
                        GlobalGrabBridge.result(GrabResult.ShareStarted(
                            "已選 TikTok「更多」；正在尋找系統分享清單的 AirGrab"))
                        return
                    }
                } else if (flow.tiktokShareScrolls < 4 && scrollTikTokShareRow(root)) {
                    flow.tiktokShareScrolls++
                    // Give TikTok's carousel time to finish each gesture.
                    flow.lastAction = now + 400L
                    GlobalGrabBridge.result(GrabResult.ShareStarted(
                        "正在滑動 TikTok 分享清單，尋找「更多」 " +
                            "(${flow.tiktokShareScrolls}/4)"))
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

    @Suppress("DEPRECATION")
    private fun screenSize(): Point = Point().also {
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealSize(it)
    }

    private fun controlAllowed(): Boolean {
        val power = getSystemService(POWER_SERVICE) as PowerManager
        val keyguard = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        return power.isInteractive && !keyguard.isKeyguardLocked &&
            foregroundPackage != packageName && pending == null && browserCapture == null
    }

    private fun performControlTap(x: Float, y: Float) {
        if (!controlAllowed()) return
        val stroke = GestureDescription.StrokeDescription(
            Path().apply { moveTo(x, y) }, 0L, 80L
        )
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun executeControl(command: FloatingControlRouter.Command) {
        if (!controlAllowed()) return
        if (command == FloatingControlRouter.Command.MEDIA_PLAY_PAUSE) {
            if (foregroundPackage != YOUTUBE) return
            val audio = getSystemService(AUDIO_SERVICE) as AudioManager
            val now = SystemClock.uptimeMillis()
            audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0))
            audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0))
            return
        }
        val size = screenSize()
        if (size.x < 1 || size.y < 1) return
        val x = size.x * .5f
        val y = size.y * .5f
        val path = Path()
        val duration = when (command) {
            FloatingControlRouter.Command.SWIPE_UP -> {
                path.moveTo(x, size.y * .74f)
                path.lineTo(x, size.y * .30f)
                320L
            }
            FloatingControlRouter.Command.SWIPE_DOWN -> {
                path.moveTo(x, size.y * .30f)
                path.lineTo(x, size.y * .74f)
                320L
            }
            FloatingControlRouter.Command.SWIPE_BACK -> {
                path.moveTo(size.x * .015f, y)
                path.lineTo(size.x * .60f, y)
                310L
            }
            FloatingControlRouter.Command.TAP -> {
                path.moveTo(x, y)
                80L
            }
            else -> return
        }
        dispatchGesture(GestureDescription.Builder().addStroke(
            GestureDescription.StrokeDescription(path, 0L, duration)
        ).build(), null, null)
    }

    override fun onDestroy() {
        clearPending()
        pointerOverlay?.hide()
        pointerOverlay = null
        if (current?.get() === this) current = null
        foregroundPackage = null
        GlobalGrabBridge.accessibility = null
        io.shutdown()
        super.onDestroy()
    }
}
