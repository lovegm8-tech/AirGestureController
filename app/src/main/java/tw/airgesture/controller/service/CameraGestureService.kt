package tw.airgesture.controller.service

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import tw.airgesture.controller.FaceUnlockWakeActivity
import tw.airgesture.controller.MainActivity
import tw.airgesture.controller.core.Settings
import tw.airgesture.controller.core.WakeDiagnosticLog
import tw.airgesture.controller.core.WakePalmDwellEngine
import tw.airgesture.controller.core.WakePowerSchedule
import tw.airgesture.controller.vision.GestureCameraAnalyzer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * One camera owner for both regular gestures and screen-off wake.
 *
 * When wake is OFF (default), screen-off releases the camera completely.
 * When wake is ON, the default power mode alternates short camera windows
 * with camera-CLOSED rest intervals. No touch gestures are sent while locked.
 * All state transitions / CameraX binding take place on the main thread.
 */
class CameraGestureService : LifecycleService() {
    companion object {
        const val START = "tw.airgesture.controller.START"
        const val STOP = "tw.airgesture.controller.STOP"
        private const val CHANNEL = "air_gesture_camera"
        private const val NOTIFICATION_ID = 1041
        @Volatile var running = false
            private set
        @Volatile var status = "尚未啟動"
            private set
    }

    private enum class Phase { ACTIVE, SCREEN_SCAN, SCREEN_REST, WAIT_FACE, SCREEN_SLEEP }
    private var phase = Phase.ACTIVE
    private val main = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val power by lazy { getSystemService(POWER_SERVICE) as PowerManager }
    private val keyguard by lazy { getSystemService(KEYGUARD_SERVICE) as KeyguardManager }
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var analyzer: GestureCameraAnalyzer? = null
    private var cameraOpening = false
    private var cameraBound = false
    private var cameraClosing = false
    private var cameraStates: LiveData<CameraState>? = null
    private var cameraObserver: Observer<CameraState>? = null
    private var releaseToken = 0
    private var receiverRegistered = false
    private var wakeEnabled = false
    private var powerMode = 0
    private var screenOffStartMs = -1L
    private var scanStartMs = -1L
    private var lastHandSeenMs = -1L
    private val wakePalm = WakePalmDwellEngine()
    private var cpuWake: PowerManager.WakeLock? = null

    private val enterRest = Runnable { if (running) finishScanWindow() }
    private val leaveRest = Runnable { if (running) resumeScanWindow() }
    private val sessionExpiry = Runnable { if (running) expireScreenWake() }
    private val checkUnlocked = object : Runnable {
        override fun run() {
            if (!running || phase != Phase.WAIT_FACE || !power.isInteractive) return
            if (!keyguard.isKeyguardLocked) {
                onUnlocked()
            } else main.postDelayed(this, 450L)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> onScreenOff()
                Intent.ACTION_SCREEN_ON -> onScreenOn()
                Intent.ACTION_USER_PRESENT -> {
                    WakeDiagnosticLog.add(this@CameraGestureService, "系統回報已解鎖")
                    if (power.isInteractive) onUnlocked()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "隔空手勢控制", NotificationManager.IMPORTANCE_LOW)
        )
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenReceiver, filter)
        receiverRegistered = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action != START) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            status = "需要相機權限"
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } catch (e: Exception) {
            status = "啟動失敗：${e.javaClass.simpleName}"
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        wakeEnabled = Settings.screenWake(this)
        powerMode = Settings.wakePowerMode(this)
        status = "準備前鏡頭與辨識模型…"
        phase = if (!power.isInteractive) Phase.SCREEN_SLEEP else Phase.ACTIVE
        WakeDiagnosticLog.add(this, "手勢服務啟動；熄屏喚醒=${wakeEnabled}；省電模式=${powerMode}")
        executor.execute {
            try {
                val model = GestureCameraAnalyzer(
                    this, Settings.engineOptions(this),
                    Settings.performanceMode(this), Settings.tiltedPalmAssist(this)
                )
                main.post {
                    if (running) {
                        analyzer = model
                        if (phase == Phase.ACTIVE) bindCamera()
                    } else model.close()
                }
            } catch (e: Exception) {
                main.post { fail("手勢模型初始化失敗", e) }
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val stop = PendingIntent.getService(
            this, 10, Intent(this, CameraGestureService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 11, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("隔空手勢控制運作中")
            .setContentText("螢幕亮時操作手勢；熄屏喚醒依設定運作")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_pause, "停止辨識", stop)
            .build()
    }

    private fun bindCamera() {
        if (!running || analyzer == null || cameraOpening || cameraBound || cameraClosing) return
        if (phase != Phase.ACTIVE && phase != Phase.SCREEN_SCAN) return
        if (power.isInteractive && keyguard.isKeyguardLocked) return
        cameraOpening = true
        try {
            val future = ProcessCameraProvider.getInstance(this)
            future.addListener({
                if (!running || cameraClosing ||
                    (phase != Phase.ACTIVE && phase != Phase.SCREEN_SCAN)) {
                    cameraOpening = false
                    return@addListener
                }
                try {
                    val cameraProvider = future.get()
                    provider = cameraProvider
                    val imageAnalysis = ImageAnalysis.Builder()
                        .setTargetResolution(Size(640, 480))
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    imageAnalysis.setAnalyzer(executor) { image ->
                        try {
                            val engine = analyzer
                            if (running && engine != null) {
                                when (phase) {
                                    Phase.ACTIVE -> engine.process(
                                        image,
                                        onAction = { GestureAccessibilityService.send(it) },
                                        onPalmDetection = { GestureAccessibilityService.handDetected(it) },
                                        onPointer = { GestureAccessibilityService.pointerUpdated(it) }
                                    )
                                    Phase.SCREEN_SCAN -> engine.processForWake(image) { palm, hand, stamp ->
                                        main.post {
                                            if (running && phase == Phase.SCREEN_SCAN) {
                                                if (hand) lastHandSeenMs = stamp
                                                if (wakePalm.accept(palm, stamp)) startWakeHandoff()
                                            }
                                        }
                                    }
                                    else -> Unit
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("AirGesture", "Frame processing failed", e)
                        } finally {
                            image.close()
                        }
                    }
                    clearCameraObserver()
                    cameraProvider.unbindAll()
                    val camera = cameraProvider.bindToLifecycle(
                        this, CameraSelector.DEFAULT_FRONT_CAMERA, imageAnalysis
                    )
                    val states = camera.cameraInfo.cameraState
                    cameraStates = states
                    val observer = Observer<CameraState> { current ->
                        if (current != null &&
                            (current.type == CameraState.Type.OPEN ||
                             current.type == CameraState.Type.CLOSED)) {
                            // Only log meaningful handoff states to avoid I/O on every frame.
                            WakeDiagnosticLog.add(this, "CameraX ${current.type}")
                        }
                    }
                    cameraObserver = observer
                    states.observe(this, observer)
                    cameraBound = true
                    analysis = imageAnalysis
                    cameraOpening = false
                    if (phase == Phase.ACTIVE) {
                        status = "辨識中（前鏡頭已啟動）"
                        GestureAccessibilityService.showIndicator()
                    } else {
                        status = "熄屏張掌偵測中"
                    }
                } catch (e: Exception) {
                    cameraOpening = false
                    fail("前鏡頭無法開啟", e)
                }
            }, ContextCompat.getMainExecutor(this))
        } catch (e: Exception) {
            cameraOpening = false
            fail("前鏡頭初始化失敗", e)
        }
    }

    private fun onScreenOff() {
        if (!running) return
        GestureAccessibilityService.hideIndicator()
        main.removeCallbacks(checkUnlocked)
        cancelScanTimers()
        wakeEnabled = Settings.screenWake(this)
        powerMode = Settings.wakePowerMode(this)
        if (!wakeEnabled) {
            phase = Phase.SCREEN_SLEEP
            status = "螢幕已關閉：前鏡頭自動停止以節電"
            WakeDiagnosticLog.add(this, "熄屏喚醒未啟用，關閉相機省電")
            closeCamera(null)
            releaseCpuLock()
            return
        }
        phase = Phase.SCREEN_SCAN
        screenOffStartMs = SystemClock.uptimeMillis()
        scanStartMs = screenOffStartMs
        lastHandSeenMs = -1L
        wakePalm.reset()
        WakeDiagnosticLog.add(this, "熄屏張掌待命：${if (powerMode == 0) "間歇省電" else "連續偵測"}；最多 10 分鐘")
        // A bounded PARTIAL_WAKE_LOCK makes short rest-window reopens timely.
        // It consumes CPU power, unlike a pure camera-off deep-sleep approach.
        try {
            cpuWake = power.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "AirGesture:ScreenWakeSession"
            ).also { it.acquire(WakePowerSchedule.MAX_SESSION_MS) }
        } catch (e: Exception) {
            WakeDiagnosticLog.add(this, "CPU 喚醒鎖不可用：${e.javaClass.simpleName}")
        }
        main.postDelayed(sessionExpiry, WakePowerSchedule.MAX_SESSION_MS)
        if (!cameraBound && !cameraOpening && !cameraClosing) bindCamera()
        if (powerMode == 0) main.postDelayed(enterRest, WakePowerSchedule.SCAN_WINDOW_MS)
    }

    private fun finishScanWindow() {
        if (phase != Phase.SCREEN_SCAN || powerMode != 0) return
        val now = SystemClock.uptimeMillis()
        if (WakePowerSchedule.shouldExtendScan(now, scanStartMs, lastHandSeenMs)) {
            main.postDelayed(enterRest, 400L)
            return
        }
        phase = Phase.SCREEN_REST
        wakePalm.reset()
        status = "熄屏省電：前鏡頭暫停"
        closeCamera { closed ->
            if (phase != Phase.SCREEN_REST || !running) return@closeCamera
            WakeDiagnosticLog.add(this, "省電休息：前鏡頭已${if (closed) "關閉" else "要求關閉"}")
            main.postDelayed(leaveRest, WakePowerSchedule.REST_WINDOW_MS)
        }
    }

    private fun resumeScanWindow() {
        if (phase != Phase.SCREEN_REST || !running || power.isInteractive) return
        if (WakePowerSchedule.expired(SystemClock.uptimeMillis(), screenOffStartMs)) {
            expireScreenWake()
            return
        }
        phase = Phase.SCREEN_SCAN
        scanStartMs = SystemClock.uptimeMillis()
        wakePalm.reset()
        WakeDiagnosticLog.add(this, "省電休息結束；重新啟動前鏡頭偵測")
        bindCamera()
        main.postDelayed(enterRest, WakePowerSchedule.SCAN_WINDOW_MS)
    }

    private fun startWakeHandoff() {
        if (!running || phase != Phase.SCREEN_SCAN || power.isInteractive) return
        phase = Phase.WAIT_FACE
        cancelScanTimers()
        status = "張掌成功，正在讓出前鏡頭給三星臉部辨識"
        WakeDiagnosticLog.add(this, "熄屏張掌持續約 1 秒，開始前鏡頭交接")
        closeCamera { closed ->
            if (!running || phase != Phase.WAIT_FACE || power.isInteractive) return@closeCamera
            if (!closed) {
                status = "相機未確認關閉；為避免干擾臉部辨識，此次未亮屏"
                WakeDiagnosticLog.add(this, "未確認 CameraX CLOSED，暫停亮屏")
                return@closeCamera
            }
            WakeDiagnosticLog.add(this, "CameraX 已確認 CLOSED；400ms 後用 Activity 喚醒")
            main.postDelayed({
                if (running && phase == Phase.WAIT_FACE && !power.isInteractive) {
                    try {
                        startActivity(Intent(this, FaceUnlockWakeActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_NO_ANIMATION or
                                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        })
                        WakeDiagnosticLog.add(this, "已嘗試建立解鎖 Activity")
                    } catch (e: Exception) {
                        WakeDiagnosticLog.add(this, "Activity 亮屏遭系統限制：${e.javaClass.simpleName}")
                        status = "系統不允許此方式亮屏"
                    }
                }
            }, 400L)
        }
    }

    private fun onScreenOn() {
        if (!running) return
        releaseCpuLock()
        cancelScanTimers()
        phase = Phase.WAIT_FACE
        WakeDiagnosticLog.add(this, "螢幕已亮：釋放前鏡頭，等待三星完成解鎖")
        GestureAccessibilityService.hideIndicator()
        closeCamera { if (running && phase == Phase.WAIT_FACE) main.post(checkUnlocked) }
        main.removeCallbacks(checkUnlocked)
        main.postDelayed(checkUnlocked, 300L)
    }

    private fun onUnlocked() {
        if (!running || !power.isInteractive || keyguard.isKeyguardLocked) return
        if (phase == Phase.ACTIVE) return
        phase = Phase.ACTIVE
        releaseCpuLock()
        cancelScanTimers()
        main.removeCallbacks(checkUnlocked)
        WakeDiagnosticLog.add(this, "解鎖成功；將恢復原本完整隔空手勢")
        status = "已解鎖，等待前鏡頭重新啟動"
        main.postDelayed({
            if (running && phase == Phase.ACTIVE && power.isInteractive &&
                !keyguard.isKeyguardLocked) {
                bindCamera()
            }
        }, 1800L)
    }

    private fun expireScreenWake() {
        if (phase != Phase.SCREEN_SCAN && phase != Phase.SCREEN_REST) return
        phase = Phase.SCREEN_SLEEP
        releaseCpuLock()
        cancelScanTimers()
        wakePalm.reset()
        status = "熄屏喚醒已達 10 分鐘上限，停止攝影機以節電"
        WakeDiagnosticLog.add(this, "省電保護：熄屏喚醒達 10 分鐘上限，停止監測")
        closeCamera(null)
    }

    private fun cancelScanTimers() {
        main.removeCallbacks(enterRest)
        main.removeCallbacks(leaveRest)
        main.removeCallbacks(sessionExpiry)
    }

    private fun clearCameraObserver() {
        val states = cameraStates
        val obs = cameraObserver
        if (states != null && obs != null) states.removeObserver(obs)
        cameraStates = null
        cameraObserver = null
    }

    /** Await CameraX's asynchronous CLOSED state before launching face unlock. */
    private fun closeCamera(onClosed: ((Boolean) -> Unit)?) {
        if (cameraClosing) {
            // An earlier close is already in progress; retry after it settles.
            main.postDelayed({
                if (running) closeCamera(onClosed)
            }, 220L)
            return
        }
        if (!cameraBound && !cameraOpening && cameraStates == null) {
            onClosed?.invoke(true)
            return
        }
        cameraClosing = true
        val token = ++releaseToken
        val states = cameraStates
        try {
            analysis?.clearAnalyzer()
            provider?.unbindAll()
        } catch (e: Exception) {
            WakeDiagnosticLog.add(this, "停止相機時發生錯誤：${e.javaClass.simpleName}")
        }
        cameraBound = false
        cameraOpening = false
        analysis = null
        if (states == null) {
            cameraClosing = false
            onClosed?.invoke(true)
            return
        }
        clearCameraObserver()
        var finished = false
        val closingObserver = Observer<CameraState> { current ->
            if (!finished && current?.type == CameraState.Type.CLOSED) {
                main.post {
                    if (!finished && releaseToken == token) {
                        finished = true
                        states.removeObservers(this)
                        cameraClosing = false
                        WakeDiagnosticLog.add(this, "CameraX 前鏡頭 CLOSED")
                        onClosed?.invoke(true)
                    }
                }
            }
        }
        states.observe(this, closingObserver)
        main.postDelayed({
            if (!finished && releaseToken == token) {
                finished = true
                states.removeObserver(closingObserver)
                cameraClosing = false
                val closed = states.value?.type == CameraState.Type.CLOSED
                WakeDiagnosticLog.add(this, "關閉相機等待逾時；CLOSED=${closed}")
                onClosed?.invoke(closed)
            }
        }, 3000L)
    }

    private fun releaseCpuLock() {
        try {
            cpuWake?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) { }
        cpuWake = null
    }

    private fun fail(label: String, e: Exception) {
        Log.e("AirGesture", label, e)
        status = "$label：${e.javaClass.simpleName}"
        WakeDiagnosticLog.add(this, "錯誤：$label：${e.javaClass.simpleName}")
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        cancelScanTimers()
        main.removeCallbacks(checkUnlocked)
        releaseCpuLock()
        if (receiverRegistered) {
            try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
            receiverRegistered = false
        }
        GestureAccessibilityService.hideIndicator()
        try {
            analysis?.clearAnalyzer()
            provider?.unbindAll()
        } catch (_: Exception) {}
        cameraBound = false
        cameraOpening = false
        clearCameraObserver()
        if (!executor.isShutdown) {
            executor.execute {
                try { analyzer?.close() } catch (e: Exception) {
                    Log.w("AirGesture", "GestureRecognizer close failed", e)
                }
                analyzer = null
            }
            executor.shutdown()
        }
        status = "已停止"
        super.onDestroy()
    }
}
