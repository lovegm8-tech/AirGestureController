package tw.airgesture.transferbeta.subtitle;

import java.util.Locale;

/**
 * Engine boundary for evaluating a dedicated offline translator without
 * changing the working ML Kit subtitle pipeline.
 *
 * No network fallback is permitted. A backend must not claim READY until its
 * model, tokenizer, language routing and inference session are validated.
 */
public interface OfflineTranslationEngine extends AutoCloseable {
    enum State { NOT_INSTALLED, LOADING, READY, FAILED }
    enum SourceLanguage {
        ENGLISH("eng_Latn"), JAPANESE("jpn_Jpan");
        public final String nllbTag;
        SourceLanguage(String tag) { this.nllbTag = tag; }
    }
    final class Result {
        public final String original;
        public final String translatedTraditionalChinese;
        public final long inferenceMillis;
        public final String engineName;
        public Result(String original, String translation, long millis, String engineName) {
            this.original = original;
            this.translatedTraditionalChinese = translation;
            this.inferenceMillis = millis;
            this.engineName = engineName;
        }
    }
    State state();
    String description();
    Result translate(String sourceText, SourceLanguage source) throws Exception;
    @Override void close();

    static SourceLanguage sourceFromCode(String code) {
        String normalized = code == null ? "" : code.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("en")) return SourceLanguage.ENGLISH;
        if (normalized.startsWith("ja")) return SourceLanguage.JAPANESE;
        throw new IllegalArgumentException("Unsupported offline translation language: " + code);
    }
}
