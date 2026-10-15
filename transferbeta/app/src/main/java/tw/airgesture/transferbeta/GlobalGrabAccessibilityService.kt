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
        if (event == null || pending == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return
        schedulePendingCheck()
    }

    override fun onInterrupt() { clearPending() }

    private fun schedulePendingCheck() {
        if (nextAttempt != null) return
        val call = Runnable { nextAttempt = null; advanceFlow() }
        nextAttempt = call
        main.postDelayed(call, 320)
    }

    private fun clearPending() {
        nextAttempt?.let { main.removeCallbacks(it) }
        nextAttempt = null
        pending = null
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
        val plainText = tree.first.map { VisibleText(it.id, it.label ?: it.hint, it.password) }
        when (val decision = GrabContentPolicy.decide(pkg, plainText)) {
            is GrabChoice.Link -> {
                GlobalGrabBridge.result(GrabResult.Link(decision.url))
                return
            }
            else -> Unit
        }
        val plan = ShareAutomationPolicy.plan(pkg)
        if (plan == null) {
            GlobalGrabBridge.result(GrabResult.Unavailable(
                "此 App 目前無已驗證的分享規則；請用分享選單或手動選取原檔。"))
            return
        }
        val index = ShareAutomationPolicy.match(plan.steps.first(), tree.first)
        if (index == null) {
            GlobalGrabBridge.result(GrabResult.Unavailable(
                "在目前畫面找不到唯一的「分享」入口；未執行自動點擊。"))
            return
        }
        val now = SystemClock.uptimeMillis()
        pending = RunningShare(pkg, plan, 1, now + 12_000, now)
        if (tree.second[index].performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            GlobalGrabBridge.result(GrabResult.ShareStarted(plan.description))
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
                "分享流程已逾時；可能需要使用者手動確認格式或分享選單"))
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
                GlobalGrabBridge.result(GrabResult.ShareStarted("已選擇隔空傳檔，正在接收原 App 提供的檔案或網址"))
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
