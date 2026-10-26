package tw.airgesture.transferbeta.subtitle;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.io.FileInputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One-tap installation for previously downloaded RTranslator NLLB assets.
 *
 * These pinned SHA-256 values were recorded from the user's actual device
 * downloads of the official release URLs. They are consistency fingerprints,
 * not a separately signed publisher manifest. The tokenizer is additionally
 * pinned to the official RTranslator v2.00 Git blob by its downloader.
 */
public final class NllbDeviceInstaller {
    private NllbDeviceInstaller() {}

    private static final Map<String, String> EXPECTED;
    static {
        Map<String, String> hashes = new LinkedHashMap<>();
        hashes.put("NLLB_cache_initializer.onnx",
                "4150b3cba7db9487a94e3b693f79d0f89772e75d80bffd0e60b06de1b574e3d9");
        hashes.put("NLLB_decoder.onnx",
                "86028825495fc34085c7ebb80fb5f0eaa18ac6600f9d4dbc55bc731cc6981159");
        hashes.put("NLLB_embed_and_lm_head.onnx",
                "2745a6f932237b8bb0c35ded54987ea3fc455b696215d48d32271c28c785dd50");
        hashes.put("NLLB_encoder.onnx",
                "9b25d62f4875e5087ffde53c2794308c07f60f4c77670fc5bbfe308a1ef88cd9");
        hashes.put("sentencepiece_bpe.model",
                "14bb8dfb35c0ffdea7bc01e56cea38b9e3d5efcdcb9c251d6b40538e1aab555a");
        EXPECTED = Collections.unmodifiableMap(hashes);
    }

    public interface Progress { void update(String message); }

    public static String installAndTest(Context context, Progress progress) throws Exception {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
            throw new IllegalStateException("NLLB installation must run in background");
        Context app = context.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences("floating_subtitle", Context.MODE_PRIVATE);
        prefs.edit().putBoolean("nllb_enabled", false).apply();
        File staged = new File(app.getFilesDir(), "nllb-unverified");
        File installed = NllbModelStore.directory(app);

        for (String name : NllbOnnxRuntimeProbe.REQUIRED) {
            File source = new File(staged, name);
            File existing = new File(installed, name);
            if (progress != null) progress.update("驗證 " + name);
            File candidate = source.isFile() ? source : existing;
            if (!candidate.isFile()) throw new java.io.IOException("模型缺失：" + name);
            Long size = NllbModelStore.RELEASE_ASSET_SIZES.get(name);
            if (size != null && candidate.length() != size)
                throw new java.io.IOException("檔案大小不正確：" + name);
            if (!NllbModelStore.sha256(candidate).equalsIgnoreCase(EXPECTED.get(name)))
                throw new SecurityException("模型校驗不符：" + name);
        }

        for (String name : NllbOnnxRuntimeProbe.REQUIRED) {
            File target = new File(installed, name);
            if (target.isFile() && NllbModelStore.sha256(target).equalsIgnoreCase(EXPECTED.get(name)))
                continue;
            if (progress != null) progress.update("安裝 " + name);
            try (FileInputStream input = new FileInputStream(new File(staged, name))) {
                NllbModelStore.installVerified(app, name, input, EXPECTED.get(name));
            }
        }
        if (!NllbModelStore.verify(app, EXPECTED))
            throw new SecurityException("正式安裝後校驗失敗");

        SharedPreferences.Editor editor = prefs.edit();
        for (Map.Entry<String, String> entry : EXPECTED.entrySet())
            editor.putString("nllb_sha256_" + entry.getKey(), entry.getValue());
        if (!editor.commit()) throw new java.io.IOException("無法儲存模型驗證資料");

        if (progress != null) progress.update("模型安裝完成，正在執行英文／日文推論");
        StringBuilder result = new StringBuilder("五個模型已安裝並通過與已記錄雜湊值比對。\n");
        try (NllbOfflineTranslationEngine engine = new NllbOfflineTranslationEngine(app, EXPECTED)) {
            engine.initialize();
            for (OfflineTranslationEngine.SourceLanguage language :
                    new OfflineTranslationEngine.SourceLanguage[]{
                        OfflineTranslationEngine.SourceLanguage.ENGLISH,
                        OfflineTranslationEngine.SourceLanguage.JAPANESE}) {
                String sample = language == OfflineTranslationEngine.SourceLanguage.ENGLISH
                        ? "Good morning. How are you?" : "おはようございます。お元気ですか。";
                OfflineTranslationEngine.Result translation = engine.translate(sample, language);
                result.append(language.name()).append("：")
                      .append(translation.translatedTraditionalChinese).append(" (")
                      .append(translation.inferenceMillis).append(" ms)\n");
            }
            prefs.edit().putBoolean("nllb_enabled", true).commit();
            result.append("英文與日文推論均成功，已啟用 NLLB；重新啟動懸浮翻譯後生效。");
        } catch (Exception | LinkageError failure) {
            prefs.edit().putBoolean("nllb_enabled", false).apply();
            result.append("模型安裝成功，但實際推論失敗：")
                  .append(failure.getClass().getSimpleName()).append("：")
                  .append(failure.getMessage())
                  .append("\n已保持 NLLB 關閉，ML Kit 可繼續使用。");
        }
        return result.toString();
    }
}
