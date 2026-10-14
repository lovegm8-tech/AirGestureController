package tw.airgesture.controller.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import tw.airgesture.controller.MainActivity
import tw.airgesture.controller.core.Settings
import tw.airgesture.controller.vision.GestureCameraAnalyzer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Started from visible Activity to comply with Android 14+ camera FGS restrictions. */
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

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var provider: ProcessCameraProvider? = null
    private var analyzer: GestureCameraAnalyzer? = null
    private var cameraRequested = false

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "隔空手勢控制", NotificationManager.IMPORTANCE_LOW).apply {
                description = "前鏡頭正在辨識手勢，可隨時停止"
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action != START) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            status = "需要相機權限"
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } catch (e: Exception) {
            status = "啟動失敗：${e.javaClass.simpleName}"
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        status = "準備前鏡頭與辨識模型…"
        if (!cameraRequested) startCamera()
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val stopIntent = Intent(this, CameraGestureService::class.java).setAction(STOP)
        val stop = PendingIntent.getService(this, 10, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val openIntent = PendingIntent.getActivity(this, 11,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("隔空手勢辨識運作中")
            .setContentText("前鏡頭本機辨識中 · 點選停止可立即關閉")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_pause, "停止辨識", stop)
            .build()
    }

    private fun startCamera() {
        cameraRequested = true
        executor.execute {
            try {
                val engine = GestureCameraAnalyzer(this, Settings.engineOptions(this), Settings.performanceMode(this), Settings.tiltedPalmAssist(this))
                analyzer = engine
                main.post {
                    if (!running) return@post
                    val future = ProcessCameraProvider.getInstance(this)
                    future.addListener({
                        try {
                            if (!running) return@addListener
                            val camera = future.get()
                            provider = camera
                            val analysis = ImageAnalysis.Builder()
                                .setTargetResolution(Size(640, 480))
                                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()
                            analysis.setAnalyzer(executor) { image ->
                                try {
                                    if (running) engine.process(image,
                                        onAction = { action -> GestureAccessibilityService.send(action) },
                                        onPalmDetection = { open -> GestureAccessibilityService.handDetected(open) },
                                        onPointer = { result -> GestureAccessibilityService.pointerUpdated(result) }
                                    )
                                } catch (error: Exception) {
                                    Log.e("AirGesture", "Frame recognition failed", error)
                                } finally { image.close() }
                            }
                            camera.unbindAll()
                            camera.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
                            status = "辨識中（前鏡頭已啟動）"
                            GestureAccessibilityService.showIndicator()
                        } catch (e: Exception) {
                            fail("相機無法開啟", e)
                        }
                    }, ContextCompat.getMainExecutor(this))
                }
            } catch (e: Exception) {
                main.post { fail("辨識模型初始化失敗", e) }
            }
        }
    }

    private fun fail(message: String, error: Exception) {
        Log.e("AirGesture", message, error)
        status = "$message：${error.javaClass.simpleName}"
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        GestureAccessibilityService.hideIndicator()
        if (status.startsWith("辨識中") || status.startsWith("準備")) status = "已停止"
        provider?.unbindAll()
        provider = null
        // Tasks are serial: close the recognizer after any frame currently being analyzed.
        if (!executor.isShutdown) {
            executor.execute {
                try { analyzer?.close() } catch (ignored: Exception) {
                    Log.w("AirGesture", "Model close failed", ignored)
                }
                analyzer = null
            }
            executor.shutdown()
        }
        super.onDestroy()
    }
}
