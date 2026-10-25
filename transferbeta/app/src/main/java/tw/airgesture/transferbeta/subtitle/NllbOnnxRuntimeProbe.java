package tw.airgesture.transferbeta.subtitle;

import android.content.Context;
import java.io.File;
import java.util.Map;
import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.TensorInfo;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/**
 * Non-invasive NLLB runtime smoke test. Validates that ONNX Runtime can open
 * the four split NLLB graph files before enabling a translation engine.
 * This does not perform tokenization or translation.
 */
public final class NllbOnnxRuntimeProbe {
    private NllbOnnxRuntimeProbe() {}
    public static final String[] REQUIRED = {
        "NLLB_encoder.onnx", "NLLB_decoder.onnx",
        "NLLB_cache_initializer.onnx", "NLLB_embed_and_lm_head.onnx",
        "sentencepiece_bpe.model"
    };
    public static String inspect(Context context) {
        File dir = new File(context.getFilesDir(), "nllb");
        for (String name : REQUIRED) {
            File f = new File(dir, name);
            if (!f.isFile() || f.length() == 0) return "MISSING: " + name;
        }
        return "FILES_PRESENT_NOT_VALIDATED";
    }
    /** Read actual graph I/O signatures before implementing token-by-token decoding.
     * Invoke only on a worker thread: loading four large ONNX graphs is expensive.
     * Diagnostic output contains model tensor metadata, never user subtitle text.
     */
    public static String describeGraphSignatures(Context context) {
        String check = inspect(context);
        if (!"FILES_PRESENT_NOT_VALIDATED".equals(check)) return check;
        StringBuilder report = new StringBuilder();
        try {
            OrtEnvironment env = OrtEnvironment.getEnvironment();
            File dir = new File(context.getFilesDir(), "nllb");
            for (int i = 0; i < 4; i++) {
                try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
                    options.setCPUArenaAllocator(false);
                    options.setMemoryPatternOptimization(false);
                    try (OrtSession session = env.createSession(new File(dir, REQUIRED[i]).getAbsolutePath(), options)) {
                        report.append(REQUIRED[i]).append('\\n');
                        appendNodes(report, "INPUT", session.getInputInfo());
                        appendNodes(report, "OUTPUT", session.getOutputInfo());
                    }
                }
            }
            return report.toString();
        } catch (Exception e) {
            return "GRAPH_SIGNATURE_FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }
    private static void appendNodes(StringBuilder report, String direction, Map<String, NodeInfo> nodes) {
        for (Map.Entry<String, NodeInfo> entry : nodes.entrySet()) {
            report.append(direction).append(' ').append(entry.getKey()).append(": ");
            if (entry.getValue().getInfo() instanceof TensorInfo) {
                TensorInfo tensor = (TensorInfo) entry.getValue().getInfo();
                report.append(tensor.type).append(' ').append(java.util.Arrays.toString(tensor.getShape()));
            } else {
                report.append(entry.getValue().getInfo().toString());
            }
            report.append('\\n');
        }
    }
    public static String validateGraphs(Context context) {
        String check = inspect(context);
        if (!"FILES_PRESENT_NOT_VALIDATED".equals(check)) return check;
        File dir = new File(context.getFilesDir(), "nllb");
        try {
            OrtEnvironment env = OrtEnvironment.getEnvironment();
            for (int i = 0; i < 4; i++) {
                try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
                    options.setCPUArenaAllocator(false);
                    options.setMemoryPatternOptimization(false);
                    try (OrtSession session = env.createSession(new File(dir, REQUIRED[i]).getAbsolutePath(), options)) {
                        if (session.getInputNames().isEmpty()) return "INVALID_GRAPH: " + REQUIRED[i];
                    }
                }
            }
            return "GRAPHS_LOADED_TOKENIZER_AND_INFERENCE_PENDING";
        } catch (Exception e) {
            return "LOAD_FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }
}
