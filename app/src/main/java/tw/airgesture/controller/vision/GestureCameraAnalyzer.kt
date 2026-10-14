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
import tw.airgesture.controller.core.PointerDwellEngine
import tw.airgesture.controller.core.TiltedPalmAssist
import java.nio.ByteBuffer

/** CameraX feeds a single analysis executor. Inference is local and throttled. */
class GestureCameraAnalyzer(
    context: Context,
    options: GestureDecisionEngine.Options,
    performanceMode: Int = 1,
    angleAssistEnabled: Boolean = false
) : AutoCloseable {
    private val decision = GestureDecisionEngine(options)
    private val pointer = PointerDwellEngine()
    private val tiltAssist = TiltedPalmAssist(angleAssistEnabled)
    private val useAngleAssist = angleAssistEnabled
    private val scheduler = AdaptiveFrameScheduler(AdaptiveFrameScheduler.Mode.fromIndex(performanceMode))
    private val converter = ReusableFrameConverter()
    private val recognizer: GestureRecognizer

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
        onPalmDetection: (Boolean) -> Unit,
        onPointer: (PointerDwellEngine.Result?) -> Unit
    ) {
        val now = SystemClock.uptimeMillis()
        if (!scheduler.shouldAnalyze(now)) return

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
            onPalmDetection(angleResult.showPalmIndicator)
            scheduler.onAnalyzed(now, foundHand)
            val shape = when {
                angleResult.useLandmarkFallback -> GestureDecisionEngine.Shape.OPEN_PALM
                // The MediaPipe "None" label can appear during motion blur while
                // hand landmarks are still available. In experimental fast mode,
                // treat this as a short gap, not as a confirmed different gesture.
                options.fastSwipeAssistEnabled && categoryName == "None" ->
                    GestureDecisionEngine.Shape.NO_HAND
                else -> originalShape
            }
            // Geometric evidence is not the classifier's score. Use the existing
            // minimum only after conservative two-frame geometric confirmation.
            val effectiveConfidence = if (angleResult.useLandmarkFallback) .65f else score

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

            decision.accept(GestureDecisionEngine.Frame(shape, palmY, effectiveConfidence, palmX), now)?.let(onAction)
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
