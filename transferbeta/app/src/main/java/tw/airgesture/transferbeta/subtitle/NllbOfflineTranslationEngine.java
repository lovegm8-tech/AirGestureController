package tw.airgesture.transferbeta.subtitle;

import android.content.Context;
import android.icu.text.Transliterator;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtSession;
import java.io.File;
import java.util.Map;

/**
 * End-to-end offline NLLB translation adapter.
 *
 * Strict opt-in: requires verified model hashes, native SentencePiece and all
 * four ONNX graphs. Never falls back to ML Kit or silently returns source text.
 * Open and translate on a background worker thread; model loading is expensive.
 */
public final class NllbOfflineTranslationEngine implements OfflineTranslationEngine {
    private final Context appContext;
    private final Map<String, String> trustedHashes;
    private NllbSentencePiece tokenizer;
    private NllbOnnxEncoder encoder;
    private NllbOnnxDecoder decoder;
    private State state = State.NOT_INSTALLED;
    private String detail = "Not initialized";

    public NllbOfflineTranslationEngine(Context context, Map<String, String> trustedHashes) {
        if (context == null) throw new IllegalArgumentException("Context required");
        this.appContext = context.getApplicationContext();
        this.trustedHashes = trustedHashes == null ? null
            : java.util.Collections.unmodifiableMap(new java.util.HashMap<>(trustedHashes));
    }

    /** Must be invoked off the UI thread. */
    public synchronized void initialize() throws Exception {
        if (state == State.READY) return;
        closeResources();
        state = State.LOADING;
        detail = "Verifying NLLB model files";
        try {
            if (trustedHashes == null || !NllbModelStore.verify(appContext, trustedHashes)) {
                state = State.NOT_INSTALLED;
                throw new IllegalStateException("NLLB files missing or SHA-256 verification failed");
            }
            if (!NllbSentencePiece.isNativeAvailable())
                throw new IllegalStateException("SentencePiece native arm64 library unavailable");
            File model = new File(NllbModelStore.directory(appContext), "sentencepiece_bpe.model");
            tokenizer = new NllbSentencePiece(model);
            encoder = new NllbOnnxEncoder(appContext);
            decoder = new NllbOnnxDecoder(appContext);
            state = State.READY;
            detail = "NLLB ONNX and SentencePiece loaded; inference not yet validated on device";
        } catch (Exception | LinkageError e) {
            if (state != State.NOT_INSTALLED) state = State.FAILED;
            detail = e.getClass().getSimpleName() + ": " + e.getMessage();
            closeResources();
            throw e;
        }
    }

    @Override public synchronized State state() { return state; }
    @Override public synchronized String description() { return detail; }

    @Override public synchronized Result translate(String sourceText, SourceLanguage source) throws Exception {
        if (state != State.READY || tokenizer == null || encoder == null || decoder == null)
            throw new IllegalStateException("NLLB engine is not initialized");
        if (source == null) throw new IllegalArgumentException("Source language required");
        long start = android.os.SystemClock.elapsedRealtime();
        NllbTokenCodec.Input input = NllbTokenCodec.encode(tokenizer, source.nllbTag, sourceText);
        int limit = Math.min(256, Math.max(24, input.ids.length * 5));
        try (OrtSession.Result encoded = encoder.encode(input)) {
            OnnxTensor hidden = (OnnxTensor) encoded.get("last_hidden_state")
                .orElseThrow(() -> new IllegalStateException("Encoder missing last_hidden_state"));
            int[] outputIds = decoder.generate(hidden, input.attentionMask, limit);
            String simplified = NllbTokenCodec.decode(tokenizer, outputIds);
            String translated = Transliterator.getInstance("Simplified-Traditional").transliterate(simplified);
            if (translated == null || translated.trim().isEmpty())
                throw new IllegalStateException("NLLB produced empty translation");
            return new Result(sourceText, translated,
                android.os.SystemClock.elapsedRealtime() - start, "NLLB-200 ONNX");
        }
    }

    private void closeResources() {
        if (decoder != null) {
            try { decoder.close(); } catch (Exception ignored) {}
            decoder = null;
        }
        if (encoder != null) {
            try { encoder.close(); } catch (Exception ignored) {}
            encoder = null;
        }
        if (tokenizer != null) {
            tokenizer.close();
            tokenizer = null;
        }
    }

    @Override public synchronized void close() {
        closeResources();
        state = State.NOT_INSTALLED;
        detail = "Closed";
    }
}
