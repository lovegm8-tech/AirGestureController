package tw.airgesture.controller.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import tw.airgesture.controller.core.AdaptiveFrameScheduler
import tw.airgesture.controller.core.GestureDecisionEngine
import tw.airgesture.controller.core.HandVisualState
import tw.airgesture.controller.core.HandVisualClassifier
import tw.airgesture.controller.core.PointerDwellEngine
import tw.airgesture.controller.core.PalmPositionTracker
import tw.airgesture.controller.core.TiltedPalmAssist
import tw.airgesture.controller.core.YouTubeGestureRouter
import tw.airgesture.controller.core.WakeDiagnosticLog
import java.nio.ByteBuffer

/** CameraX feeds a single analysis executor. Inference is local and throttled. */
class GestureCameraAnalyzer(
    context: Context,
    options: GestureDecisionEngine.Options,
    performanceMode: Int = 1,
    angleAssistEnabled: Boolean = false,
    pointerDwellMs: Long = 2000L,
    smartLitPower: Boolean = false,
    palmPositionOverlayEnabled: Boolean = true,
    motionStandbyEnabled: Boolean = false
) : AutoCloseable {
    private val decision = GestureDecisionEngine(options)
    private val palmTracker: PalmPositionTracker? = if (palmPositionOverlayEnabled)
        PalmPositionTracker() else null
    private val youtubeRouter = YouTubeGestureRouter()
    private val pointer = PointerDwellEngine(pointerDwellMs)
    private val tiltAssist = TiltedPalmAssist(angleAssistEnabled)
    private val useAngleAssist = angleAssistEnabled
    private val useFastSwipeAssist = options.fastSwipeAssistEnabled
    private val appContext = context.applicationContext
    private val motionSentinel = if (smartLitPower && motionStandbyEnabled)
        IdleMotionSampler() else null
    private var statStartedAt = Long.MIN_VALUE
    private var statFrameCount = 0L
    private var statInferenceCount = 0L
    private var statMotionWakeCount = 0L
    private val scheduler = AdaptiveFrameScheduler(
        AdaptiveFrameScheduler.Mode.fromIndex(performanceMode), smartLitPower,
        motionStandbyEnabled
    )
    private val converter = ReusableFrameConverter()
    private val recognizer: GestureRecognizer
    private val wakeTiltAssist = TiltedPalmAssist(true)
    private var lastWakeScanMs = Long.MIN_VALUE
    private var lastWakeHandMs = Long.MIN_VALUE

    init {
        val base = BaseOptions.builder().setModelAssetPath("gesture_recognizer.task").build()
        val config = GestureRecognizer.GestureRecognizerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.VIDEO)
            .setNumHands(1)
            .setMinHandDetectionConfidence(.60f)
            .setMinHandPresenceConfidence(.60f)
            .setMinTrackingConfidence(.60f)
            .build()
        recognizer = GestureRecognizer.createFromOptions(context.applicationContext, config)
    }

    fun process(
        proxy: ImageProxy,
        onAction: (GestureDecisionEngine.Action) -> Unit,
        onHandState: (HandVisualState) -> Unit,
        onPointer: (PointerDwellEngine.Result?) -> Unit,
        onPalmPosition: (PalmPositionTracker.Snapshot?) -> Unit = {},
        isYouTubeForeground: () -> Boolean = { false }
    ) {
        val now = SystemClock.uptimeMillis()
        if (statStartedAt == Long.MIN_VALUE) statStartedAt = now
        statFrameCount++
        val movement = if (motionSentinel != null && scheduler.isDeepIdle(now)) {
            motionSentinel.observe(proxy, now)
        } else {
            motionSentinel?.reset()
            false
        }
        if (movement) statMotionWakeCount++
        if (now - statStartedAt >= 300_000L && now >= statStartedAt) {
            // Count-only, once every 5 min. No photos or gestures are saved.
            WakeDiagnosticLog.add(appContext,
                "亮屏省電5分鐘：收到影格=$statFrameCount；完整AI辨識=$statInferenceCount；動態加速=$statMotionWakeCount")
            statStartedAt = now
            statFrameCount = 0
            statInferenceCount = 0
            statMotionWakeCount = 0
        }
        if (!scheduler.shouldAnalyze(now, movement)) return
        statInferenceCount++

        // Do not allocate/copy a Bitmap at all when a frame is skipped.
        val frame = converter.convert(proxy)
        try {
            val result = recognizer.recognizeForVideo(BitmapImageBuilder(frame).build(), now)
            val category = result.gestures().firstOrNull()?.maxByOrNull { it.score() }
            val landmarks = result.landmarks().firstOrNull()
            val foundHand = landmarks != null && landmarks.isNotEmpty()
            val categoryName = category?.categoryName()
            val originalShape = when (categoryName) {
                "Open_Palm" -> GestureDecisionEngine.Shape.OPEN_PALM
                "Closed_Fist" -> GestureDecisionEngine.Shape.CLOSED_FIST
                null -> GestureDecisionEngine.Shape.NO_HAND
                else -> GestureDecisionEngine.Shape.OTHER
            }
            // Wrist + four knuckles, avoiding per-frame list allocations.
            val palmY = if (landmarks != null && landmarks.size > 17) {
                (landmarks[0].y() + landmarks[5].y() + landmarks[9].y() +
                    landmarks[13].y() + landmarks[17].y()) / 5f
            } else null
            val score = category?.score() ?: 0f
            // Only when opted in, derive roll-independent geometry from 21 landmarks.
            // High-confidence model results keep precedence; no secondary inference pass.
            val xy = if (useAngleAssist && landmarks != null && landmarks.size >= 21) {
                FloatArray(42).also { coordinates ->
                    for (i in 0 until 21) {
                        coordinates[i * 2] = landmarks[i].x()
                        coordinates[i * 2 + 1] = landmarks[i].y()
                    }
                }
            } else null
            val angleResult = tiltAssist.resolve(categoryName, score, xy, now)
            scheduler.onAnalyzed(now, foundHand)
            val shape = when {
                angleResult.useLandmarkFallback -> GestureDecisionEngine.Shape.OPEN_PALM
                // The MediaPipe "None" label can appear during motion blur while
                // hand landmarks are still available. In experimental fast mode,
                // treat this as a short gap, not as a confirmed different gesture.
                useFastSwipeAssist && categoryName == "None" ->
                    GestureDecisionEngine.Shape.NO_HAND
                else -> originalShape
            }
            // Geometric evidence is not the classifier's score. Use the existing
            // minimum only after conservative two-frame geometric confirmation.
            val effectiveConfidence = if (angleResult.useLandmarkFallback) .65f else score

            // UI-only recognition feedback. Do not alter GestureDecisionEngine input.
            onHandState(HandVisualClassifier.classify(
                shape, effectiveConfidence, foundHand,
                categoryName == "Pointing_Up" && score >= .65f
            ))

            // The camera analyzer is not a mirrored Preview; make screen-right
            // movement screen-right for the front-facing camera.
            val palmX = if (landmarks != null && landmarks.size > 17) {
                1f - (landmarks[0].x() + landmarks[5].x() + landmarks[9].x() +
                    landmarks[13].x() + landmarks[17].x()) / 5f
            } else null

            // Pointing_Up is a built-in MediaPipe Gesture Recognizer category.
            // Never infer a click from a weak/occluded classification.
            if (categoryName == "Pointing_Up" && score >= .65f && landmarks != null && landmarks.size > 8) {
                val index = landmarks[8]
                onPointer(pointer.accept(1f - index.x(), index.y(), now))
            } else {
                pointer.reset()
                onPointer(null)
            }

            // Visual-only palm location tracking. No circle interpretation, no
            // gesture-action arbitration, and no delayed taps/swipes.
            if (palmTracker != null) onPalmPosition(palmTracker.observe(
                palmX, palmY,
                shape == GestureDecisionEngine.Shape.OPEN_PALM &&
                    effectiveConfidence >= .65f,
                now
            ))
            val action = decision.accept(
                GestureDecisionEngine.Frame(shape, palmY, effectiveConfidence, palmX), now
            )
            val confirmedShape = if (effectiveConfidence < .65f)
                GestureDecisionEngine.Shape.NO_HAND else shape
            // YouTube gets its own verified palm -> fist media key immediately.
            // Every normal swipe / other app fist passes through unchanged.
            youtubeRouter.route(confirmedShape, now, action,
                isYouTubeForeground())?.let(onAction)
        } finally {
            converter.release(frame)
        }
    }

    /**
     * Lightweight screen-off branch: no overlay, pointer, or touch injection.
     * MediaPipe scans less often without a visible hand and speeds up for a hand.
     * Actual sensor power savings are handled by CameraGestureService unbinding it.
     */
    fun processForWake(
        proxy: ImageProxy,
        onPalmObservation: (Boolean, Boolean, Long) -> Unit
    ) {
        val now = SystemClock.uptimeMillis()
        val recentHand = lastWakeHandMs != Long.MIN_VALUE &&
            now >= lastWakeHandMs && now - lastWakeHandMs <= 1100L
        val period = if (recentHand) 140L else 440L
        if (lastWakeScanMs != Long.MIN_VALUE && now >= lastWakeScanMs &&
            now - lastWakeScanMs < period) return
        lastWakeScanMs = now
        val frame = converter.convert(proxy)
        try {
            val result = recognizer.recognizeForVideo(BitmapImageBuilder(frame).build(), now)
            val category = result.gestures().firstOrNull()?.maxByOrNull { it.score() }
            val name = category?.categoryName()
            val score = category?.score() ?: 0f
            val landmarks = result.landmarks().firstOrNull()
            val hasHand = landmarks != null && landmarks.size >= 21
            if (hasHand) lastWakeHandMs = now
            val xy = if (landmarks != null && landmarks.size >= 21) {
                FloatArray(42).also { dst ->
                    for (i in 0 until 21) {
                        dst[i * 2] = landmarks[i].x()
                        dst[i * 2 + 1] = landmarks[i].y()
                    }
                }
            } else null
            val assist = wakeTiltAssist.resolve(name, score, xy, now)
            val palm = (name == "Open_Palm" && score >= .65f) ||
                assist.useLandmarkFallback
            onPalmObservation(palm, hasHand, now)
        } finally {
            converter.release(frame)
        }
    }

    override fun close() {
        try { recognizer.close() } finally { converter.close() }
    }
}

/**
 * CameraX RGBA_8888 -> Bitmap. Reuse the source Bitmap and row-padding buffer.
 * MediaPipe VIDEO processing is synchronous, so the temporary rotated Bitmap
 * can be released immediately after recognizeForVideo returns.
 */
private class ReusableFrameConverter : AutoCloseable {
    private var source: Bitmap? = null
    private var rowBuffer: ByteArray? = null
    private val rotateMatrix = Matrix()

    fun convert(proxy: ImageProxy): Bitmap {
        val width = proxy.width
        val height = proxy.height
        val plane = proxy.planes[0]
        require(plane.pixelStride == 4) { "CameraX analyzer requires RGBA_8888" }
        val src = source?.takeIf { !it.isRecycled && it.width == width && it.height == height }
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                source?.recycle()
                source = it
            }
        val rowBytes = width * 4
        val buffer = plane.buffer.duplicate().apply { rewind() }
        if (plane.rowStride == rowBytes) {
            src.copyPixelsFromBuffer(buffer)
        } else {
            val needed = rowBytes * height
            val storage = rowBuffer?.takeIf { it.size == needed }
                ?: ByteArray(needed).also { rowBuffer = it }
            for (row in 0 until height) {
                buffer.position(row * plane.rowStride)
                buffer.get(storage, row * rowBytes, rowBytes)
            }
            src.copyPixelsFromBuffer(ByteBuffer.wrap(storage))
        }
        val rotation = proxy.imageInfo.rotationDegrees
        if (rotation == 0) return src
        rotateMatrix.reset()
        rotateMatrix.postRotate(rotation.toFloat())
        return Bitmap.createBitmap(src, 0, 0, width, height, rotateMatrix, true)
    }

    fun release(frame: Bitmap) {
        if (frame !== source && !frame.isRecycled) frame.recycle()
    }

    override fun close() {
        source?.recycle()
        source = null
        rowBuffer = null
    }
}
