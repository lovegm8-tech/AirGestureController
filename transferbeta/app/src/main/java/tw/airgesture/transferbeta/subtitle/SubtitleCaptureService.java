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
import android.graphics.RectF;
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
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.content.SharedPreferences;
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
    public static final String ACTION_EDIT = "tw.airgesture.transferbeta.subtitle.EDIT_ROI";
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
    private FrameLayout editorRoot;
    private SubtitleRoiEditor roiEditor;
    private TextView reopenEditorView;
    private volatile Rect activeRoi;
    private volatile boolean calibrating = true;
    private volatile boolean manuallySelecting = false;
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
    private int scanCounter = 0;
    private long embeddedPageObservedMs = -30000L;
    public static volatile String lastSelectionStatus = "尚未選取字幕";
    private static final float PORTRAIT_ZOOM = 1.8f;
    public static volatile String lastRecognizedSource = "尚未辨識到原文";
    public static volatile String lastTranslationResult = "尚未翻譯";
    public static volatile String lastScanMode = "尚未開始掃描";
    public static volatile String lastCandidateSummary = "尚無 OCR 候選字幕";
    public static volatile String lastTranslationQuality = "尚無翻譯結果";
    private String sourceLanguage = "en";

    @Override public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        lastRecognizedSource = "尚未辨識到原文";
        lastTranslationResult = "尚未翻譯";
        lastScanMode = "初始化";
        lastCandidateSummary = "尚無 OCR 候選字幕";
        lastTranslationQuality = "尚無翻譯結果";
        lastSelectionStatus = "等待 OCR 擷取畫面";
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
        if (intent != null && ACTION_EDIT.equals(intent.getAction())) {
            if (running) main.post(this::openSelectionEditor);
            return START_NOT_STICKY;
        }
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
            // Let the user return to the video after MediaProjection consent.
            main.postDelayed(this::openSelectionEditor, 1250L);
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
                    if (!calibrating) showCaption("翻譯已開啟，等待外語字幕…");
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


    private int unitDp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private String roiKey() {
        return width > height ? "landscape" : "portrait";
    }

    private RectF readNormalizedRoi() {
        SharedPreferences pref = getSharedPreferences("floating_subtitle", MODE_PRIVATE);
        String p = "ocr_roi_" + roiKey();
        if (!pref.getBoolean(p + "_saved", false)) {
            return width > height
                    ? new RectF(0.12f, 0.76f, 0.88f, 0.92f)
                    : new RectF(0.12f, 0.255f, 0.88f, 0.37f);
        }
        return new RectF(pref.getFloat(p + "_l", 0.12f),
                pref.getFloat(p + "_t", 0.25f),
                pref.getFloat(p + "_r", 0.88f),
                pref.getFloat(p + "_b", 0.37f));
    }

    private Rect normalizeToPixels(RectF fractions) {
        int l = Math.max(0, Math.min(width - 1, Math.round(fractions.left * width)));
        int t = Math.max(0, Math.min(height - 1, Math.round(fractions.top * height)));
        int r = Math.max(l + 1, Math.min(width, Math.round(fractions.right * width)));
        int b = Math.max(t + 1, Math.min(height, Math.round(fractions.bottom * height)));
        return new Rect(l, t, r, b);
    }

    private void closeSelectionEditor() {
        if (editorRoot != null) {
            try { windowManager.removeView(editorRoot); }
            catch (IllegalArgumentException ignored) { }
            editorRoot = null;
            roiEditor = null;
        }
    }

    private void hideReopenButton() {
        if (reopenEditorView != null) {
            try { windowManager.removeView(reopenEditorView); }
            catch (IllegalArgumentException ignored) { }
            reopenEditorView = null;
        }
    }

    /** A tiny, nonblocking launcher so the user can navigate to a video first. */
    private void showReopenButton() {
        hideReopenButton();
        reopenEditorView = new TextView(this);
        reopenEditorView.setText("框選字幕");
        reopenEditorView.setTextColor(Color.WHITE);
        reopenEditorView.setTextSize(15f);
        reopenEditorView.setPadding(unitDp(14), unitDp(11), unitDp(14), unitDp(11));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(18, 102, 142));
        bg.setCornerRadius(unitDp(26));
        reopenEditorView.setBackground(bg);
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                        WindowManager.LayoutParams.FLAG_SECURE,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.RIGHT | Gravity.CENTER_VERTICAL;
        p.x = unitDp(12);
        reopenEditorView.setOnClickListener(v -> openSelectionEditor());
        try { windowManager.addView(reopenEditorView, p); }
        catch (Exception ignored) { reopenEditorView = null; }
    }

    /** Editable overlay is never included in the recorded OCR screenshot. */
    private void openSelectionEditor() {
        if (!running || !Settings.canDrawOverlays(this)) return;
        calibrating = true;
        manuallySelecting = true;
        hideReopenButton();
        closeSelectionEditor();
        if (captionView != null) captionView.setVisibility(View.INVISIBLE);
        try {
            editorRoot = new FrameLayout(this);
            roiEditor = new SubtitleRoiEditor(this, readNormalizedRoi());
            editorRoot.addView(roiEditor, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            LinearLayout controls = new LinearLayout(this);
            controls.setOrientation(LinearLayout.VERTICAL);
            controls.setPadding(unitDp(8), unitDp(7), unitDp(8), unitDp(7));
            GradientDrawable surface = new GradientDrawable();
            surface.setColor(Color.argb(238, 19, 28, 40));
            surface.setCornerRadius(unitDp(14));
            controls.setBackground(surface);
            TextView tip = new TextView(this);
            tip.setText("把綠框移到原文字幕上，拉四角調整大小");
            tip.setTextColor(Color.WHITE);
            tip.setTextSize(13f);
            tip.setGravity(Gravity.CENTER);
            controls.addView(tip);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            Button later = new Button(this);
            later.setText("切換影片");
            later.setAllCaps(false);
            later.setOnClickListener(v -> {
                closeSelectionEditor();
                showReopenButton();
            });
            Button cancel = new Button(this);
            cancel.setText("停止翻譯");
            cancel.setAllCaps(false);
            cancel.setOnClickListener(v -> stopSelf());
            Button done = new Button(this);
            done.setText("完成框選");
            done.setAllCaps(false);
            done.setOnClickListener(v -> {
                if (roiEditor == null) return;
                RectF selected = roiEditor.normalizedRect();
                Rect region = normalizeToPixels(selected);
                if (region.width() < unitDp(78) || region.height() < unitDp(24)) {
                    tip.setText("框太小，請放大後再完成");
                    return;
                }
                String key = "ocr_roi_" + roiKey();
                getSharedPreferences("floating_subtitle", MODE_PRIVATE).edit()
                        .putBoolean(key + "_saved", true)
                        .putFloat(key + "_l", selected.left)
                        .putFloat(key + "_t", selected.top)
                        .putFloat(key + "_r", selected.right)
                        .putFloat(key + "_b", selected.bottom).apply();
                activeRoi = region;
                manuallySelecting = false;
                closeSelectionEditor();
                hideReopenButton();
                lastSource = "";
                previousCandidate = null;
                stableSubtitleFrames = 0;
                if (captionView != null) captionView.setVisibility(View.VISIBLE);
                calibrating = false;
                showCaption(modelReady ? "等待框內字幕…" : "正在準備翻譯模型…");
            });
            row.addView(later, new LinearLayout.LayoutParams(0, unitDp(50), 1f));
            row.addView(cancel, new LinearLayout.LayoutParams(0, unitDp(50), 1f));
            row.addView(done, new LinearLayout.LayoutParams(0, unitDp(50), 1f));
            controls.addView(row);
            FrameLayout.LayoutParams bar = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP);
            bar.setMargins(unitDp(12), unitDp(34), unitDp(12), 0);
            editorRoot.addView(controls, bar);
            WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                            WindowManager.LayoutParams.FLAG_SECURE,
                    PixelFormat.TRANSLUCENT);
            p.gravity = Gravity.TOP | Gravity.LEFT;
            windowManager.addView(editorRoot, p);
        } catch (Exception ignored) {
            closeSelectionEditor();
            showReopenButton();
        }
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
                if (!calibrating && modelReady && ocrBusy.compareAndSet(false, true)) captureFrame();
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
            calibrating = true;
            activeRoi = null;
            lastSource = "";
            lastTranslation = "";
            previousCandidate = null;
            if (old != null) old.close();
            main.post(() -> {
                closeSelectionEditor();
                openSelectionEditor();
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
        Bitmap enlarged = null;
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
            final Rect selectedRoi = activeRoi;
            final boolean manualRoiActive = selectedRoi != null;
            int cropX = 0;
            int rawCropY = region[0];
            float imageScale = 1.0f;
            if (manualRoiActive) {
                Rect roi = new Rect(selectedRoi);
                if (!roi.intersect(0, 0, width, height) ||
                        roi.width() < 2 || roi.height() < 2) return;
                cropX = roi.left;
                rawCropY = roi.top;
                crop = Bitmap.createBitmap(screen,
                        roi.left, roi.top, roi.width(), roi.height());
                // Subtitle font in small selected regions needs more pixels,
                // especially on portrait YouTube and low-resolution streams.
                float scale = Math.min(2.15f,
                        Math.max(1.0f, 1500f / Math.max(1, roi.width())));
                if (scale > 1.05f) {
                    enlarged = Bitmap.createScaledBitmap(crop,
                            Math.max(1, Math.round(roi.width() * scale)),
                            Math.max(1, Math.round(roi.height() * scale)), true);
                    crop.recycle();
                    crop = enlarged;
                    enlarged = null;
                    imageScale = scale;
                }
            } else {
                crop = Bitmap.createBitmap(screen, 0, region[0], width,
                        region[1] - region[0]);
            }
            captureCropY = rawCropY;
            final boolean portraitZoom =
                    !manualRoiActive
                    && height > width * 1.25f
                    && mode == SubtitleRegionPolicy.FULL_SCREEN
                    && (++scanCounter % 3 != 0);
            final boolean subtitleBand = portraitZoom && scanCounter % 3 == 1;
            // Always scan the full display periodically. The other two passes
            // magnify the embedded video: one whole player and one focused
            // bottom subtitle band. The band deliberately excludes the
            // recommended videos/search UI starting below ~37.5%.
            if (portraitZoom) {
                int videoTop = subtitleBand ? Math.round(height * 0.21f) : 0;
                int videoBottom = Math.max(videoTop + 1, Math.round(height * 0.375f));
                Bitmap videoRegion = Bitmap.createBitmap(
                        screen, 0, videoTop, width, videoBottom - videoTop);
                float zoomFactor = subtitleBand ? 2.35f : PORTRAIT_ZOOM;
                try {
                    enlarged = Bitmap.createScaledBitmap(videoRegion,
                            Math.max(1, Math.round(width * zoomFactor)),
                            Math.max(1, Math.round(videoRegion.getHeight() * zoomFactor)),
                            true);
                } finally { videoRegion.recycle(); }
                crop.recycle();
                crop = enlarged;
                enlarged = null;
                rawCropY = videoTop;
                imageScale = zoomFactor;
            }
            final int frameCropY = rawCropY;
            final int frameCropX = cropX;
            final float frameScale = imageScale;
            lastScanMode = manualRoiActive ? "手動字幕框（只掃框內）" :
                    (subtitleBand ? "直向影片字幕帶放大 2.35 倍" :
                    (portraitZoom ? "直向播放器放大 1.8 倍" :
                    (height > width ? "直向全螢幕" : "橫向全螢幕")));
            Bitmap owned = crop;
            crop = null;
            recognizer.process(InputImage.fromBitmap(owned, 0))
                    .addOnSuccessListener(text -> {
                        if (!calibrating) translateRecognizedLines(text, frameCropX, frameCropY,
                                frameScale, manualRoiActive);
                    })
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
            if (enlarged != null) enlarged.recycle();
            if (!acceptedByRecognizer) ocrBusy.set(false);
        }
    }


    private void translateRecognizedLines(Text result, int frameCropX, int frameCropY,
                                          float imageScale, boolean manualRoiActive) {
        if (!running || !modelReady || translator == null) return;
        List<SubtitleCandidatePolicy.Candidate> candidates = new ArrayList<>();
        List<SubtitleCandidatePolicy.Candidate> pageLines = new ArrayList<>();
        for (Text.TextBlock block : result.getTextBlocks()) {
            List<Text.Line> valid = new ArrayList<>();
            for (Text.Line line : block.getLines()) {
                Rect rect = line.getBoundingBox();
                String raw = line.getText().trim();
                if (rect == null || raw.length() < 2) continue;
                // Detect text-based page chrome using unfiltered OCR results,
                // including labels in the wrong language.
                pageLines.add(new SubtitleCandidatePolicy.Candidate(raw,
                        Math.round(rect.left / imageScale) + frameCropX,
                        Math.round(rect.top / imageScale) + frameCropY,
                        Math.round(rect.right / imageScale) + frameCropX,
                        Math.round(rect.bottom / imageScale) + frameCropY));
                if (!SubtitleRegionPolicy.isPossibleSubtitle(raw, sourceLanguage)) continue;
                valid.add(line);
                candidates.add(new SubtitleCandidatePolicy.Candidate(raw,
                        Math.round(rect.left / imageScale) + frameCropX,
                        Math.round(rect.top / imageScale) + frameCropY,
                        Math.round(rect.right / imageScale) + frameCropX,
                        Math.round(rect.bottom / imageScale) + frameCropY));
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
                if ((bottom - top) / imageScale < height * 0.13f && combined.length() <= 280) {
                    candidates.add(new SubtitleCandidatePolicy.Candidate(
                            combined.toString(), Math.round(left / imageScale) + frameCropX,
                            Math.round(top / imageScale) + frameCropY,
                            Math.round(right / imageScale) + frameCropX,
                            Math.round(bottom / imageScale) + frameCropY));
                }
            }
        }
        java.util.List<SubtitleCandidatePolicy.Candidate> originals = new ArrayList<>(candidates);
        candidates.addAll(SubtitleCandidatePolicy.joinNearbyLines(originals, width));
        // Diagnostics are kept only in memory; no screen frames or text are sent
        // to our server. Show ranking and location to distinguish OCR selection
        // errors from ML Kit translation problems in the field.
        List<SubtitleCandidatePolicy.Candidate> ranking = new ArrayList<>(candidates);
        ranking.sort((a, b) -> Double.compare(
                SubtitleCandidatePolicy.score(b, width, height, previousCandidate, stableSubtitleFrames),
                SubtitleCandidatePolicy.score(a, width, height, previousCandidate, stableSubtitleFrames)));
        StringBuilder candidateSummary = new StringBuilder();
        for (int idx = 0; idx < Math.min(5, ranking.size()); idx++) {
            SubtitleCandidatePolicy.Candidate item = ranking.get(idx);
            if (candidateSummary.length() > 0) candidateSummary.append(" | ");
            candidateSummary.append(idx + 1).append(". [")
                    .append(Math.round(item.centerY() * 100f / height)).append("%] ")
                    .append(item.text.substring(0, Math.min(90, item.text.length())));
        }
        lastCandidateSummary = candidateSummary.length() == 0
                ? "此影格沒有符合來源語言的候選文字" : candidateSummary.toString();
        boolean fullScreenSetting = getSharedPreferences("floating_subtitle", MODE_PRIVATE)
                .getInt("subtitle_area_mode", SubtitleRegionPolicy.FULL_SCREEN)
                == SubtitleRegionPolicy.FULL_SCREEN;
        boolean playerOnlyPass = fullScreenSetting
                && height > width * 1.25f && imageScale > 1f;
        boolean portraitFullScan = fullScreenSetting
                && height > width * 1.25f && !playerOnlyPass;
        if (portraitFullScan &&
                SubtitleSelectionPolicy.isEmbeddedPlayerPage(pageLines, width, height)) {
            embeddedPageObservedMs = SystemClock.elapsedRealtime();
        }
        boolean embeddedPage = portraitFullScan
                && SystemClock.elapsedRealtime() - embeddedPageObservedMs < 12000L;
        SubtitleCandidatePolicy.Candidate chosen = manualRoiActive
                ? SubtitleCandidatePolicy.best(candidates, width, height,
                    previousCandidate, stableSubtitleFrames)
                : fullScreenSetting
                ? SubtitleSelectionPolicy.select(candidates, width, height,
                        previousCandidate, stableSubtitleFrames,
                        embeddedPage, playerOnlyPass)
                : SubtitleCandidatePolicy.best(candidates, width, height,
                        previousCandidate, stableSubtitleFrames);
        lastSelectionStatus = chosen == null
                ? (manualRoiActive ? "手動框內尚無可信外語字幕" : (embeddedPage || playerOnlyPass
                    ? "無可信影片字幕：已跳過搜尋標題與推薦內容"
                    : "本影格未找到符合字幕條件的文字"))
                : (manualRoiActive ? "已辨識手動框內字幕" :
                    (embeddedPage ? "已判斷為上方播放器字幕" :
                    (playerOnlyPass ? "已辨識上方影片字幕" : "已選取字幕候選")));
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
        lastRecognizedSource = source;
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
                    lastTranslationResult = lastTranslation;
                    // Do not blindly remove Latin characters: proper names
                    // such as Harry Potter and invented spell names are valid.
                    // Report mixed-language output in diagnostics instead.
                    int han = 0, latin = 0;
                    for (int i = 0; i < lastTranslation.length(); i++) {
                        char c = lastTranslation.charAt(i);
                        if (c >= 0x4e00 && c <= 0x9fff) han++;
                        else if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) latin++;
                    }
                    lastTranslationQuality = latin > 6 && han > 0
                            ? "含英文片段：可能是專有名詞或模型漏譯（請對照 OCR 原文）"
                            : (han == 0 && latin > 0 ? "翻譯未輸出中文，已停止顯示錯誤字幕" : "有輸出中文字");
                    // Prevent a failed ML Kit translation from showing the raw
                    // English search keyword as though it were Chinese.
                    if ("en".equals(sourceLanguage) && han == 0 && latin >= 5) {
                        showCaption("等待正確的中文字幕…");
                        return;
                    }
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
        Intent editIntent = new Intent(this, SubtitleCaptureService.class).setAction(ACTION_EDIT);
        PendingIntent edit = PendingIntent.getService(this, 343,
                editIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
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
                        android.R.drawable.ic_menu_crop, "框選字幕", edit).build())
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
        closeSelectionEditor();
        hideReopenButton();
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
