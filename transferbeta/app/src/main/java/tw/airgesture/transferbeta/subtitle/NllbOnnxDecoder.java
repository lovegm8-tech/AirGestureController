package tw.airgesture.transferbeta.subtitle;

import android.content.Context;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * NLLB-200 cached autoregressive greedy decoder.
 * Requires encoder's last_hidden_state and its attention mask.
 * Caller must run on worker thread and ensure models are verified first.
 */
public final class NllbOnnxDecoder implements AutoCloseable {
    private static final int LAYERS = 12;
    private static final int HEADS = 16;
    private static final int HEAD_SIZE = 64;
    private final OrtEnvironment env;
    private static final int EOS = 2;
    private final OrtSession decoder;
    private final OrtSession initializer;
    private final OrtSession embeddingAndHead;

    public NllbOnnxDecoder(Context context) throws OrtException {
        env = OrtEnvironment.getEnvironment();
        File dir = NllbModelStore.directory(context);
        OrtSession dec = null, init = null, head = null;
        try {
            dec = open(dir, "NLLB_decoder.onnx");
            init = open(dir, "NLLB_cache_initializer.onnx");
            head = open(dir, "NLLB_embed_and_lm_head.onnx");
        } catch (OrtException e) {
            if (head != null) head.close();
            if (init != null) init.close();
            if (dec != null) dec.close();
            throw e;
        }
        decoder = dec;
        initializer = init;
        embeddingAndHead = head;
    }

    private OrtSession open(File dir, String name) throws OrtException {
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setMemoryPatternOptimization(false);
            options.setCPUArenaAllocator(false);
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT);
            return env.createSession(new File(dir, name).getAbsolutePath(), options);
        }
    }

    private static OnnxTensor named(OrtSession.Result result, String key) {
        return (OnnxTensor) result.get(key).orElseThrow(
            () -> new IllegalStateException("Missing NLLB graph output: " + key));
    }

    private static int argmax(float[] logits) {
        int best = 0;
        for (int i = 1; i < logits.length; i++) if (logits[i] > logits[best]) best = i;
        return best;
    }

    /**
     * @param encoderState encoder last_hidden_state tensor, owned by caller
     * @param attentionMask source attention mask from NllbTokenCodec
     * @param maxTokens strict generation limit to avoid endless loops
     */
    public int[] generate(OnnxTensor encoderState, long[] attentionMask, int maxTokens)
            throws OrtException {
        if (encoderState == null || attentionMask == null || attentionMask.length == 0)
            throw new IllegalArgumentException("Missing encoder state or attention mask");
        if (maxTokens < 2 || maxTokens > 256)
            throw new IllegalArgumentException("maxTokens must be 1..256");

        Map<String, OnnxTensor> initializerInput = new HashMap<>();
        initializerInput.put("encoder_hidden_states", encoderState);
        try (OrtSession.Result cache = initializer.run(initializerInput);
             OnnxTensor mask = OnnxTensor.createTensor(env, new long[][] { attentionMask });
             // Java multidimensional arrays cannot reliably encode an empty ONNX cache axis.
             // Construct the zero-element tensor with an explicit shape instead.
             OnnxTensor emptyPast = OnnxTensor.createTensor(env,
                 ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder()).asFloatBuffer(),
                 new long[] {1, HEADS, 0, HEAD_SIZE});
             OnnxTensor zeroPreLogits = OnnxTensor.createTensor(env, new float[1][1][1024]);
             OnnxTensor zeroLmIds = OnnxTensor.createTensor(env, new long[1][2]);
             OnnxTensor embeddingMode = OnnxTensor.createTensor(env, new boolean[] { false });
             OnnxTensor lmMode = OnnxTensor.createTensor(env, new boolean[] { true })) {
            ArrayList<Integer> output = new ArrayList<>();
            OrtSession.Result previous = null;
            int inputId = EOS; // NLLB decoder start
            try {
                for (int step = 0; step < maxTokens; step++) {
                    try (OnnxTensor input = OnnxTensor.createTensor(env, new long[][] {{inputId}})) {
                        Map<String, OnnxTensor> embedInput = new HashMap<>();
                        embedInput.put("input_ids", input);
                        embedInput.put("pre_logits", zeroPreLogits);
                        embedInput.put("use_lm_head", embeddingMode);
                        try (OrtSession.Result embedResult = embeddingAndHead.run(embedInput)) {
                            Map<String, OnnxTensor> decoderInput = new HashMap<>();
                            decoderInput.put("input_ids", input);
                            decoderInput.put("encoder_attention_mask", mask);
                            decoderInput.put("embed_matrix", named(embedResult, "embed_matrix"));
                            for (int layer = 0; layer < LAYERS; layer++) {
                                String prefix = "past_key_values." + layer + ".";
                                String present = "present." + layer + ".";
                                decoderInput.put(prefix + "decoder.key", previous == null
                                    ? emptyPast : named(previous, present + "decoder.key"));
                                decoderInput.put(prefix + "decoder.value", previous == null
                                    ? emptyPast : named(previous, present + "decoder.value"));
                                decoderInput.put(prefix + "encoder.key", named(cache, present + "encoder.key"));
                                decoderInput.put(prefix + "encoder.value", named(cache, present + "encoder.value"));
                            }
                            OrtSession.Result current = decoder.run(decoderInput);
                            if (previous != null) previous.close();
                            previous = current;
                        }
                        Map<String, OnnxTensor> headInput = new HashMap<>();
                        headInput.put("input_ids", zeroLmIds);
                        headInput.put("pre_logits", named(previous, "pre_logits"));
                        headInput.put("use_lm_head", lmMode);
                        int predicted;
                        try (OrtSession.Result headResult = embeddingAndHead.run(headInput)) {
                            float[][][] logits = (float[][][]) named(headResult, "logits").getValue();
                            predicted = argmax(logits[0][0]);
                        }
                        if (step > 0 && predicted == EOS) break;
                        // The initial decoder-start pass is constrained to the target
                        // language token. Its predicted logits must not be emitted.
                        if (step == 0) {
                            inputId = NllbTokenCodec.targetTraditionalChineseId();
                        } else {
                            output.add(predicted);
                            inputId = predicted;
                        }
                    }
                }
            } finally {
                if (previous != null) previous.close();
            }
            int[] ids = new int[output.size()];
            for (int i = 0; i < ids.length; i++) ids[i] = output.get(i);
            return ids;
        }
    }

    @Override public void close() throws OrtException {
        try { decoder.close(); }
        finally {
            try { initializer.close(); }
            finally { embeddingAndHead.close(); }
        }
    }
}
