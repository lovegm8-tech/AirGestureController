package tw.airgesture.transferbeta.subtitle;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.icu.text.Transliterator;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.TextView;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Independent OCR subtitle service for Floating Control v1.0.23.
 * Does not touch camera, gestures, Nearby, NFC or the app's file-transfer service.
 * MediaProjection consent is required for each new capture session.
 * No audio recording and no remote translation API.
 */
public final class SubtitleCaptureService extends Service {
    public static final String ACTION_START = "tw.airgesture.transferbeta.subtitle.START";
    public static final String ACTION_STOP = "tw.airgesture.transferbeta.subtitle.STOP";
    public static final String EXTRA_RESULT = "screen_capture_result";
    public static final String EXTRA_DATA = "screen_capture_data";
    private static final String CHANNEL_ID = "floating_subtitle_capture";
    private static final int NOTIFICATION_ID = 34122;
    private static final long SCAN_INTERVAL_MS = 1000L;
    @SuppressWarnings("FieldMayBeFinal")
    public static volatile boolean running = false;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean ocrBusy = new AtomicBoolean(false);
    private final AtomicBoolean translateBusy = new AtomicBoolean(false);
    private HandlerThread workerThread;
    private Handler worker;
    private WindowManager windowManager;
    private TextView captionView;
    private WindowManager.LayoutParams captionParams;
    private MediaProjection mediaProjection;
    private MediaProjection.Callback projectionCallback;
    private ImageReader imageReader;
    private VirtualDisplay virtualDisplay;
    private TextRecognizer recognizer;
    private Translator translator;
    private Transliterator traditionalConverter;
    private volatile boolean modelReady = false;
    private int width, height, densityDpi;
    private String lastSource = "";
    private String lastTranslation = "";
    private SubtitleCandidatePolicy.Candidate previousCandidate;
    private int stableSubtitleFrames = 0;
    private boolean captionDraggedByUser = false;
    private volatile long lastSubtitleObservedMs = 0L;
    private int captureCropY = 0;
    private String sourceLanguage = "en";

    @Override public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        workerThread = new HandlerThread("floating-subtitle-ocr");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
        try {
            traditionalConverter = Transliterator.getInstance("Simplified-Traditional");
        } catch (Exception ignored) {
            traditionalConverter = null;
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(intent.getAction()) || running) return START_NOT_STICKY;

        int resultCode = intent.getIntExtra(EXTRA_RESULT, Activity.RESULT_CANCELED);
        Intent data;
        if (Build.VERSION.SDK_INT >= 33) {
            data = intent.getParcelableExtra(EXTRA_DATA, Intent.class);
        } else {
            data = intent.getParcelableExtra(EXTRA_DATA);
        }
        if (resultCode != Activity.RESULT_OK || data == null ||
                !Settings.canDrawOverlays(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            createNotificationChannel();
            startForeground(NOTIFICATION_ID, makeNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            running = true;
            createCaption();
            initializeRecognizer();
            MediaProjectionManager manager =
                    (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            mediaProjection = manager.getMediaProjection(resultCode, data);
            if (mediaProjection == null) throw new IllegalStateException("capture permission rejected");
            projectionCallback = new MediaProjection.Callback() {
                @Override public void onStop() { stopSelf(); }
            };
            mediaProjection.registerCallback(projectionCallback, main);
            updateDisplayMetrics();
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
            virtualDisplay = mediaProjection.createVirtualDisplay("FloatingControlSubtitle",
                    width, height, densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(), null, main);
            worker.post(frameLoop);
        } catch (Exception exception) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void initializeRecognizer() {
        String language = getSharedPreferences("floating_subtitle", MODE_PRIVATE)
                .getString("source_language", "en");
        sourceLanguage = language;
        final String translateFrom;
        if ("ja".equals(language)) {
            recognizer = TextRecognition.getClient(
                    new JapaneseTextRecognizerOptions.Builder().build());
            translateFrom = TranslateLanguage.JAPANESE;
        } else if ("ko".equals(language)) {
            recognizer = TextRecognition.getClient(
                    new KoreanTextRecognizerOptions.Builder().build());
            translateFrom = TranslateLanguage.KOREAN;
        } else {
            recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
            translateFrom = TranslateLanguage.ENGLISH;
        }
        translator = Translation.getClient(new TranslatorOptions.Builder()
                .setSourceLanguage(translateFrom)
                .setTargetLanguage(TranslateLanguage.CHINESE).build());
        showCaption("正在準備翻譯模型…");
        // The model downloads only if needed; subsequent use can be entirely offline.
        translator.downloadModelIfNeeded(new DownloadConditions.Builder().requireWifi().build())
                .addOnSuccessListener(ignored -> {
                    if (!running) return;
                    modelReady = true;
                    showCaption("翻譯已開啟，等待外語字幕…");
                })
                .addOnFailureListener(error -> {
                    modelReady = false;
                    showCaption("無法載入翻譯模型，請連接 Wi-Fi 後重新啟動翻譯");
                });
    }

    private int dp(int size) {
        return Math.round(getResources().getDisplayMetrics().density * size);
    }

    private void updateDisplayMetrics() {
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        width = metrics.widthPixels;
        height = metrics.heightPixels;
        densityDpi = metrics.densityDpi;
    }

    private void createCaption() {
        captionView = new TextView(this);
        captionView.setText("準備懸浮字幕…");
        captionView.setTextColor(Color.WHITE);
        captionView.setTextSize(18f);
        captionView.setGravity(Gravity.CENTER);
        captionView.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(225, 12, 18, 28));
        bg.setCornerRadius(dp(11));
        captionView.setBackground(bg);
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        captionParams = new WindowManager.LayoutParams(
                Math.round(metrics.widthPixels * 0.86f),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_SECURE,
                PixelFormat.TRANSLUCENT);
        captionParams.gravity = Gravity.TOP | Gravity.LEFT;
        captionParams.x = Math.round(metrics.widthPixels * 0.07f);
        // Put the Chinese subtitle near the bottom by default; users can drag it.
        // The initial hint is positioned high; translated captions follow the
        // actual detected subtitle position once OCR finds dialogue.
        captionParams.y = Math.round(metrics.heightPixels * 0.18f);
        captionView.setOnTouchListener(new android.view.View.OnTouchListener() {
            private float rawX, rawY;
            private int baseX, baseY;
            @Override public boolean onTouch(android.view.View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        rawX = event.getRawX(); rawY = event.getRawY();
                        baseX = captionParams.x; baseY = captionParams.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (Math.abs(event.getRawX() - rawX) > dp(8)
                                || Math.abs(event.getRawY() - rawY) > dp(8)) {
                            captionDraggedByUser = true;
                        }
                        captionParams.x = Math.max(0, baseX + Math.round(event.getRawX() - rawX));
                        captionParams.y = Math.max(0, baseY + Math.round(event.getRawY() - rawY));
                        try { windowManager.updateViewLayout(captionView, captionParams); }
                        catch (IllegalArgumentException ignored) { }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        return true;
                    default:
                        return false;
                }
            }
        });
        windowManager.addView(captionView, captionParams);
    }

    private void showCaption(String text) {
        main.post(() -> {
            if (running && captionView != null) captionView.setText(text);
        });
    }


    /**
     * Place translated text immediately above the original OCR subtitle.
     * User dragging the floating box pauses auto following for this session.
     */
    private void positionCaptionAbove(SubtitleCandidatePolicy.Candidate original) {
        if (original == null || captionDraggedByUser) return;
        main.post(() -> {
            if (!running || captionView == null || captionParams == null || captionDraggedByUser) return;
            captionView.post(() -> {
                if (!running || captionView == null || captionParams == null || captionDraggedByUser)
                    return;
                // Re-measure after each text update. A longer translation must not
                // grow down onto the source subtitle.
                captionView.measure(
                        android.view.View.MeasureSpec.makeMeasureSpec(captionParams.width,
                                android.view.View.MeasureSpec.EXACTLY),
                        android.view.View.MeasureSpec.makeMeasureSpec(height,
                                android.view.View.MeasureSpec.AT_MOST));
                int viewHeight = Math.max(dp(38), captionView.getMeasuredHeight());
                int wantedX = original.centerX() - captionParams.width / 2;
                int gap = dp(9);
                int wantedY = original.top - viewHeight - gap;
                // If the source caption is too close to the top edge, display
                // beneath it rather than cover its English/Japanese/Korean text.
                if (wantedY < 0) wantedY = original.bottom + gap;
                captionParams.x = Math.max(0, Math.min(Math.max(0, width - captionParams.width), wantedX));
                captionParams.y = Math.max(0, Math.min(Math.max(0, height - viewHeight), wantedY));
                try { windowManager.updateViewLayout(captionView, captionParams); }
                catch (IllegalArgumentException ignored) { }
            });
        });
    }

    private final Runnable frameLoop = new Runnable() {
        @Override public void run() {
            if (!running) return;
            try {
                if (modelReady && ocrBusy.compareAndSet(false, true)) captureFrame();
                adaptRotation();
            } catch (Exception ignored) {
                ocrBusy.set(false);
            }
            if (running) worker.postDelayed(this, SCAN_INTERVAL_MS);
        }
    };

    private void adaptRotation() {
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        if (metrics.widthPixels == width && metrics.heightPixels == height) return;
        if (ocrBusy.get() || virtualDisplay == null) return;
        ImageReader next = null;
        try {
            next = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels,
                    PixelFormat.RGBA_8888, 2);
            virtualDisplay.resize(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi);
            virtualDisplay.setSurface(next.getSurface());
            ImageReader old = imageReader;
            imageReader = next;
            next = null;
            width = metrics.widthPixels;
            height = metrics.heightPixels;
            densityDpi = metrics.densityDpi;
            if (old != null) old.close();
            main.post(() -> {
                if (captionView == null || captionParams == null) return;
                captionParams.width = Math.round(width * 0.86f);
                captionParams.x = Math.min(captionParams.x, Math.round(width * 0.14f));
                try { windowManager.updateViewLayout(captionView, captionParams); }
                catch (IllegalArgumentException ignored) { }
            });
        } catch (Exception ignored) {
            if (next != null) next.close();
        }
    }

    private void captureFrame() {
        Image image = null;
        Bitmap screen = null;
        Bitmap crop = null;
        boolean acceptedByRecognizer = false;
        try {
            if (imageReader == null) return;
            image = imageReader.acquireLatestImage();
            if (image == null) return;
            Image.Plane plane = image.getPlanes()[0];
            int pixelStride = plane.getPixelStride();
            if (pixelStride <= 0 || plane.getRowStride() / pixelStride < width) return;
            int paddedWidth = plane.getRowStride() / pixelStride;
            ByteBuffer buffer = plane.getBuffer();
            screen = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888);
            screen.copyPixelsFromBuffer(buffer);
            final int mode = getSharedPreferences("floating_subtitle", MODE_PRIVATE)
                    .getInt("subtitle_area_mode", SubtitleRegionPolicy.FULL_SCREEN);
            final int topPercent = getSharedPreferences("floating_subtitle", MODE_PRIVATE)
                    .getInt("subtitle_area_top_pct", 20);
            final int bottomPercent = getSharedPreferences("floating_subtitle", MODE_PRIVATE)
                    .getInt("subtitle_area_bottom_pct", 85);
            final int[] region = SubtitleRegionPolicy.verticalBounds(
                    width, height, mode, topPercent, bottomPercent);
            captureCropY = region[0];
            final int frameCropY = region[0];
            crop = Bitmap.createBitmap(screen, 0, region[0], width,
                    region[1] - region[0]);
            Bitmap owned = crop;
            crop = null;
            recognizer.process(InputImage.fromBitmap(owned, 0))
                    .addOnSuccessListener(text -> translateRecognizedLines(text, frameCropY))
                    .addOnCompleteListener(task -> {
                        owned.recycle();
                        ocrBusy.set(false);
                    });
            acceptedByRecognizer = true;
        } catch (Exception ignored) {
            // Nonfatal: next frame retries.
        } finally {
            if (image != null) image.close();
            if (screen != null) screen.recycle();
            if (crop != null) crop.recycle();
            if (!acceptedByRecognizer) ocrBusy.set(false);
        }
    }


    private void translateRecognizedLines(Text result, int frameCropY) {
        if (!running || !modelReady || translator == null) return;
        List<SubtitleCandidatePolicy.Candidate> candidates = new ArrayList<>();
        for (Text.TextBlock block : result.getTextBlocks()) {
            List<Text.Line> valid = new ArrayList<>();
            for (Text.Line line : block.getLines()) {
                Rect rect = line.getBoundingBox();
                String raw = line.getText().trim();
                if (rect == null || !SubtitleRegionPolicy.isPossibleSubtitle(raw, sourceLanguage))
                    continue;
                valid.add(line);
                candidates.add(new SubtitleCandidatePolicy.Candidate(raw,
                        rect.left, rect.top + frameCropY,
                        rect.right, rect.bottom + frameCropY));
            }
            // Keep a complete two-line subtitle together when ML Kit grouped it.
            // A long paragraph or a metadata panel is not a normal caption.
            if (valid.size() >= 2 && valid.size() <= 3) {
                StringBuilder combined = new StringBuilder();
                int left = Integer.MAX_VALUE, top = Integer.MAX_VALUE;
                int right = 0, bottom = 0;
                for (Text.Line line : valid) {
                    Rect rect = line.getBoundingBox();
                    if (combined.length() > 0) combined.append(' ');
                    combined.append(line.getText().trim());
                    left = Math.min(left, rect.left);
                    right = Math.max(right, rect.right);
                    top = Math.min(top, rect.top);
                    bottom = Math.max(bottom, rect.bottom);
                }
                if (bottom - top < height * 0.13f && combined.length() <= 280) {
                    candidates.add(new SubtitleCandidatePolicy.Candidate(
                            combined.toString(), left, top + frameCropY,
                            right, bottom + frameCropY));
                }
            }
        }
        SubtitleCandidatePolicy.Candidate chosen =
                SubtitleCandidatePolicy.best(candidates, width, height, previousCandidate, stableSubtitleFrames);
        if (chosen == null) {
            if (lastSubtitleObservedMs > 0L &&
                    SystemClock.elapsedRealtime() - lastSubtitleObservedMs > 4300L) {
                lastSubtitleObservedMs = 0L;
                lastSource = "";
                lastTranslation = "";
                previousCandidate = null;
                stableSubtitleFrames = 0;
                showCaption("等待外語字幕…");
            }
            return;
        }
        lastSubtitleObservedMs = SystemClock.elapsedRealtime();
        // Tracking is based on OCR coordinates, not a fixed screen crop.
        String source = chosen.text.replaceAll("\\s+", " ").trim();
        stableSubtitleFrames = previousCandidate != null
                && source.equals(previousCandidate.text)
                ? Math.min(30, stableSubtitleFrames + 1) : 0;
        previousCandidate = chosen;
        if (source.length() < 3) return;
        if (source.equals(lastSource)) {
            positionCaptionAbove(chosen);
            return;
        }
        if (!translateBusy.compareAndSet(false, true)) return;
        final SubtitleCandidatePolicy.Candidate translatedCandidate = chosen;
        lastSource = source;
        translator.translate(source)
                .addOnSuccessListener(translated -> {
                    if (!running || !source.equals(lastSource)) return;
                    lastTranslation = traditionalize(translated);
                    showCaption(lastTranslation);
                    positionCaptionAbove(translatedCandidate);
                })
                .addOnFailureListener(error -> {
                    if (source.equals(lastSource)) lastSource = "";
                })
                .addOnCompleteListener(done -> translateBusy.set(false));
    }

    private String traditionalize(String input) {
        if (traditionalConverter == null) return input;
        try { return traditionalConverter.transliterate(input); }
        catch (Exception ignored) { return input; }
    }

    private void createNotificationChannel() {
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.createNotificationChannel(
                new NotificationChannel(CHANNEL_ID, "懸浮翻譯", NotificationManager.IMPORTANCE_LOW));
    }

    private Notification makeNotification() {
        Intent stopIntent = new Intent(this, SubtitleCaptureService.class).setAction(ACTION_STOP);
        PendingIntent stop = PendingIntent.getService(this, 341,
                stopIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent openIntent = new Intent(this, tw.airgesture.transferbeta.TransferActivity.class)
                .putExtra("airgrab_show_settings", true);
        PendingIntent open = PendingIntent.getActivity(this, 342, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("懸浮操作 · OCR 字幕翻譯中")
                .setContentText("只辨識影片畫面文字；點此回到設定")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_close_clear_cancel, "停止翻譯", stop).build())
                .build();
    }

    @Override public void onDestroy() {
        running = false;
        modelReady = false;
        if (worker != null) worker.removeCallbacksAndMessages(null);
        if (virtualDisplay != null) { virtualDisplay.release(); virtualDisplay = null; }
        if (imageReader != null) { imageReader.close(); imageReader = null; }
        if (mediaProjection != null) {
            if (projectionCallback != null) mediaProjection.unregisterCallback(projectionCallback);
            mediaProjection.stop();
            mediaProjection = null;
        }
        if (recognizer != null) recognizer.close();
        if (translator != null) translator.close();
        if (captionView != null) {
            try { windowManager.removeView(captionView); }
            catch (IllegalArgumentException ignored) { }
            captionView = null;
        }
        if (workerThread != null) workerThread.quitSafely();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
