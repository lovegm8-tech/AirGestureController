package tw.airgesture.transferbeta.subtitle;

import android.app.Activity;
import android.Manifest;
import android.content.pm.PackageManager;
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
import android.widget.ImageView;
import android.widget.ScrollView;

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
    private static final long SCAN_INTERVAL_MS = 650L;
    @SuppressWarnings("FieldMayBeFinal")
    public static volatile boolean running = false;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean ocrBusy = new AtomicBoolean(false);
    private final AtomicBoolean translateBusy = new AtomicBoolean(false);
    // Voice translation is primary; concurrent OCR only fills audio gaps.
    private final AtomicBoolean voiceTranslateBusy = new AtomicBoolean(false);
    private HandlerThread workerThread;
    private Handler worker;
    private WindowManager windowManager;
    private LinearLayout captionView;
    private TextView captionFirstLine;
    private TextView captionSecondLine;
    private final SubtitleTwoLineBuffer twoLineCaptions = new SubtitleTwoLineBuffer();
    private final SubtitlePipelineTrace pipelineTrace = new SubtitlePipelineTrace();
    private long lastOcrProbeAtMs = 0L;
    private long lastTrustedOcrAtMs = 0L;
    private void trace(String stage, String text) {
        pipelineTrace.record(SystemClock.elapsedRealtime(), stage, text);
    }
    public static volatile String lastAiStatus = "AI 模型尚未載入";
    private volatile boolean aiRefineEnabled = false;
    private FrameLayout editorRoot;
    private ScrollView diagnosticView;
    private volatile Bitmap latestOcrCropPreview;
    private volatile long lastPreviewUpdatedMs = 0L;
    private volatile String lastNonemptyRawOcrText = "近期尚未辨識到文字";
    private volatile long lastNonemptyRawOcrAtMs = 0L;
    private volatile String latestRawOcrText = "尚無原始 OCR 文字";
    private boolean diagnosisWasCalibrating = false;
    private SubtitleRoiEditor roiEditor;
    private SubtitleBubbleController translationBubble;
    private SubtitlePlaybackSpeech playbackSpeech;
    private volatile boolean voiceFallbackEnabled = false;
    private volatile boolean voiceUnavailable = false;
    private volatile long voiceStartedAtMs = 0L;
    private volatile long lastVoiceUtteranceMs = 0L;
    private long lastOcrCaptionMs = 0L;
    private long lastVoiceCaptionMs = 0L;
    private String lastSpokenSource = "";
    private String pendingVoiceSource = "";
    private long pendingVoiceUtteranceId = -1L;
    private boolean pendingVoiceIsFinal = false;
    private long lastSpokenUtteranceId = -1L;
    private boolean voiceRetryScheduled = false;
    // 750 ms forced the latest phrase to appear after the next spoken line.
    // ML Kit's single-flight queue still prevents overlap or UI congestion.
    private static final long MIN_VOICE_CAPTION_SPACING_MS = 260L;
    private long pendingVoiceQueuedAtMs = 0L;
    private long lastVoiceTranslatedId = -1L;
    private String lastVoiceFastChinese = "";
    private long lastVoiceAiRequestedId = -1L;
    private String lastVoiceAiRequestedSource = "";
    public static volatile String lastVoiceLatencyStatus = "尚無語音翻譯耗時";
    public static volatile String lastVoiceStatus = "語音補位未啟用";
    public static volatile String lastCaptureStatus = "尚未取得畫面";
    public static volatile String lastOcrStatus = "OCR 尚未啟動";
    public static volatile String lastModelStatus = "翻譯模型尚未初始化";
    public static volatile String lastCropStatus = "未選取字幕範圍";
    public static volatile int framesCaptured = 0;
    public static volatile int ocrRunsSucceeded = 0;
    public static volatile int ocrRunsFailed = 0;
    public static volatile int lastRawOcrLines = 0;
    public static volatile int lastAcceptedOcrLines = 0;
    private long lastFrameAvailableMs = 0L;
    private int ocrEmptyFrameStreak = 0;
    public static volatile String lastSpokenOriginal = "尚無語音辨識結果";
    private volatile boolean pausedByUser = false;
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
        lastVoiceStatus = "語音補位未啟用";
        lastVoiceLatencyStatus = "尚無語音翻譯耗時";
        lastCaptureStatus = "尚未取得畫面";
        lastOcrStatus = "OCR 尚未啟動";
        latestRawOcrText = "尚無原始 OCR 文字";
        lastNonemptyRawOcrText = "近期尚未辨識到文字";
        lastNonemptyRawOcrAtMs = 0L;
        lastModelStatus = "翻譯模型尚未初始化";
        lastCropStatus = "未選取字幕範圍";
        framesCaptured = 0;
        ocrRunsSucceeded = 0;
        ocrRunsFailed = 0;
        lastRawOcrLines = 0;
        lastAcceptedOcrLines = 0;
        lastSpokenOriginal = "尚無語音辨識結果";
        twoLineCaptions.clear();
        pipelineTrace.clear();
        lastOcrProbeAtMs = 0L;
        lastTrustedOcrAtMs = 0L;
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
            voiceFallbackEnabled = getSharedPreferences("floating_subtitle", MODE_PRIVATE)
                    .getBoolean("enable_voice_fallback", true)
                    && ("en".equals(getSharedPreferences("floating_subtitle", MODE_PRIVATE)
                            .getString("source_language", "en")) ||
                        "ja".equals(getSharedPreferences("floating_subtitle", MODE_PRIVATE)
                            .getString("source_language", "en")))
                    && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                            == PackageManager.PERMISSION_GRANTED;
            int foregroundTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
            if (voiceFallbackEnabled && Build.VERSION.SDK_INT >= 30)
                foregroundTypes |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            startForeground(NOTIFICATION_ID, makeNotification(), foregroundTypes);
            running = true;
            aiRefineEnabled = SubtitleLocalAi.enabled(this) &&
                    SubtitleLocalAi.modelInstalled(this);
            lastAiStatus = aiRefineEnabled ? "本地 Qwen 已安裝，等待完整台詞進行潤飾" :
                    "使用 ML Kit 快速翻譯（本地 AI 尚未安裝／停用）";
            // Voice-first starts immediately; picking an OCR rectangle is
            // optional and must never gate AudioPlaybackCapture callbacks.
            calibrating = false;
            voiceUnavailable = false;
            voiceStartedAtMs = SystemClock.elapsedRealtime();
            lastVoiceUtteranceMs = 0L;
            lastCropStatus = "自動尋找影片字幕；也可點譯小球手動框選";
            lastSelectionStatus = "優先讀取影片聲音，無聲音時才使用 OCR";
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
            if (voiceFallbackEnabled) {
                lastVoiceStatus = "語音優先：準備讀取影片聲音";
                startPlaybackSpeech();
            } else {
                lastVoiceStatus = "語音未啟用或未獲授權，使用畫面 OCR";
            }
            showCaption(voiceFallbackEnabled ? "正在啟動影片語音翻譯…" :
                    "正在搜尋畫面文字（可點譯小球框選）");
            // Do not cover the Activity with the fullscreen editor after
            // consent: the user must be able to tap "返回桌面 · 開始使用".
            // Once at a video, tap the persistent "譯" ball to select its
            // subtitle strip and begin scanning.
            main.postDelayed(this::showReopenButton, 900L);
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
        lastModelStatus = "下載或載入離線翻譯模型";
        showCaption("正在準備翻譯模型…");
        // The model downloads only if needed; subsequent use can be entirely offline.
        translator.downloadModelIfNeeded(new DownloadConditions.Builder().requireWifi().build())
                .addOnSuccessListener(ignored -> {
                    if (!running) return;
                    modelReady = true;
                    lastModelStatus = "翻譯模型已就緒";
                    if (!calibrating) showCaption("翻譯已開啟，等待外語字幕…");
                })
                .addOnFailureListener(error -> {
                    modelReady = false;
                    lastModelStatus = "翻譯模型載入失敗：" +
                            error.getClass().getSimpleName() + " " + error.getMessage();
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
        // A fixed two-row panel. Each translated sentence has its OWN
        // single-line TextView, so a long sentence can never wrap into the
        // space reserved for the next sentence.
        captionView = new LinearLayout(this);
        captionView.setOrientation(LinearLayout.VERTICAL);
        captionView.setGravity(Gravity.CENTER);
        captionView.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(225, 12, 18, 28));
        bg.setCornerRadius(dp(11));
        captionView.setBackground(bg);
        captionFirstLine = new TextView(this);
        captionSecondLine = new TextView(this);
        for (TextView line : new TextView[] {captionFirstLine, captionSecondLine}) {
            line.setTextColor(Color.WHITE);
            line.setTextSize(18f);
            line.setGravity(Gravity.CENTER);
            line.setSingleLine(true);
            // Use the whole available width for one complete translated
            // sentence, shrinking modestly before ellipsizing very long text.
            line.setAutoSizeTextTypeUniformWithConfiguration(12, 18, 1,
                    android.util.TypedValue.COMPLEX_UNIT_SP);
            line.setEllipsize(android.text.TextUtils.TruncateAt.END);
            line.setMinHeight(dp(29));
            captionView.addView(line, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        captionFirstLine.setText("準備懸浮字幕…");
        captionSecondLine.setVisibility(View.GONE);
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
        int[] p = SubtitleRoiBounds.pixels(
                fractions.left, fractions.top, fractions.right, fractions.bottom,
                width, height);
        return new Rect(p[0], p[1], p[2], p[3]);
    }

    private void closeSelectionEditor() {
        if (editorRoot != null) {
            try { windowManager.removeView(editorRoot); }
            catch (IllegalArgumentException ignored) { }
            editorRoot = null;
            roiEditor = null;
        }
    }


    /** Compatibility helpers: the old transient editor link is now a persistent ball. */
    private void hideReopenButton() {
        if (translationBubble != null) translationBubble.hide();
    }

    private void showReopenButton() {
        if (!running) return;
        if (translationBubble == null) {
            translationBubble = new SubtitleBubbleController(
                    this, windowManager, new SubtitleBubbleController.Actions() {
                @Override public void onRecalibrate() { openSelectionEditor(); }
                @Override public void onShowDiagnostics() { openLiveDiagnostics(); }
                @Override public void onTogglePaused() { togglePausedTranslation(); }
                @Override public void onStop() { stopSelf(); }
            });
        }
        translationBubble.show();
        translationBubble.updatePaused(pausedByUser);
    }

    private void togglePausedTranslation() {
        pausedByUser = !pausedByUser;
        if (pausedByUser) {
            pendingVoiceSource = "";
            showCaption("翻譯已暫停");
        } else {
            lastSource = "";
            previousCandidate = null;
            stableSubtitleFrames = 0;
            if (twoLineCaptions.hasCaptions()) renderTwoLineCaptions();
            else showCaption(modelReady ? (voiceFallbackEnabled ?
                    "正在辨識影片聲音…" : "等待畫面字幕…") :
                    "正在準備翻譯模型…");
        }
        if (translationBubble != null) translationBubble.updatePaused(pausedByUser);
    }

    /** Editable overlay is never included in the recorded OCR screenshot. */
    private void openSelectionEditor() {
        if (!running || !Settings.canDrawOverlays(this)) return;
        closeLiveDiagnostics();
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
                // The user is leaving the ROI editor for another video,
                // not pausing voice translation. The old code accidentally
                // kept calibrating=true and blocked BOTH engines forever.
                closeSelectionEditor();
                manuallySelecting = false;
                calibrating = false;
                if (captionView != null) captionView.setVisibility(View.VISIBLE);
                showReopenButton();
                showCaption(voiceFallbackEnabled ? "正在辨識影片聲音…" :
                        "OCR 正在自動尋找字幕…");
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
                lastCropStatus = "已確認原始框：" + region.left + "," + region.top +
                        " 至 " + region.right + "," + region.bottom;
                lastOcrStatus = "框選完成，等待第一張 OCR 擷取畫面";
                manuallySelecting = false;
                closeSelectionEditor();
                pausedByUser = false;
                showReopenButton();
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


    private void startPlaybackSpeech() {
        lastVoiceStatus = "正在準備" + ("ja".equals(sourceLanguage) ? "日文" : "英文") + "離線語音補位";
        playbackSpeech = new SubtitlePlaybackSpeech(this, mediaProjection, sourceLanguage,
                new SubtitlePlaybackSpeech.Callback() {
                    @Override public void onStatus(String status) {
                        lastVoiceStatus = status;
                        if (status.contains("無法啟動") ||
                                status.contains("未取得可辨識") ||
                                status.contains("讀取失敗") ||
                                status.contains("無法擷取") ||
                                status.contains("未授權")) {
                            voiceUnavailable = true;
                        }
                    }
                    @Override public void onHypothesis(String original, boolean isFinal) {
                        trace(isFinal ? "Vosk 原始 final" : "Vosk 原始 partial", original);
                    }
                    @Override public void onUtterance(String original, long utteranceId,
                                                      boolean isFinal) {
                        // Vosk audio runs off the main thread; serialize speech
                        // queue and UI/caption updates on the main looper.
                        lastVoiceUtteranceMs = SystemClock.elapsedRealtime();
                        trace("ASR 片語 #" + utteranceId + (isFinal ? " final" : " 預覽"), original);
                        voiceUnavailable = false;
                        main.post(() -> translateVoiceFallback(original, utteranceId, isFinal));
                    }
                });
        playbackSpeech.start();
    }

    /** Latest-only speech coalescing. Slow ML Kit translations no longer drop
     * the next line: while one caption is translating, retain the newest
     * stable ASR hypothesis, then translate it as soon as the engine is free.
     */
    private void translateVoiceFallback(String original, long utteranceId,
                                      boolean isFinal) {
        if (original == null || !running) return;
        String source = original.replaceAll("\\s+", " ").trim();
        int minLength = "ja".equals(sourceLanguage) ? 3 : 5;
        if (source.length() < minLength) return;
        lastSpokenOriginal = source;
        long now = SystemClock.elapsedRealtime();
        if (SubtitleOcrVoiceArbitration.prefersOcr(now, lastTrustedOcrAtMs)) {
            pendingVoiceSource = "";
            if (isFinal) trace("仲裁", "信任畫面字幕，略過語音片語 #" + utteranceId);
            return;
        }
        if (!SubtitleVoicePriority.mayDisplayVoice(voiceFallbackEnabled, modelReady,
                calibrating, pausedByUser, now, lastOcrCaptionMs, lastVoiceCaptionMs)) {
            // Respect pause and editor mode; never build a backlog in these states.
            if (pausedByUser || calibrating) pendingVoiceSource = "";
            return;
        }
        if (utteranceId == pendingVoiceUtteranceId &&
                source.equalsIgnoreCase(pendingVoiceSource)) {
            // A final version of the SAME preview must not be discarded: its
            // stable sentence is what Qwen should correct in the existing row.
            if (isFinal) pendingVoiceIsFinal = true;
            return;
        }
        if (utteranceId == lastSpokenUtteranceId &&
                source.equalsIgnoreCase(lastSpokenSource)) {
            if (isFinal && !voiceTranslateBusy.get()
                    && lastVoiceTranslatedId == utteranceId
                    && !lastVoiceFastChinese.isEmpty())
                requestVoiceAiRefinement(utteranceId, source, lastVoiceFastChinese);
            else if (isFinal && voiceTranslateBusy.get()) {
                pendingVoiceUtteranceId = utteranceId;
                pendingVoiceIsFinal = true;
                pendingVoiceSource = source;
                pendingVoiceQueuedAtMs = now;
            }
            return;
        }
        pendingVoiceUtteranceId = utteranceId;
        pendingVoiceIsFinal = isFinal;
        pendingVoiceSource = source;
        pendingVoiceQueuedAtMs = now;
        drainVoiceTranslationQueue();
    }

    private void drainVoiceTranslationQueue() {
        if (!running || pausedByUser || calibrating ||
                !voiceFallbackEnabled || !modelReady || translator == null) {
            if (pausedByUser || calibrating || !running) pendingVoiceSource = "";
            return;
        }
        if (pendingVoiceSource.isEmpty() || voiceTranslateBusy.get()) return;
        long sinceLast = SystemClock.elapsedRealtime() - lastVoiceCaptionMs;
        if (lastVoiceCaptionMs > 0 && sinceLast < MIN_VOICE_CAPTION_SPACING_MS) {
            if (!voiceRetryScheduled) {
                voiceRetryScheduled = true;
                main.postDelayed(() -> {
                    voiceRetryScheduled = false;
                    drainVoiceTranslationQueue();
                }, MIN_VOICE_CAPTION_SPACING_MS - sinceLast);
            }
            return;
        }

        if (!voiceTranslateBusy.compareAndSet(false, true)) return;
        final String source = pendingVoiceSource;
        final long sourceId = pendingVoiceUtteranceId;
        final boolean sourceFinal = pendingVoiceIsFinal;
        final long queuedAt = pendingVoiceQueuedAtMs;
        pendingVoiceSource = "";
        lastSpokenSource = source;
        lastSpokenUtteranceId = sourceId;
        final long translateStarted = SystemClock.elapsedRealtime();
        translator.translate(source)
                .addOnSuccessListener(translated -> {
                    if (!running || pausedByUser || calibrating) return;
                    String zh = traditionalize(translated);
                    boolean chinese = false;
                    for (int i = 0; i < zh.length(); i++) {
                        char c = zh.charAt(i);
                        if (c >= 0x4e00 && c <= 0x9fff) {
                            chinese = true;
                            break;
                        }
                    }
                    if (!chinese) {
                        lastVoiceStatus = "語音已辨識，但中文翻譯沒有輸出";
                        return;
                    }
                    lastVoiceCaptionMs = SystemClock.elapsedRealtime();
                    lastVoiceTranslatedId = sourceId;
                    lastVoiceFastChinese = zh;
                    lastTranslationResult = zh;
                    long mlKitMs = lastVoiceCaptionMs - translateStarted;
                    long queueMs = Math.max(0, translateStarted - queuedAt);
                    lastVoiceLatencyStatus = "新語音→字幕 " +
                            (lastVoiceCaptionMs - queuedAt) + " ms（含佇列 " +
                            queueMs + " ms、ML Kit " + mlKitMs +
                            " ms，不含 Vosk 初始辨識）";
                    lastVoiceStatus = sourceFinal ? "完整句已更新" : "語音字幕預覽已更新";
                    trace("ML Kit 語音 #" + sourceId + "（" + mlKitMs + "ms）",
                            source + " → " + zh);
                    if (SubtitleOcrVoiceArbitration.prefersOcr(
                            SystemClock.elapsedRealtime(), lastTrustedOcrAtMs)) return;
                    publishVoiceCaption(sourceId, zh);
                    positionVoiceCaption();
                    if (sourceFinal) requestVoiceAiRefinement(sourceId, source, zh);
                })
                .addOnFailureListener(error -> {
                    if (source.equals(lastSpokenSource)) lastSpokenSource = "";
                    lastVoiceStatus = ("ja".equals(sourceLanguage) ? "日文" : "英文") +
                            "語音翻譯失敗：" + error.getClass().getSimpleName();
                })
                .addOnCompleteListener(done -> {
                    voiceTranslateBusy.set(false);
                    // If Vosk delivered the next partial/final phrase during this
                    // translation, process the latest one instead of dropping it.
                    if (!pendingVoiceSource.isEmpty()) drainVoiceTranslationQueue();
                });
    }

    private void requestVoiceAiRefinement(long id, String original, String quickChinese) {
        if (!aiRefineEnabled || !SubtitleLocalAi.modelInstalled(this)) return;
        if (id == lastVoiceAiRequestedId &&
                original.equalsIgnoreCase(lastVoiceAiRequestedSource)) return;
        lastVoiceAiRequestedId = id;
        lastVoiceAiRequestedSource = original;
        final String previous = twoLineCaptions.previousOfVoice(id);
        SubtitleLocalAi.refine(getApplicationContext(), sourceLanguage,
                original, quickChinese, previous, improved -> main.post(() -> {
                    if (!running || pausedByUser || calibrating || !aiRefineEnabled) return;
                    if (twoLineCaptions.reviseVoiceIfUnchanged(id, quickChinese, improved)) {
                        trace("Qwen 語音 #" + id, improved);
                        renderTwoLineCaptions();
                        lastTranslationResult = improved;
                        lastAiStatus = "本地 Qwen 已修正語音字幕第 " + id + " 句";
                    }
                }));
    }

    private void requestOcrAiRefinement(String original, String quickChinese) {
        if (!aiRefineEnabled || !SubtitleLocalAi.modelInstalled(this)) return;
        final String language = sourceLanguage;
        final String previous = twoLineCaptions.previousOfOcr(original);
        SubtitleLocalAi.refine(getApplicationContext(), language,
                original, quickChinese, previous, improved -> main.post(() -> {
                    if (!running || pausedByUser || calibrating || !aiRefineEnabled) return;
                    if (!SubtitleOcrVoiceArbitration.prefersOcr(
                            SystemClock.elapsedRealtime(), lastTrustedOcrAtMs)
                            && !SubtitleVoicePriority.mayDisplayOcr(voiceFallbackEnabled,
                                    SystemClock.elapsedRealtime(), lastVoiceCaptionMs)) return;
                    // Do NOT insert missing captions: Qwen can be slower than
                    // an entire next line and must not restore evicted text.
                    if (twoLineCaptions.reviseOcrIfUnchanged(original, quickChinese, improved)) {
                        trace("Qwen OCR", original + " → " + improved);
                        renderTwoLineCaptions();
                        lastTranslationResult = improved;
                        lastAiStatus = "本地 Qwen 已修正 OCR 中文字幕";
                    }
                }));
    }

    private void positionVoiceCaption() {
        if (captionDraggedByUser) return;
        main.post(() -> {
            if (!running || calibrating || captionView == null || captionParams == null ||
                    captionDraggedByUser) return;
            captionParams.x = Math.max(0, Math.round(width * 0.07f));
            captionParams.y = Math.max(0, Math.round(height * 0.68f));
            try { windowManager.updateViewLayout(captionView, captionParams); }
            catch (IllegalArgumentException ignored) { }
        });
    }


    /** View the actual OCR pixels without leaving the current video player.
     * This is created only after an explicit bubble tap, remains on the device,
     * and is not sent to any server or persisted to media storage.
     */
    private void openLiveDiagnostics() {
        if (!running || diagnosticView != null) return;
        diagnosisWasCalibrating = calibrating;
        calibrating = true; // pause scanning before showing non-SECURE inspector
        try {
            diagnosticView = new ScrollView(this);
            diagnosticView.setFillViewport(false);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.argb(248, 17, 24, 36));
            bg.setCornerRadius(dp(15));
            diagnosticView.setBackground(bg);
            LinearLayout contents = new LinearLayout(this);
            contents.setOrientation(LinearLayout.VERTICAL);
            contents.setPadding(dp(14), dp(12), dp(14), dp(12));
            diagnosticView.addView(contents);

            TextView title = new TextView(this);
            title.setText("字幕 OCR 現場診斷");
            title.setTextColor(Color.WHITE);
            title.setTextSize(20f);
            contents.addView(title);

            TextView explain = new TextView(this);
            explain.setText("下方影像就是 OCR 真正讀取的裁切畫面。此面板開啟期間辨識暫停；可直接截圖傳送，關閉後恢復。");
            explain.setTextColor(Color.LTGRAY);
            explain.setPadding(0, dp(8), 0, dp(8));
            contents.addView(explain);

            Bitmap preview = latestOcrCropPreview;
            if (preview != null && !preview.isRecycled()) {
                ImageView image = new ImageView(this);
                image.setImageBitmap(preview);
                image.setBackgroundColor(Color.DKGRAY);
                image.setAdjustViewBounds(true);
                image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                contents.addView(image, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        Math.min(dp(220), Math.max(dp(86), preview.getHeight()))));
            }

            String when = lastNonemptyRawOcrAtMs == 0L
                    ? "最近沒有成功辨識到文字"
                    : "距最近一次非空原文 " + ((SystemClock.elapsedRealtime()
                        - lastNonemptyRawOcrAtMs) / 1000L) + " 秒";
            TextView details = new TextView(this);
            details.setTextColor(Color.WHITE);
            details.setTextSize(14f);
            details.setPadding(0, dp(9), 0, dp(9));
            details.setText("影格：" + framesCaptured + "；OCR 成功：" + ocrRunsSucceeded +
                    "，錯誤：" + ocrRunsFailed +
                    "\n範圍：" + lastCropStatus +
                    "\n最近一次 OCR：" + latestRawOcrText +
                    "\n最後非空原文：" + lastNonemptyRawOcrText +
                    "\n" + when +
                    "\n模型：" + lastModelStatus +
                    "\n選取：" + lastSelectionStatus +
                    "\n語音：" + lastVoiceStatus +
                    "\n翻譯延遲診斷：" + lastVoiceLatencyStatus +
                    "\n本地 Qwen：" + lastAiStatus +
                    "\n模型狀態：" + SubtitleLocalAi.status(this) +
                    "\n\n【最近 60 筆逐階段診斷（僅記憶體）】\n" +
                    pipelineTrace.snapshot());
            contents.addView(details);

            Button dismiss = new Button(this);
            dismiss.setText("關閉診斷，繼續翻譯");
            dismiss.setOnClickListener(v -> closeLiveDiagnostics());
            contents.addView(dismiss);

            WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                    Math.max(dp(300), Math.min(width - dp(24), dp(440))),
                    Math.max(dp(260), Math.min(height - dp(110), dp(630))),
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            p.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            p.y = dp(55);
            windowManager.addView(diagnosticView, p);
        } catch (Exception error) {
            closeLiveDiagnostics();
            lastOcrStatus = "診斷視窗失敗：" + error.getClass().getSimpleName();
        }
    }

    private void closeLiveDiagnostics() {
        if (diagnosticView != null) {
            try { windowManager.removeView(diagnosticView); }
            catch (IllegalArgumentException ignored) { }
            diagnosticView = null;
            if (running) calibrating = diagnosisWasCalibrating;
        }
    }

    private void showCaption(String text) {
        // This method is for transient model/permission/waiting notices only.
        // Once translated sentences exist, never overwrite the two-line panel
        // with a transient "waiting for subtitle" hint.
        main.post(() -> {
            if (!running || captionView == null || captionFirstLine == null) return;
            if (twoLineCaptions.hasCaptions() && !"翻譯已暫停".equals(text)) return;
            captionFirstLine.setText(text);
            captionSecondLine.setText("");
            captionSecondLine.setVisibility(View.GONE);
        });
    }

    private void renderTwoLineCaptions() {
        if (!running || captionFirstLine == null || captionSecondLine == null) return;
        if (twoLineCaptions.count() == 0) return;
        captionFirstLine.setText(twoLineCaptions.first());
        captionSecondLine.setText(twoLineCaptions.second());
        captionSecondLine.setVisibility(twoLineCaptions.count() == 2 ?
                View.VISIBLE : View.GONE);
    }

    private void publishVoiceCaption(long utteranceId, String translated) {
        // All ML Kit callbacks run on main thread. A final ASR result carries
        // the same utterance ID as its partial hypothesis, so it revises the
        // same row instead of advancing to the next one.
        if (twoLineCaptions.putVoice(utteranceId, translated)) {
            trace("顯示語音 #" + utteranceId, translated);
            renderTwoLineCaptions();
        }
    }

    private void publishOcrCaption(String source, String translated) {
        if (twoLineCaptions.putOcr(source, translated)) {
            trace("顯示 OCR", source + " → " + translated);
            renderTwoLineCaptions();
        }
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
                // Run OCR even when the translation model is not ready:
                // diagnosis should distinguish recognition from model download.
                long now = SystemClock.elapsedRealtime();
                boolean fallbackOcr = recognizer != null && SubtitleVoicePriority.mayScanOcr(
                        voiceFallbackEnabled, voiceUnavailable, calibrating,
                        pausedByUser, now, voiceStartedAtMs, lastVoiceUtteranceMs);
                if (fallbackOcr && voiceFallbackEnabled && !voiceUnavailable)
                    lastSelectionStatus = "語音暫無台詞，正在以 OCR 補充";
                boolean audioActive = voiceFallbackEnabled && !voiceUnavailable && !fallbackOcr;
                boolean passiveProbe = audioActive &&
                        SubtitleOcrVoiceArbitration.shouldProbe(
                                now, lastOcrProbeAtMs, lastTrustedOcrAtMs);
                if ((fallbackOcr || passiveProbe) &&
                        ocrBusy.compareAndSet(false, true)) {
                    lastOcrProbeAtMs = now;
                    captureFrame();
                }
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
            // Orientation must not open a fullscreen editor over videos.
            // The saved rectangle has a different orientation; discard it and
            // use automatic OCR until the user asks to recalibrate.
            activeRoi = null;
            calibrating = false;
            lastCropStatus = "畫面旋轉，已切回自動字幕搜尋；可再框選";
            lastSource = "";
            lastTranslation = "";
            previousCandidate = null;
            if (old != null) old.close();
            main.post(() -> {
                closeSelectionEditor();
                if (translationBubble != null) translationBubble.onDisplayChanged();
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
            if (image == null) {
                if (SystemClock.elapsedRealtime() - lastFrameAvailableMs > 3500L)
                    lastCaptureStatus = "畫面擷取暫無影格（播放器可能禁止擷取或暫停）";
                return;
            }
            lastFrameAvailableMs = SystemClock.elapsedRealtime();
            framesCaptured++;
            lastCaptureStatus = "畫面擷取正常（已取得 " + framesCaptured + " 張影格）";
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
                        roi.width() < 2 || roi.height() < 2) {
                    lastCropStatus = "框選區域不在畫面內，請重新框選";
                    return;
                }
                // OCR often misses dialogue clipped to its pixel bounds.
                // Slightly expand the user-specified strip without scanning
                // the unrelated recommendations further down the page.
                int marginX = Math.min(dp(11), Math.max(2, roi.width() / 30));
                int marginY = Math.min(dp(8), Math.max(2, roi.height() / 6));
                roi.left = Math.max(0, roi.left - marginX);
                roi.right = Math.min(width, roi.right + marginX);
                roi.top = Math.max(0, roi.top - marginY);
                roi.bottom = Math.min(height, roi.bottom + marginY);
                cropX = roi.left;
                rawCropY = roi.top;
                crop = Bitmap.createBitmap(screen,
                        roi.left, roi.top, roi.width(), roi.height());
                // Subtitle font in small selected regions needs more pixels,
                // especially on portrait YouTube and low-resolution streams.
                float scale = Math.min(2.7f,
                        Math.max(2.25f, 2300f / Math.max(1, roi.width())));
                lastCropStatus = "手動框 OCR：" + roi.left + "," + roi.top + " → " +
                        roi.right + "," + roi.bottom + "，放大 " + scale + " 倍";
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
            // Keep a reduced, memory-only preview of exactly what ML Kit sees.
            // The user can inspect it from the floating ball while still on
            // the video; returning to the app changes captured screen content.
            final long previewAt = SystemClock.elapsedRealtime();
            if (previewAt - lastPreviewUpdatedMs > 2500L || latestOcrCropPreview == null) {
            lastPreviewUpdatedMs = previewAt;
            final int previewWidth = Math.min(650, owned.getWidth());
            final int previewHeight = Math.max(1, Math.round(
                    owned.getHeight() * (previewWidth / (float) owned.getWidth())));
            Bitmap nextPreview = (owned.getWidth() == previewWidth &&
                    owned.getHeight() == previewHeight)
                    ? owned.copy(Bitmap.Config.ARGB_8888, false)
                    : Bitmap.createScaledBitmap(owned, previewWidth, previewHeight, false);
            Bitmap previousPreview = latestOcrCropPreview;
            latestOcrCropPreview = nextPreview;
            // Diagnostic panel pauses scanning, so this bitmap won't be swapped
            // while an inspector is open.
            if (previousPreview != null && !previousPreview.isRecycled()) {
                previousPreview.recycle();
            }
            }
            lastOcrStatus = "正在執行 OCR（掃描中）";
            recognizer.process(InputImage.fromBitmap(owned, 0))
                    .addOnSuccessListener(text -> {
                        ocrRunsSucceeded++;
                        int rawLines = 0;
                        for (Text.TextBlock block : text.getTextBlocks()) {
                            rawLines += block.getLines().size();
                        }
                        lastRawOcrLines = rawLines;
                        StringBuilder rawText = new StringBuilder();
                        for (Text.TextBlock block : text.getTextBlocks()) {
                            for (Text.Line line : block.getLines()) {
                                if (rawText.length() > 0) rawText.append(" | ");
                                if (rawText.length() > 600) break;
                                rawText.append(line.getText());
                            }
                        }
                        latestRawOcrText = rawText.length() == 0
                                ? "這一張影格沒有辨識到任何文字" : rawText.toString();
                        if (rawText.length() > 0) {
                            lastNonemptyRawOcrText = latestRawOcrText;
                            lastNonemptyRawOcrAtMs = SystemClock.elapsedRealtime();
                        }
                        lastOcrStatus = "OCR 已完成 " + ocrRunsSucceeded + " 次，原始文字行 " + rawLines;
                        if (rawLines == 0) ocrEmptyFrameStreak++;
                        else ocrEmptyFrameStreak = 0;
                        if (!calibrating) {
                            try {
                                translateRecognizedLines(text, frameCropX, frameCropY,
                                        frameScale, manualRoiActive);
                            } catch (RuntimeException parseError) {
                                lastOcrStatus = "OCR 文字解析失敗：" +
                                        parseError.getClass().getSimpleName() + " " +
                                        parseError.getMessage();
                                ocrRunsFailed++;
                            }
                        }
                    })
                    .addOnFailureListener(error -> {
                        ocrRunsFailed++;
                        lastOcrStatus = "OCR 失敗：" + error.getClass().getSimpleName() +
                                " " + error.getMessage() + "（" + ocrRunsFailed + " 次）";
                    })
                    .addOnCompleteListener(task -> {
                        owned.recycle();
                        ocrBusy.set(false);
                    });
            acceptedByRecognizer = true;
        } catch (Exception captureError) {
            lastCaptureStatus = "擷取影格失敗：" + captureError.getClass().getSimpleName() +
                    " " + captureError.getMessage();
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
        if (!running || calibrating || pausedByUser) return;
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
        lastAcceptedOcrLines = candidates.size();
        if (candidates.isEmpty()) {
            lastOcrStatus = "OCR 完成：原始 " + lastRawOcrLines + " 行，來源語言有效 0 行" +
                    (manualRoiActive ? "；請確認字幕完整落在框內" : "");
        } else {
            lastOcrStatus = "OCR 完成：原始 " + lastRawOcrLines +
                    " 行，有效字幕 " + candidates.size() + " 行";
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
                if (SubtitleVoicePriority.mayDisplayOcr(voiceFallbackEnabled,
                        SystemClock.elapsedRealtime(), lastVoiceCaptionMs))
                    showCaption(voiceFallbackEnabled ? "語音無台詞，等待畫面字幕…" :
                            "等待外語字幕…");
            }
            return;
        }
        lastSubtitleObservedMs = SystemClock.elapsedRealtime();
        // Tracking is based on OCR coordinates, not a fixed screen crop.
        String source = chosen.text.replaceAll("\\s+", " ").trim();
        if (!source.equals(lastRecognizedSource)) trace("OCR 候選", source);
        lastRecognizedSource = source;
        stableSubtitleFrames = previousCandidate != null
                && source.equals(previousCandidate.text)
                ? Math.min(30, stableSubtitleFrames + 1) : 0;
        boolean trustedOcr = SubtitleOcrVoiceArbitration.isTrusted(
                chosen, previousCandidate, stableSubtitleFrames,
                manualRoiActive, width, height);
        previousCandidate = chosen;
        if (source.length() < 3) return;
        if (!modelReady || translator == null) {
            lastSelectionStatus = "已辨識字幕，但翻譯模型尚未就緒：" + lastModelStatus;
            return;
        }
        if (source.equals(lastSource) && twoLineCaptions.containsOcr(source)) {
            // Only preserve priority when that OCR line is truly displayed:
            // an older ML Kit request may have been rejected while voice won.
            if (trustedOcr) lastTrustedOcrAtMs = SystemClock.elapsedRealtime();
            positionCaptionAbove(chosen);
            return;
        }
        if (!trustedOcr && !SubtitleVoicePriority.mayDisplayOcr(voiceFallbackEnabled,
                SystemClock.elapsedRealtime(), lastVoiceCaptionMs)) return;
        if (!translateBusy.compareAndSet(false, true)) return;
        final SubtitleCandidatePolicy.Candidate translatedCandidate = chosen;
        lastSource = source;
        trace(trustedOcr ? "OCR 可信字幕" : "OCR 補位", source);
        translator.translate(source)
                .addOnSuccessListener(translated -> {
                    if (!running || pausedByUser || calibrating ||
                            !source.equals(lastSource)) return;
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
                    if (!trustedOcr && !SubtitleVoicePriority.mayDisplayOcr(voiceFallbackEnabled,
                            SystemClock.elapsedRealtime(), lastVoiceCaptionMs)) return;
                    lastOcrCaptionMs = SystemClock.elapsedRealtime();
                    if (trustedOcr) lastTrustedOcrAtMs = lastOcrCaptionMs;
                    trace("ML Kit OCR", source + " → " + lastTranslation);
                    publishOcrCaption(source, lastTranslation);
                    positionCaptionAbove(translatedCandidate);
                    // Share the SAME downloaded Qwen model with voice
                    // translation; ML Kit displays first, AI revises in place.
                    requestOcrAiRefinement(source, lastTranslation);
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
        closeLiveDiagnostics();
        Bitmap stalePreview = latestOcrCropPreview;
        latestOcrCropPreview = null;
        if (stalePreview != null && !stalePreview.isRecycled()) stalePreview.recycle();
        if (playbackSpeech != null) {
            playbackSpeech.stop();
            playbackSpeech = null;
        }
        // Do not run a large model once subtitles have been stopped.
        SubtitleLocalAi.release();
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
