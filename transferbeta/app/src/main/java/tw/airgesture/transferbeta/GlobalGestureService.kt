package tw.airgesture.transferbeta

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.KeyguardManager
import android.os.PowerManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.SystemClock
import android.util.Range
import android.os.Handler
import android.os.Looper
import android.util.Size
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import tw.airgesture.transferbeta.core.AdaptivePowerPolicy
import tw.airgesture.transferbeta.core.CameraUnlockGate
import tw.airgesture.transferbeta.core.VisualEvent
import tw.airgesture.transferbeta.core.VisualPhase
import tw.airgesture.transferbeta.core.HandFeedbackPolicy
import tw.airgesture.transferbeta.core.FloatingControlRouter
import tw.airgesture.transferbeta.core.TiltedPalmAssist
import tw.airgesture.transferbeta.core.FloatingSettings
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * Foreground camera service, started ONLY from visible TransferActivity.
 * ECO mode throttles ML inference; it cannot guarantee tiny camera sensor current.
 * Releases camera on screen off, only rebinds AFTER genuine unlock.
 * Nearby connections remain active while locked; manual user start is required
 * if Android kills the entire service.
 */
class GlobalGestureService : LifecycleService() {
    companion object {
        const val ACTION_START = "tw.airgesture.transferbeta.START_GLOBAL"
        const val ACTION_STOP = "tw.airgesture.transferbeta.STOP_GLOBAL"
        const val ACTION_CAPTURE_SCREEN = "tw.airgesture.transferbeta.EXPLICIT_SCREEN_CAPTURE"
        const val ACTION_CONFIRM_RECEIVE = "tw.airgesture.transferbeta.CONFIRM_RECEIVE"
        const val ACTION_SETTINGS_CHANGED = "tw.airgesture.transferbeta.SETTINGS_CHANGED"
        private const val CHANNEL = "global_grab_camera"
        private const val NOTIFICATION = 4108
        @Volatile var isActive = false
            private set
        @Volatile var isPausedForGame = false
            private set
        @Volatile private var activeInstance: WeakReference<GlobalGestureService>? = null

        /** Accessibility sends changes only when the actual foreground window changes. */
        fun onGameForegroundChanged(game: Boolean) {
            val service = activeInstance?.get() ?: return
            service.main.post {
                if (activeInstance?.get() === service && isActive)
                    service.setGameSuspended(game)
            }
        }
        @Volatile var transfer: MultiPeerTransferManager? = null
            private set
        @Volatile var framesSeen = 0L
            private set
        @Volatile var inferences = 0L
            private set
        @Volatile var requestedSensorFps = "由裝置決定"
            private set
        @Volatile var lastPose = "尚未辨識"
            private set
        @Volatile var lastGestureAction = "尚無指令"
            private set
        @Volatile var actionCount = 0L
            private set
        @Volatile var lastRecognitionError = "無"
            private set
        @Volatile private var actionOverlay: GestureStatusOverlay? = null
        @Volatile private var actionCueEnabled = true

        /** Called only after Android accepts a real control command. */
        fun showAcceptedControlAction(command: FloatingControlRouter.Command) {
            if (actionCueEnabled) actionOverlay?.showControlAction(command)
        }

        fun showAcceptedPointerTap() {
            if (actionCueEnabled) actionOverlay?.showPointerClick()
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var connections: MultiPeerTransferManager
    private lateinit var overlay: GestureStatusOverlay
    private val power = AdaptivePowerPolicy()
    private val motionSampler = IdleMotionSampler()
    private val handFeedback = HandFeedbackPolicy()
    private var unified = FloatingControlRouter()
    // Gesture visual feedback is now event-driven, not a camera-frame trail.
    private var cursorShown = false
    private val tiltedPalm = TiltedPalmAssist(enabled = true)
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var recognizer: GestureRecognizer? = null
    // Reuse fallback RGBA row-padding storage on the single camera worker.
    // CameraX often provides a tightly packed direct ByteBuffer, requiring no
    // intermediate frame-sized ByteArray at all.
    private var rgbaScratch = ByteArray(0)
    private var sourceBitmap: Bitmap? = null // reusable on the single camera worker
    private val landmarkScratch = FloatArray(42)
    private val rotationMatrix = Matrix()
    private var lastHint = 0L
    private var isScreenOn = false
    private val unlockGate = CameraUnlockGate()
    private val keyguard by lazy { getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager }
    private val powerManager by lazy { getSystemService(Context.POWER_SERVICE) as PowerManager }
    private var cameraGeneration = 0L
    private var cameraBindPending = false
    private var cameraBound = false
    private var cameraResumeCheck: Runnable? = null
    private var cameraRetryCount = 0
    @Volatile private var gameSuspended = false
    @Volatile private var receivingOffer = false

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> suspendCameraForScreenOff()
                Intent.ACTION_SCREEN_ON -> {
                    isScreenOn = true
                    unlockGate.screenOn()
                    power.screenChanged(false)
                    // SCREEN_ON is often delivered before face unlock starts.
                    // Do not bind here. Wait for KEYGUARD to disappear.
                    cameraRetryCount = 0
                    if (!gameSuspended) scheduleUnlockCheck(1000L)
                }
                Intent.ACTION_USER_PRESENT -> {
                    // A genuine unlock has been signalled by Android. Keyguard
                    // is rechecked in the delayed callback; never trust the
                    // broadcast alone to access the camera.
                    if (isScreenOn && isActive && !gameSuspended) {
                        cameraRetryCount = 0
                        scheduleUnlockCheck(800L)
                    }
                }
            }
        }
    }

    private data class UnlockState(
        val interactive: Boolean,
        val keyguardLocked: Boolean,
        val deviceLocked: Boolean
    )
    private fun currentUnlockState(): UnlockState = try {
        UnlockState(powerManager.isInteractive, keyguard.isKeyguardLocked,
            keyguard.isDeviceLocked)
    } catch (_: Exception) {
        // Unknown lock state is treated as locked; never compete with face ID.
        UnlockState(false, true, true)
    }

    private fun mayBindCamera(): Boolean {
        val state = currentUnlockState()
        return unlockGate.canUseCamera(state.interactive,
            state.keyguardLocked, state.deviceLocked)
    }

    private fun cancelUnlockCheck() {
        cameraResumeCheck?.let { main.removeCallbacks(it) }
        cameraResumeCheck = null
    }

    /** Full camera release, not merely stopping ML inference. */
    private fun suspendCameraForScreenOff() {
        isScreenOn = false
        unlockGate.screenOff()
        cancelUnlockCheck()
        cameraGeneration++ // Invalidate any asynchronous ProcessCameraProvider listener
        cameraBindPending = false
        cameraBound = false
        cameraRetryCount = 0
        power.screenChanged(false)
        connections.setDiscoveryEnabled(false)
        provider?.unbindAll()
        overlay.dismissHand()
        GlobalGrabAccessibilityService.setCursor(null)
        overlay.dismissAction()
        cameraExecutor.execute {
            cursorShown = false
            handFeedback.reset()
            unified.reset()
        }
    }

    /** Game suspension fully unbinds the front camera and also pauses Nearby
     * discovery. Existing authenticated links and in-flight files stay alive. */
    private fun setGameSuspended(foregroundGame: Boolean) {
        val shouldPause = foregroundGame && FloatingSettings.pauseInGames(this)
        if (shouldPause == gameSuspended) return
        gameSuspended = shouldPause
        isPausedForGame = shouldPause
        if (shouldPause) {
            cancelUnlockCheck()
            cameraGeneration++ // stale CameraX bind futures must not revive camera
            cameraBindPending = false
            cameraBound = false
            cameraRetryCount = 0
            power.screenChanged(false)
            connections.setDiscoveryEnabled(false)
            provider?.unbindAll()
            overlay.dismissHand()
            overlay.dismissAction()
            GlobalGrabAccessibilityService.setCursor(null)
            GlobalGrabAccessibilityService.setPalm(null)
            cameraExecutor.execute {
                cursorShown = false
                handFeedback.reset()
                unified.reset()
            }
            lastGestureAction = "遊戲期間已暫停前鏡頭與手勢辨識"
        } else if (isActive && isScreenOn) {
            // Do not touch the camera until Android has completed face unlock.
            lastGestureAction = "已離開遊戲，等待恢復隔空操作"
            cameraRetryCount = 0
            scheduleUnlockCheck(700L)
        }
    }

    private fun scheduleUnlockCheck(delayMillis: Long) {
        cancelUnlockCheck()
        if (!isScreenOn || !isActive || gameSuspended) return
        val expectedGeneration = cameraGeneration
        val check = Runnable {
            cameraResumeCheck = null
            if (!isActive || !isScreenOn || gameSuspended ||
                cameraGeneration != expectedGeneration) return@Runnable
            val state = currentUnlockState()
            if (unlockGate.confirmUnlocked(state.interactive,
                    state.keyguardLocked, state.deviceLocked)) {
                // Allow face authentication to fully close its camera session.
                // This is only reached after Keyguard is genuinely gone.
                power.screenChanged(true)
                connections.setDiscoveryEnabled(FloatingSettings.nearby(this@GlobalGestureService))
                bindCamera()
            } else if (++cameraRetryCount <= 25) {
                // Infrequent checks handle devices that do not dispatch
                // ACTION_USER_PRESENT. Stop polling after ~50s; a subsequent
                // ACTION_USER_PRESENT or new screen cycle still resumes.
                scheduleUnlockCheck(2000L)
            }
        }
        cameraResumeCheck = check
        main.postDelayed(check, delayMillis)
    }

    override fun onCreate() {
        super.onCreate()
        reloadFloatingSettings()
        connections = MultiPeerTransferManager(applicationContext)
        activeInstance = WeakReference(this)
        overlay = GestureStatusOverlay(applicationContext)
        actionOverlay = overlay
        connections.onVisualEvent = { event -> overlay.showState(event) }
        connections.onReceiverOfferState = { awaiting ->
            if (receivingOffer != awaiting) {
                receivingOffer = awaiting
                power.setReceiverAwaiting(awaiting)
                cameraExecutor.execute { unified.reset() }
            }
        }
        isScreenOn = powerManager.isInteractive
        if (isScreenOn) unlockGate.screenOn() else unlockGate.screenOff()
        registerReceiver(screen, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        })
    }

    private fun reloadFloatingSettings() {
        power.setPowerMode(FloatingSettings.power(this))
        // Keep v1.0.3 battery cadence: a one-shot cue never boosts AI FPS.
        actionCueEnabled = FloatingSettings.actionFeedback(this)
        val dwell = FloatingSettings.dwellMs(this)
        val sensitivity = FloatingSettings.sensitivity(this)
        cameraExecutor.execute {
            unified = FloatingControlRouter(dwell, sensitivity)
            cursorShown = false
        }
        // Hide any old v1.0.3 palm marker after upgrading.
        GlobalGrabAccessibilityService.setPalm(null)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if(intent?.action == ACTION_SETTINGS_CHANGED) {
            if(isActive) {
                reloadFloatingSettings()
                setGameSuspended(GlobalGrabAccessibilityService.gameForeground)
                connections.setDiscoveryEnabled(!gameSuspended && isScreenOn &&
                    mayBindCamera() && FloatingSettings.nearby(this))
            }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_CONFIRM_RECEIVE && isActive) {
            connections.confirmReceive()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_START && isActive) {
            // Reopening the Activity must never restart camera or Nearby pairing.
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_CAPTURE_SCREEN && isActive) {
            main.postDelayed({
                val accessibility = GlobalGrabBridge.accessibility?.get()
                if (accessibility == null) {
                    connections.reportCaptureFailure("請先啟用無障礙抓取權限")
                } else accessibility.takeFallbackScreenshot()
            }, 850)
            return START_NOT_STICKY
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }
        foregroundNotification()
        lastGestureAction = "等待操作"
        actionCount = 0L
        lastRecognitionError = "無"
        isActive = true
        gameSuspended = FloatingSettings.pauseInGames(this) &&
            GlobalGrabAccessibilityService.gameForeground
        isPausedForGame = gameSuspended
        transfer = connections
        GlobalGrabBridge.onGrabResult = { result ->
            main.post {
                when (result) {
                    is GrabResult.Link -> connections.offerLink(result.url)
                    is GrabResult.ScreenSnapshot -> connections.offerFile(result.file,
                        "screen-capture-" + System.currentTimeMillis() + ".png")
                    is GrabResult.ShareStarted -> connections.reportShareStarted(result.detail)
                    is GrabResult.Unavailable -> connections.reportCaptureFailure(result.reason)
                }
            }
        }
        GlobalGrabBridge.onReceiveGesture = { main.post { connections.confirmReceive() } }
        connections.setDiscoveryEnabled(false)
        connections.start()
        power.start()
        // A foreground start from the unlocked dashboard may bind after a
        // short grace period. Starting while locked leaves the camera free.
        power.screenChanged(false)
        if (isScreenOn && !gameSuspended) scheduleUnlockCheck(700L)
        return START_NOT_STICKY
    }

    private fun foregroundNotification() {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(NotificationChannel(
            CHANNEL, "懸浮操作／前鏡頭監測", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 32,
            Intent(this, GlobalGestureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val screenshot = PendingIntent.getService(this, 34,
            Intent(this, GlobalGestureService::class.java).setAction(ACTION_CAPTURE_SCREEN),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 33,
            Intent(this, TransferActivity::class.java)
                .putExtra("airgrab_show_settings", true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("懸浮操作已啟用")
            .setContentText("控制與傳檔共用鏡頭；待機自動降頻")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(
                android.R.drawable.ic_menu_camera, "手動截圖備援", screenshot).build())
            .addAction(Notification.Action.Builder(
                android.R.drawable.ic_media_pause, "停止前鏡頭偵測", stop).build())
            .build()
        startForeground(NOTIFICATION, n)
    }

    private fun preferredCameraFps(): Range<Int>? {
        return try {
            val mgr = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = mgr.cameraIdList.firstOrNull {
                mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_FRONT
            } ?: return null
            val ranges = mgr.getCameraCharacteristics(id)
                .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?: return null
            // Only request an actually advertised range; many models have no <=15 fps option.
            ranges.filter { it.upper <= 15 && it.lower > 0 }.minByOrNull { it.upper }
        } catch (_: Exception) { null }
    }

    private fun bindCamera() {
        if (cameraBindPending || cameraBound || !isActive || !isScreenOn ||
            gameSuspended || !mayBindCamera()) return
        val generation = cameraGeneration
        cameraBindPending = true
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (cameraGeneration != generation || !isActive || !isScreenOn ||
                gameSuspended || !mayBindCamera()) {
                if (cameraGeneration == generation) cameraBindPending = false
                return@addListener
            }
            try {
                if (recognizer == null) {
                    recognizer = GestureRecognizer.createFromOptions(this,
                        GestureRecognizer.GestureRecognizerOptions.builder()
                            .setBaseOptions(BaseOptions.builder()
                                .setModelAssetPath("gesture_recognizer.task").build())
                            .setRunningMode(RunningMode.VIDEO)
                            .setNumHands(1)
                            .setMinHandDetectionConfidence(.52f)
                            .setMinHandPresenceConfidence(.52f)
                            .setMinTrackingConfidence(.50f)
                            .build())
                }
                val b = ImageAnalysis.Builder()
                    .setTargetResolution(Size(480, 360))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                preferredCameraFps()?.let { advertised ->
                    requestedSensorFps = advertised.toString()
                    Camera2Interop.Extender(b).setCaptureRequestOption(
                        CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, advertised)
                }
                val analysis = b.build()
                analysis.setAnalyzer(cameraExecutor) { frame ->
                    try { infer(frame) } catch (error: Exception) {
                        lastRecognitionError = error.javaClass.simpleName +
                            ": " + (error.message?.take(90) ?: "unknown")
                        android.util.Log.w("FloatingControl", "手勢辨識錯誤", error)
                    } finally { frame.close() }
                }
                provider = future.get()
                provider?.unbindAll()
                provider?.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
                cameraBound = true
                cameraBindPending = false
                cameraRetryCount = 0
            } catch (_: Exception) {
                // A competing camera user (including face unlock) must not
                // destroy the Nearby service. Retry a few times after unlock.
                cameraBindPending = false
                cameraBound = false
                provider?.unbindAll()
                if (cameraGeneration == generation && isActive && isScreenOn &&
                    !gameSuspended && cameraRetryCount++ < 3) {
                    scheduleUnlockCheck(1500L)
                } else {
                    connections.reportCaptureFailure(
                        "前鏡頭暫時無法使用，傳檔連線仍在；請解鎖後重試")
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun infer(frame: ImageProxy) {
        if (gameSuspended) return
        framesSeen++
        val now = SystemClock.uptimeMillis()
        val movement = if (power.isDeepIdle(now)) {
            motionSampler.observe(frame, now)
        } else {
            motionSampler.reset()
            false
        }
        if (!power.shouldInfer(now, movement)) return
        inferences++
        val image = frameToBitmap(frame)
        try {
            val result = recognizer?.recognizeForVideo(BitmapImageBuilder(image).build(), now)
                ?: return
            val best = result.gestures().firstOrNull()?.maxByOrNull { it.score() }
            val name = best?.categoryName()
            val score = best?.score() ?: 0f
            val marks = result.landmarks().firstOrNull()
            val handSeen = marks?.isNotEmpty() == true ||
                name == "Open_Palm" || name == "Closed_Fist"
            power.observeHand(handSeen, now)
            // Tilt fallback uses existing landmarks, not another ML inference.
            val xy = if (marks != null && marks.size >= 21) {
                for (i in 0 until 21) {
                    landmarkScratch[2 * i] = marks[i].x()
                    landmarkScratch[2 * i + 1] = marks[i].y()
                }
                landmarkScratch
            } else null
            val tilt = tiltedPalm.resolve(name, score, xy, now)
            val pose = if (tilt.useLandmarkFallback) "Open_Palm"
                else if (score >= .58f) name ?: "" else ""
            val trustedScore = if (tilt.useLandmarkFallback) .65f else score
            lastPose = (if (pose.isBlank()) "未分類" else pose) +
                " " + (trustedScore * 100f).toInt() + "%"
            handFeedback.observe(handSeen, pose, now)?.let(overlay::showHandFeedback)
            val px = if (marks != null && marks.size > 17)
                1f - (marks[0].x() + marks[5].x() + marks[9].x() +
                    marks[13].x() + marks[17].x()) / 5f else null
            val py = if (marks != null && marks.size > 17)
                (marks[0].y() + marks[5].y() + marks[9].y() +
                    marks[13].y() + marks[17].y()) / 5f else null
            val index = if (pose == "Pointing_Up" && marks != null &&
                marks.size > 8) marks[8] else null
            val output = unified.accept(pose, trustedScore, px, py,
                index?.let { 1f - it.x() }, index?.y(), now, receivingOffer,
                GlobalGrabAccessibilityService.isYouTubeForeground())
            when (val command = output.command) {
                FloatingControlRouter.Command.SEND -> {
                    actionCount++
                    if (!connections.isConnected) {
                        lastGestureAction = "無法傳送：尚未連線附近裝置"
                        connections.reportCaptureFailure(
                            "尚未連線附近裝置，請在兩台手機啟用懸浮操作，完成配對後再試")
                    } else {
                        lastGestureAction = "張掌→握拳抓取（已送至分享服務）"
                        overlay.showState(VisualEvent(VisualPhase.CAPTURING, "正在抓取目前內容…"))
                        GlobalGrabBridge.onGrabGesture()
                    }
                }
                FloatingControlRouter.Command.RECEIVE -> {
                    actionCount++
                    lastGestureAction = "握拳→張掌接收（已送至傳檔服務）"
                    GlobalGrabBridge.onReleaseGesture()
                }
                null -> Unit
                else -> {
                    actionCount++
                    lastGestureAction = "隔空操作指令：" + command.name
                    GlobalGrabAccessibilityService.sendControl(command)
                }
            }
            // The old code posted null overlay updates to the main thread on
            // every recognition. Hide only once when a visible pointer leaves.
            if (output.pointer != null) {
                GlobalGrabAccessibilityService.setCursor(output.pointer)
                cursorShown = true
            } else if (cursorShown) {
                GlobalGrabAccessibilityService.setCursor(null)
                cursorShown = false
            }
            // Telemetry deliberately stores no frame or hand identity.
            if (now - lastHint > 1500L) lastHint = now
        } finally { if (image !== sourceBitmap) image.recycle() }
    }

    private fun frameToBitmap(proxy: ImageProxy): Bitmap {
        val w = proxy.width
        val h = proxy.height
        val plane = proxy.planes[0]
        val src = plane.buffer.duplicate().apply { position(0) }
        val packedBytes = w * h * 4
        val pixelStride = plane.pixelStride
        val rgba = if (pixelStride == 4 && plane.rowStride == w * 4 &&
            src.remaining() >= packedBytes) {
            // Zero extra Java array allocations: Bitmap copies directly from
            // CameraX's read-only image plane on this worker before frame.close.
            src
        } else {
            if (rgbaScratch.size != packedBytes) rgbaScratch = ByteArray(packedBytes)
            for (y in 0 until h) {
                if (pixelStride == 4) {
                    src.position(y * plane.rowStride)
                    src.get(rgbaScratch, y * w * 4, w * 4)
                } else {
                    // Rare devices with padded pixels still produce RGBA.
                    for (x in 0 until w) {
                        val srcIndex = y * plane.rowStride + x * pixelStride
                        val dstIndex = (y * w + x) * 4
                        for (channel in 0 until 4) {
                            rgbaScratch[dstIndex + channel] = src.get(srcIndex + channel)
                        }
                    }
                }
            }
            ByteBuffer.wrap(rgbaScratch)
        }
        val image = sourceBitmap?.takeIf {
            !it.isRecycled && it.width == w && it.height == h
        } ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            sourceBitmap?.recycle()
            sourceBitmap = it
        }
        image.copyPixelsFromBuffer(rgba)
        val rotation = proxy.imageInfo.rotationDegrees
        if (rotation == 0) return image
        return try {
            rotationMatrix.reset()
            rotationMatrix.postRotate(rotation.toFloat())
            Bitmap.createBitmap(image, 0, 0, w, h, rotationMatrix, false)
        } catch (e: Exception) {
            throw e
        }
    }

    override fun onDestroy() {
        if (activeInstance?.get() === this) activeInstance = null
        isPausedForGame = false
        gameSuspended = false
        cancelUnlockCheck()
        cameraGeneration++
        isActive = false
        transfer = null
        actionOverlay = null
        GlobalGrabBridge.onGrabResult = null
        GlobalGrabBridge.onReceiveGesture = null
        connections.onVisualEvent = null
        connections.onReceiverOfferState = null
        power.setReceiverAwaiting(false)
        connections.stop()
        overlay.dismiss()
        overlay.dismissHand()
        overlay.dismissAction()
        GlobalGrabAccessibilityService.setCursor(null)
        GlobalGrabAccessibilityService.setPalm(null)
        power.stop()
        provider?.unbindAll()
        try { unregisterReceiver(screen) } catch (_: Exception) {}
        val closingRecognizer = recognizer
        recognizer = null
        cameraExecutor.execute {
            closingRecognizer?.close()
            sourceBitmap?.recycle()
            sourceBitmap = null
        }
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}
