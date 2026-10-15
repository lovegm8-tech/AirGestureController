package tw.airgesture.wakeprobe

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
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Explicit opt-in, short-lived experiment. Never records camera images.
 * Camera is intentionally released BEFORE requesting screen wake so Samsung
 * face unlock can acquire the front camera after the screen becomes lit.
 */
class WakeProbeService : LifecycleService() {
    companion object {
        const val START = "tw.airgesture.wakeprobe.START"
        const val STOP = "tw.airgesture.wakeprobe.STOP"
        private const val CHANNEL = "wake_probe_camera"
        private const val NOTICE_ID = 2052
        private const val MAX_TRIAL_MS = 8 * 60 * 1000L

        @Volatile var running = false
            private set
        @Volatile var status = "尚未啟動"
            private set
    }

    private val main = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val engine = WakeGestureEngine()
    private val assist = TiltedPalmAssist(enabled = true)
    private val power by lazy { getSystemService(POWER_SERVICE) as PowerManager }
    private val lock by lazy { getSystemService(KEYGUARD_SERVICE) as KeyguardManager }
    private var cpuWake: PowerManager.WakeLock? = null
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var recognizer: GestureRecognizer? = null
    private var converter = CameraBitmapConverter()
    private var previousAnalysisMs = -1L
    private var offFrames = 0
    private var monitorEnabled = false
    private var bound = false
    private var receiverRegistered = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    ProbeLog.add(this@WakeProbeService, "螢幕關閉；開始觀察前鏡頭是否還有畫面")
                    status = "熄屏監測中（需要手掌保持約 1 秒）"
                    engine.screenOff()
                    offFrames = 0
                    monitorEnabled = true
                }
                Intent.ACTION_SCREEN_ON -> {
                    ProbeLog.add(this@WakeProbeService, "偵測到螢幕亮起")
                    if (!lock.isKeyguardLocked) afterUnlock()
                    else status = "亮屏但尚未解鎖；等待三星臉部辨識"
                }
                Intent.ACTION_USER_PRESENT -> {
                    ProbeLog.add(this@WakeProbeService, "Android 回報已解鎖")
                    afterUnlock()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "張掌喚醒實驗", NotificationManager.IMPORTANCE_LOW)
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
        if (intent?.action != START || running) {
            if (intent?.action != START) stopSelf()
            return START_NOT_STICKY
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            status = "缺少相機權限"
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this, NOTICE_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } catch (e: Exception) {
            ProbeLog.add(this, "前景相機服務啟動失敗：${e.javaClass.simpleName}")
            status = "啟動失敗"
            stopSelf()
            return START_NOT_STICKY
        }

        running = true
        monitorEnabled = true
        status = "模型載入中"
        ProbeLog.add(this, "實驗已啟動，最多監測 8 分鐘；沒有錄影與影像儲存")
        // Keep CPU awake while the screen is off; never indefinitely.
        try {
            cpuWake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WakeProbe:TestCpu").also {
                it.acquire(MAX_TRIAL_MS)
            }
            ProbeLog.add(this, "測試用部分喚醒鎖已啟用（自動到期）")
        } catch (e: Exception) {
            ProbeLog.add(this, "CPU 喚醒鎖失敗：${e.javaClass.simpleName}")
        }
        main.postDelayed({
            if (running) {
                ProbeLog.add(this, "已達 8 分鐘實驗上限，自動停止")
                stopSelf()
            }
        }, MAX_TRIAL_MS)
        executor.execute {
            try {
                val base = BaseOptions.builder().setModelAssetPath("gesture_recognizer.task").build()
                val options = GestureRecognizer.GestureRecognizerOptions.builder()
                    .setBaseOptions(base)
                    .setRunningMode(RunningMode.VIDEO)
                    .setNumHands(1)
                    .setMinHandDetectionConfidence(.60f)
                    .setMinHandPresenceConfidence(.60f)
                    .setMinTrackingConfidence(.60f)
                    .build()
                recognizer = GestureRecognizer.createFromOptions(applicationContext, options)
                main.post { if (running) bindCamera() }
            } catch (e: Exception) {
                main.post { reportError("模型初始化失敗", e) }
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val stop = PendingIntent.getService(this, 2,
            Intent(this, WakeProbeService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val launch = PendingIntent.getActivity(this, 3,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("張掌喚醒：正在進行相機實驗")
            .setContentText("最多 8 分鐘 · 熄屏偵測可能受系統阻止")
            .setContentIntent(launch)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_pause, "停止實驗", stop)
            .build()
    }

    private fun bindCamera() {
        if (!running || bound || recognizer == null) return
        try {
            val future = ProcessCameraProvider.getInstance(this)
            future.addListener({
                if (!running || bound) return@addListener
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
                            if (running && monitorEnabled) analyze(image)
                        } catch (e: Exception) {
                            ProbeLog.add(this, "影像分析錯誤：${e.javaClass.simpleName}")
                            Log.e("WakeProbe", "analyzer failure", e)
                        } finally {
                            image.close()
                        }
                    }
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, imageAnalysis)
                    analysis = imageAnalysis
                    bound = true
                    status = if (power.isInteractive) "前鏡頭正常；請切回影片再熄屏" else "熄屏相機已要求啟動"
                    ProbeLog.add(this, "CameraX 已綁定前鏡頭")
                } catch (e: Exception) {
                    reportError("前鏡頭綁定失敗", e)
                }
            }, ContextCompat.getMainExecutor(this))
        } catch (e: Exception) {
            reportError("無法取得相機", e)
        }
    }

    private fun analyze(proxy: ImageProxy) {
        val now = SystemClock.uptimeMillis()
        val period = if (!power.isInteractive) 130L else 120L
        if (previousAnalysisMs >= 0 && now - previousAnalysisMs < period) return
        previousAnalysisMs = now

        val frame = converter.convert(proxy)
        try {
            val result = recognizer?.recognizeForVideo(BitmapImageBuilder(frame).build(), now) ?: return
            val category = result.gestures().firstOrNull()?.maxByOrNull { it.score() }
            val name = category?.categoryName()
            val score = category?.score() ?: 0f
            val landmarks = result.landmarks().firstOrNull()
            val xy = if (landmarks != null && landmarks.size >= 21) {
                FloatArray(42).also { pts ->
                    for (i in 0 until 21) {
                        pts[i * 2] = landmarks[i].x()
                        pts[i * 2 + 1] = landmarks[i].y()
                    }
                }
            } else null
            val fallback = assist.resolve(name, score, xy, now)
            val shape = when {
                name == "Open_Palm" && score >= .65f -> WakeGestureEngine.Shape.PALM
                fallback.useLandmarkFallback -> WakeGestureEngine.Shape.PALM
                name == "Closed_Fist" && score >= .65f -> WakeGestureEngine.Shape.FIST
                else -> WakeGestureEngine.Shape.OTHER
            }

            if (!power.isInteractive) {
                offFrames++
                if (offFrames == 1 || offFrames % 20 == 0) {
                    ProbeLog.add(this, "熄屏期間實際收到第 ${offFrames} 張相機分析畫面")
                }
            }

            val action = engine.accept(shape, now)
            when (action) {
                WakeGestureEngine.Action.ATTEMPT_WAKE -> main.post { attemptWake() }
                WakeGestureEngine.Action.TAP_TO_RESUME -> main.post {
                    if (!lock.isKeyguardLocked && power.isInteractive) {
                        if (MediaGestureAccessibilityService.tapCenter())
                            ProbeLog.add(this, "偵測到解鎖後張掌→握拳；已要求中央點擊")
                        else ProbeLog.add(this, "辨識到張掌→握拳，但尚未授權無障礙服務")
                        status = "實驗已完成；可查看結果並停止"
                    } else ProbeLog.add(this, "辨識到手勢，但目前仍鎖定，略過點擊")
                }
                null -> Unit
            }
        } finally {
            converter.release(frame)
        }
    }

    private fun attemptWake() {
        if (!running || power.isInteractive) return
        monitorEnabled = false
        status = "張掌成功！釋放前鏡頭後嘗試亮屏"
        ProbeLog.add(this, "熄屏張掌持續達門檻；準備亮屏；先釋放鏡頭供臉部辨識")
        unbindCamera()
        main.postDelayed({
            if (!running) return@postDelayed
            val perm = ContextCompat.checkSelfPermission(this, "android.permission.TURN_SCREEN_ON")
            ProbeLog.add(this, "亮屏特殊權限：${if (perm == PackageManager.PERMISSION_GRANTED) "已授權" else "未授權或不適用"}")
            try {
                // Deprecated wake flag is deliberately tested as a fallback:
                // Android 14+ may require TURN_SCREEN_ON special access.
                @Suppress("DEPRECATION")
                val wake = power.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "WakeProbe:ScreenOnAttempt"
                )
                wake.acquire(2500L)
                main.postDelayed({ if (wake.isHeld) wake.release() }, 2100L)
                ProbeLog.add(this, "已送出 Android 亮屏喚醒請求")
                main.postDelayed({
                    ProbeLog.add(this, "亮屏結果：${if (power.isInteractive) "螢幕已亮" else "仍為熄屏（可能被系統拒絕）"}")
                }, 1100L)
            } catch (e: Exception) {
                ProbeLog.add(this, "亮屏請求失敗：${e.javaClass.simpleName}")
                status = "亮屏請求遭系統限制，請手動點亮查看紀錄"
            }
        }, 250L)
    }

    private fun afterUnlock() {
        if (!running || !power.isInteractive || lock.isKeyguardLocked) return
        if (engine.state == WakeGestureEngine.State.WAITING_RESUME_PALM ||
            engine.state == WakeGestureEngine.State.WAITING_FIST ||
            engine.state == WakeGestureEngine.State.COMPLETED) return
        engine.unlocked()
        status = "已解鎖；等待張掌→握拳以繼續播放影片"
        ProbeLog.add(this, "開始解鎖後手勢測試，等待張掌再握拳")
        monitorEnabled = true
        // Face unlock and CameraX can contend for the only front camera.
        main.postDelayed({ if (running) bindCamera() }, 1300L)
    }

    private fun unbindCamera() {
        try {
            analysis?.clearAnalyzer()
            provider?.unbindAll()
        } catch (e: Exception) {
            ProbeLog.add(this, "相機釋放異常：${e.javaClass.simpleName}")
        }
        analysis = null
        bound = false
    }

    private fun reportError(prefix: String, error: Exception) {
        ProbeLog.add(this, "$prefix：${error.javaClass.simpleName} (${error.message?.take(80) ?: ""})")
        status = prefix
        Log.e("WakeProbe", prefix, error)
    }

    override fun onDestroy() {
        running = false
        monitorEnabled = false
        if (receiverRegistered) {
            try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
            receiverRegistered = false
        }
        unbindCamera()
        try { cpuWake?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        if (!executor.isShutdown) {
            executor.execute {
                try { recognizer?.close() } catch (_: Exception) {}
                recognizer = null
                converter.close()
            }
            executor.shutdown()
        }
        status = "已停止"
        ProbeLog.add(this, "實驗服務已停止，釋放相機與喚醒鎖")
        super.onDestroy()
    }
}

private class CameraBitmapConverter {
    private var source: Bitmap? = null
    private var rowBuffer: ByteArray? = null
    private val matrix = Matrix()

    fun convert(proxy: ImageProxy): Bitmap {
        val width = proxy.width
        val height = proxy.height
        val bitmap = source?.takeIf { !it.isRecycled && it.width == width && it.height == height }
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                source?.recycle()
                source = it
            }
        val plane = proxy.planes[0]
        require(plane.pixelStride == 4)
        val buffer = plane.buffer.duplicate().apply { rewind() }
        val rowBytes = width * 4
        if (plane.rowStride == rowBytes) {
            bitmap.copyPixelsFromBuffer(buffer)
        } else {
            val count = rowBytes * height
            val bytes = rowBuffer?.takeIf { it.size == count } ?: ByteArray(count).also { rowBuffer = it }
            for (row in 0 until height) {
                buffer.position(row * plane.rowStride)
                buffer.get(bytes, row * rowBytes, rowBytes)
            }
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
        }
        val rotation = proxy.imageInfo.rotationDegrees
        if (rotation == 0) return bitmap
        matrix.reset()
        matrix.postRotate(rotation.toFloat())
        return Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true)
    }

    fun release(bitmap: Bitmap) {
        if (bitmap !== source && !bitmap.isRecycled) bitmap.recycle()
    }

    fun close() {
        source?.recycle()
        source = null
        rowBuffer = null
    }
}
