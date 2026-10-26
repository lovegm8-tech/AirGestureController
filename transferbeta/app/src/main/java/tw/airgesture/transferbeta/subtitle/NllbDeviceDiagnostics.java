package tw.airgesture.transferbeta.subtitle;

import android.content.Context;
import android.os.SystemClock;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Explicit on-device diagnostic, invoked on a worker thread only.
 * Does not install, trust, or silently enable unverified models.
 * Never includes user subtitles in diagnostic output.
 */
public final class NllbDeviceDiagnostics {
    private NllbDeviceDiagnostics() {}

    public interface Listener {
        void onStage(String stage);
    }

    public static String inventory(Context context, Listener listener) throws Exception {
        File dir = NllbModelStore.directory(context);
        StringBuilder report = new StringBuilder("NLLB device inventory\n");
        for (String name : NllbOnnxRuntimeProbe.REQUIRED) {
            if (listener != null) listener.onStage("檢查 " + name);
            File file = new File(dir, name);
            report.append(name).append(": ");
            if (!file.isFile()) {
                report.append("MISSING\n");
                continue;
            }
            report.append(file.length()).append(" bytes; SHA-256=")
                    .append(NllbModelStore.sha256(file)).append('\n');
            Long expectedSize = NllbModelStore.RELEASE_ASSET_SIZES.get(name);
            if (expectedSize != null && file.length() != expectedSize) {
                report.append("WARNING: unexpected release size\n");
            }
        }
        report.append("Hashes above are fingerprints only; compare with trusted publisher manifest.\n");
        return report.toString();
    }

    public static String runTranslationSmokeTest(
            Context context, Map<String, String> trustedHashes, Listener listener) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
            throw new IllegalStateException("Run NLLB diagnostics off the main thread");
        if (trustedHashes == null || trustedHashes.size() != NllbOnnxRuntimeProbe.REQUIRED.length)
            return "NOT_VERIFIED: complete trusted SHA-256 manifest required";
        try {
            if (listener != null) listener.onStage("驗證五個模型 SHA-256");
            if (!NllbModelStore.verify(context, trustedHashes))
                return "NOT_VERIFIED: file missing or trusted SHA-256 mismatch";
            StringBuilder report = new StringBuilder("NLLB on-device smoke test\n");
            try (NllbOfflineTranslationEngine engine =
                         new NllbOfflineTranslationEngine(context, trustedHashes)) {
                if (listener != null) listener.onStage("載入 ONNX 和 SentencePiece");
                long loadStart = SystemClock.elapsedRealtime();
                engine.initialize();
                report.append("load_ms=").append(SystemClock.elapsedRealtime() - loadStart)
                        .append('\n');
                OfflineTranslationEngine.SourceLanguage[] languages = {
                    OfflineTranslationEngine.SourceLanguage.ENGLISH,
                    OfflineTranslationEngine.SourceLanguage.JAPANESE
                };
                String[] samples = {
                    "Good morning. How are you?",
                    "おはようございます。お元気ですか。"
                };
                for (int i = 0; i < samples.length; i++) {
                    if (Thread.currentThread().isInterrupted()) return "CANCELLED";
                    if (listener != null) listener.onStage(
                            i == 0 ? "測試英文翻譯" : "測試日文翻譯");
                    OfflineTranslationEngine.Result result =
                            engine.translate(samples[i], languages[i]);
                    report.append(i == 0 ? "EN" : "JA").append(": ")
                            .append(result.translatedTraditionalChinese)
                            .append(" (").append(result.inferenceMillis).append(" ms)\n");
                }
            }
            return report.toString();
        } catch (Exception | LinkageError error) {
            return "INFERENCE_FAILED: " + error.getClass().getSimpleName()
                    + ": " + String.valueOf(error.getMessage());
        }
    }
}
