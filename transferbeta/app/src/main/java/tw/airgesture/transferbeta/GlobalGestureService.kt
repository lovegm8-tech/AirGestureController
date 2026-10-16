package tw.airgesture.transferbeta

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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
import tw.airgesture.transferbeta.core.VisualEvent
import tw.airgesture.transferbeta.core.VisualPhase
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * Foreground camera service, started ONLY from visible TransferActivity.
 * ECO mode throttles ML inference; it cannot guarantee tiny camera sensor current.
 * Stops camera on screen off; manual user start is required if Android kills the service.
 */
class GlobalGestureService : LifecycleService() {
    companion object {
        const val ACTION_START = "tw.airgesture.transferbeta.START_GLOBAL"
        const val ACTION_STOP = "tw.airgesture.transferbeta.STOP_GLOBAL"
        const val ACTION_CAPTURE_SCREEN = "tw.airgesture.transferbeta.EXPLICIT_SCREEN_CAPTURE"
        const val ACTION_CONFIRM_RECEIVE = "tw.airgesture.transferbeta.CONFIRM_RECEIVE"
        private const val CHANNEL = "global_grab_camera"
        private const val NOTIFICATION = 4108
        @Volatile var isActive = false
            private set
        @Volatile var transfer: BackgroundTransferManager? = null
            private set
        @Volatile var framesSeen = 0L
            private set
        @Volatile var inferences = 0L
            private set
        @Volatile var requestedSensorFps = "由裝置決定"
            private set
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var connections: BackgroundTransferManager
    private lateinit var overlay: GestureStatusOverlay
    private val power = AdaptivePowerPolicy()
    private val sequence = GestureSequence(holdMillis = 240L, unknownGraceMillis = 550L)
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var recognizer: GestureRecognizer? = null
    private var lastHint = 0L
    private var isScreenOn = true
    @Volatile private var receivingOffer = false

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenOn = false
                    power.screenChanged(false)
                    provider?.unbindAll()
                }
                Intent.ACTION_SCREEN_ON -> {
                    isScreenOn = true
                    power.screenChanged(true)
                    if (isActive) bindCamera()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        connections = BackgroundTransferManager(applicationContext)
        overlay = GestureStatusOverlay(applicationContext)
        connections.onVisualEvent = { event -> overlay.showState(event) }
        connections.onReceiverOfferState = { awaiting ->
            if (receivingOffer != awaiting) {
                receivingOffer = awaiting
                power.setReceiverAwaiting(awaiting)
                cameraExecutor.execute { sequence.reset() }
            }
        }
        registerReceiver(screen, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_CONFIRM_RECEIVE && isActive) {
            connections.confirmReceive()
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
        isActive = true
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
        connections.start()
        power.start()
        if (isScreenOn) bindCamera()
        return START_NOT_STICKY
    }

    private fun foregroundNotification() {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(NotificationChannel(
            CHANNEL, "隔空抓取／前鏡頭監測", NotificationManager.IMPORTANCE_LOW))
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
            .setContentTitle("隔空抓取已啟用")
            .setContentText("低頻辨識中；偵測到手掌才切換快速辨識")
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
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (!isActive || !isScreenOn) return@addListener
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
                    try { infer(frame) } catch (_: Exception) { /* fail closed */ }
                    finally { frame.close() }
                }
                provider = future.get()
                provider?.unbindAll()
                provider?.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
            } catch (_: Exception) {
                // Camera ownership / background restrictions require user-visible recovery.
                isActive = false
                stopSelf()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun infer(frame: ImageProxy) {
        framesSeen++
        val now = SystemClock.uptimeMillis()
        if (!power.shouldInfer(now)) return
        inferences++
        val image = frameToBitmap(frame)
        try {
            val result = recognizer?.recognizeForVideo(BitmapImageBuilder(image).build(), now)
                ?: return
            val best = result.gestures().firstOrNull()?.maxByOrNull { it.score() }
            val shape = if ((best?.score() ?: 0f) >= .58f)
                best?.categoryName() ?: "" else ""
            val handSeen = result.landmarks().firstOrNull()?.isNotEmpty() == true ||
                shape == "Open_Palm" || shape == "Closed_Fist"
            power.observeHand(handSeen, now)
            when (sequence.accept(shape, now)) {
                GestureSequence.Result.SEND -> {
                    overlay.showState(VisualEvent(VisualPhase.CAPTURING, "正在抓取目前內容…"))
                    GlobalGrabBridge.onGrabGesture()
                }
                GestureSequence.Result.RECEIVE -> GlobalGrabBridge.onReleaseGesture()
                else -> Unit
            }
            // Telemetry deliberately stores no frame or hand identity.
            if (now - lastHint > 1500L) lastHint = now
        } finally { image.recycle() }
    }

    private fun frameToBitmap(proxy: ImageProxy): Bitmap {
        val w = proxy.width
        val h = proxy.height
        val plane = proxy.planes[0]
        val src = proxy.planes[0].buffer.duplicate()
        val row = ByteArray(w * 4)
        val arr = ByteArray(w * h * 4)
        for (y in 0 until h) {
            src.position(y * plane.rowStride)
            src.get(row)
            System.arraycopy(row, 0, arr, y * row.size, row.size)
        }
        val image = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        image.copyPixelsFromBuffer(ByteBuffer.wrap(arr))
        val rotation = proxy.imageInfo.rotationDegrees
        if (rotation == 0) return image
        return try {
            Bitmap.createBitmap(image, 0, 0, w, h,
                Matrix().apply { postRotate(rotation.toFloat()) }, false)
        } finally { image.recycle() }
    }

    override fun onDestroy() {
        isActive = false
        transfer = null
        GlobalGrabBridge.onGrabResult = null
        GlobalGrabBridge.onReceiveGesture = null
        connections.onVisualEvent = null
        connections.onReceiverOfferState = null
        power.setReceiverAwaiting(false)
        connections.stop()
        overlay.dismiss()
        power.stop()
        provider?.unbindAll()
        try { unregisterReceiver(screen) } catch (_: Exception) {}
        recognizer?.close()
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}
