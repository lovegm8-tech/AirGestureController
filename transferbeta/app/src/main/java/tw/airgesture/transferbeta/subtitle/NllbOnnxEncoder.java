package tw.airgesture.transferbeta.subtitle;

import android.content.Context;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Runs the actual NLLB-200 encoder path: SentencePiece -> embedding graph -> encoder graph.
 * NOT a complete translator; the cache decoder and LM head must still be implemented.
 * Worker thread only. The caller owns and closes returned Result and this encoder.
 */
public final class NllbOnnxEncoder implements AutoCloseable {
    private final OrtEnvironment environment;
    private final OrtSession embedding;
    private final OrtSession encoder;

    public NllbOnnxEncoder(Context context) throws OrtException {
        environment = OrtEnvironment.getEnvironment();
        File dir = NllbModelStore.directory(context);
        OrtSession embed = null;
        OrtSession enc = null;
        try {
            try (OrtSession.SessionOptions options = options()) {
                embed = environment.createSession(new File(dir, "NLLB_embed_and_lm_head.onnx").getAbsolutePath(), options);
            }
            try (OrtSession.SessionOptions options = options()) {
                enc = environment.createSession(new File(dir, "NLLB_encoder.onnx").getAbsolutePath(), options);
            }
        } catch (OrtException failure) {
            if (enc != null) enc.close();
            if (embed != null) embed.close();
            throw failure;
        }
        embedding = embed;
        encoder = enc;
    }

    private static OrtSession.SessionOptions options() throws OrtException {
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setCPUArenaAllocator(false);
        options.setMemoryPatternOptimization(false);
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT);
        return options;
    }

    /** Caller must close returned encoder result before closing this object. */
    public OrtSession.Result encode(NllbTokenCodec.Input input) throws OrtException {
        if (input == null || input.ids.length == 0) throw new IllegalArgumentException("Empty tokens");
        long[][] ids = new long[][] { input.ids };
        long[][] mask = new long[][] { input.attentionMask };
        try (OnnxTensor idsTensor = OnnxTensor.createTensor(environment, ids);
             OnnxTensor maskTensor = OnnxTensor.createTensor(environment, mask);
             OnnxTensor emptyLogits = OnnxTensor.createTensor(environment, new float[1][1][1024]);
             OnnxTensor useLmHead = OnnxTensor.createTensor(environment, new boolean[] { false })) {
            Map<String, OnnxTensor> embedInputs = new HashMap<>();
            embedInputs.put("input_ids", idsTensor);
            embedInputs.put("pre_logits", emptyLogits);
            embedInputs.put("use_lm_head", useLmHead);
            try (OrtSession.Result embeddings = embedding.run(embedInputs)) {
                OnnxTensor embedMatrix = (OnnxTensor) embeddings.get("embed_matrix")
                    .orElseThrow(() -> new IllegalStateException("Embedding graph missing embed_matrix"));
                Map<String, OnnxTensor> encoderInputs = new HashMap<>();
                encoderInputs.put("input_ids", idsTensor);
                encoderInputs.put("attention_mask", maskTensor);
                encoderInputs.put("embed_matrix", embedMatrix);
                return encoder.run(encoderInputs);
            }
        }
    }

    @Override public void close() throws OrtException {
        try { encoder.close(); } finally { embedding.close(); }
    }
}
