package tw.airgesture.transferbeta.subtitle;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.os.Build;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Explicit opt-in: captures only permitted MEDIA/GAME playback from other apps,
 * NEVER the microphone, then runs offline English Vosk recognition.
 * The 40 MB Apache-2.0 speech model downloads once from its public publisher
 * into the app's private files and is never uploaded.
 */
public final class SubtitlePlaybackSpeech {
    public interface Callback {
        void onStatus(String status);
        void onUtterance(String english);
    }

    private static final String MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip";
    private static final String MODEL_FOLDER = "vosk-model-small-en-us-0.15";
    private static final int SAMPLE_RATE = 16000;
    private final Context context;
    private final MediaProjection projection;
    private final Callback callback;
    private final AtomicBoolean alive = new AtomicBoolean(false);
    private ExecutorService executor;
    private volatile AudioRecord capture;

    public SubtitlePlaybackSpeech(Context context, MediaProjection projection, Callback callback) {
        this.context = context.getApplicationContext();
        this.projection = projection;
        this.callback = callback;
    }

    public void start() {
        if (Build.VERSION.SDK_INT < 29) {
            callback.onStatus("系統版本不支援內部音訊擷取"); return;
        }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            callback.onStatus("未授權播放音訊擷取所需的音訊權限"); return;
        }
        if (!alive.compareAndSet(false, true)) return;
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "subtitle-offline-voice");
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
        executor.execute(this::startRecognizer);
    }

    public void stop() {
        alive.set(false);
        AudioRecord record = capture;
        if (record != null) {
            try { record.stop(); } catch (Exception ignored) { }
        }
        if (executor != null) executor.shutdownNow();
    }

    private File prepareModel() throws Exception {
        File dir = new File(context.getFilesDir(), MODEL_FOLDER);
        File ready = new File(dir, ".ready");
        if (ready.isFile() && new File(dir, "am").isDirectory()
                && new File(dir, "conf").isDirectory()) return dir;
        if (!alive.get()) throw new InterruptedException("cancelled");
        callback.onStatus("第一次啟用：下載英文離線語音模型約 40 MB");
        File download = new File(context.getCacheDir(), "vosk-en-download.partial");
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(MODEL_URL).openConnection();
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(60000);
            conn.setInstanceFollowRedirects(true);
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK)
                throw new java.io.IOException("model HTTP " + conn.getResponseCode());
            long bytes = 0;
            try (InputStream input = new BufferedInputStream(conn.getInputStream());
                 FileOutputStream out = new FileOutputStream(download)) {
                byte[] buffer = new byte[32768];
                int n;
                while (alive.get() && (n = input.read(buffer)) >= 0) {
                    if (n == 0) continue;
                    bytes += n;
                    if (bytes > 70L * 1024 * 1024)
                        throw new java.io.IOException("model archive exceeds 70 MB");
                    out.write(buffer, 0, n);
                }
            }
            if (!alive.get()) throw new InterruptedException("cancelled");
            if (bytes < 10_000_000) throw new java.io.IOException("model archive incomplete");
            callback.onStatus("正在安裝英文離線語音模型");
            File tmpDir = new File(context.getFilesDir(), MODEL_FOLDER + "-installing");
            deleteRecursive(tmpDir);
            if (!tmpDir.mkdirs()) throw new java.io.IOException("cannot create model directory");
            int count = 0;
            long extracted = 0L;
            String canonical = tmpDir.getCanonicalPath() + File.separator;
            try (ZipInputStream zip = new ZipInputStream(
                    new BufferedInputStream(new java.io.FileInputStream(download)))) {
                ZipEntry entry;
                while (alive.get() && (entry = zip.getNextEntry()) != null) {
                    String name = entry.getName().replace('\\', '/');
                    String prefix = MODEL_FOLDER + "/";
                    if (!name.startsWith(prefix)) throw new java.io.IOException("unknown model layout");
                    String relative = name.substring(prefix.length());
                    if (relative.isEmpty()) continue;
                    if (++count > 2000) throw new java.io.IOException("too many model files");
                    File output = new File(tmpDir, relative);
                    if (!output.getCanonicalPath().startsWith(canonical))
                        throw new java.io.IOException("model zip path traversal");
                    if (entry.isDirectory()) {
                        if (!output.isDirectory() && !output.mkdirs())
                            throw new java.io.IOException("mkdir failed");
                    } else {
                        File parent = output.getParentFile();
                        if (!parent.isDirectory() && !parent.mkdirs())
                            throw new java.io.IOException("mkdir failed");
                        try (BufferedOutputStream out =
                                     new BufferedOutputStream(new FileOutputStream(output))) {
                            byte[] block = new byte[32768];
                            int n;
                            while (alive.get() && (n = zip.read(block)) != -1) {
                                extracted += n;
                                if (extracted > 220L * 1024 * 1024)
                                    throw new java.io.IOException("model exceeds extraction limit");
                                out.write(block, 0, n);
                            }
                        }
                    }
                }
            }
            if (!alive.get()) throw new InterruptedException("cancelled");
            if (!new File(tmpDir, "am").isDirectory() ||
                    !new File(tmpDir, "conf").isDirectory())
                throw new java.io.IOException("missing model files");
            deleteRecursive(dir);
            if (!tmpDir.renameTo(dir)) throw new java.io.IOException("model move failed");
            if (!ready.createNewFile()) throw new java.io.IOException("model ready marker failed");
            return dir;
        } finally {
            download.delete();
            if (conn != null) conn.disconnect();
        }
    }

    private static void deleteRecursive(File file) {
        if (!file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursive(child);
        file.delete();
    }

    private static String recognizeText(String json, String key) {
        try { return new JSONObject(json).optString(key, "").trim(); }
        catch (Exception ignored) { return ""; }
    }

    private void startRecognizer() {
        AudioRecord record = null;
        try {
            File modelDir = prepareModel();
            if (!alive.get()) return;
            callback.onStatus("英文語音模型載入中");
            try (Model model = new Model(modelDir.getAbsolutePath());
                 Recognizer recognizer = new Recognizer(model, SAMPLE_RATE)) {
                AudioPlaybackCaptureConfiguration config =
                        new AudioPlaybackCaptureConfiguration.Builder(projection)
                                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                                .build();
                int min = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                if (min <= 0) throw new IllegalStateException("16 kHz playback capture unavailable");
                AudioFormat format = new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build();
                record = new AudioRecord.Builder().setAudioFormat(format)
                        .setBufferSizeInBytes(Math.max(8192, min * 2))
                        .setAudioPlaybackCaptureConfig(config).build();
                if (record.getState() != AudioRecord.STATE_INITIALIZED)
                    throw new IllegalStateException("cannot open playback audio");
                capture = record;
                record.startRecording();
                if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
                    throw new IllegalStateException("audio capture not started");
                callback.onStatus("英文語音補位已啟動（只聽允許擷取的播放聲音）");
                byte[] pcm = new byte[3200]; // 100 ms of 16 kHz mono PCM 16-bit
                long lastPartialAt = 0;
                String lastCandidate = "";
                long lastReportAt = android.os.SystemClock.elapsedRealtime();
                long audibleSamples = 0;
                while (alive.get() && !Thread.currentThread().isInterrupted()) {
                    int n = record.read(pcm, 0, pcm.length, AudioRecord.READ_BLOCKING);
                    if (n <= 0) {
                        if (n < 0) callback.onStatus("播放音訊讀取失敗：" + n);
                        if (n < 0) break;
                        continue;
                    }
                    for (int i = 0; i + 1 < n; i += 2) {
                        if (pcm[i] != 0 || pcm[i+1] != 0) { audibleSamples++; break; }
                    }
                    if (recognizer.acceptWaveForm(pcm, n)) {
                        String sentence = recognizeText(recognizer.getResult(), "text");
                        if (sentence.length() >= 7 && !sentence.equalsIgnoreCase(lastCandidate)) {
                            lastCandidate = sentence;
                            callback.onUtterance(sentence);
                        }
                    } else {
                        long now = android.os.SystemClock.elapsedRealtime();
                        if (now - lastPartialAt >= 2900) {
                            String partial = recognizeText(recognizer.getPartialResult(), "partial");
                            if (partial.length() >= 19 && !partial.equalsIgnoreCase(lastCandidate)) {
                                lastCandidate = partial;
                                callback.onUtterance(partial);
                            }
                            lastPartialAt = now;
                        }
                    }
                    long now = android.os.SystemClock.elapsedRealtime();
                    if (now - lastReportAt > 7000 && audibleSamples == 0) {
                        callback.onStatus("未取得可辨識的播放聲音；播放器可能禁止內部音訊擷取");
                        lastReportAt = now;
                    } else if (audibleSamples > 0) {
                        lastReportAt = now;
                    }
                }
            }
        } catch (Exception error) {
            if (alive.get()) {
                callback.onStatus("語音補位無法啟動：" + error.getClass().getSimpleName()
                        + "（OCR 翻譯仍可使用）");
            }
        } finally {
            capture = null;
            if (record != null) {
                try { record.stop(); } catch (Exception ignored) { }
                record.release();
            }
            alive.set(false);
        }
    }
}
