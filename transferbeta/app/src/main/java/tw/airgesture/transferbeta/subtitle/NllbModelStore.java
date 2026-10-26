package tw.airgesture.transferbeta.subtitle;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;

/**
 * Fail-closed model installation verifier.
 * Models are provisioned separately, never silently downloaded from unverified URLs.
 * A future downloader must stage files and atomically promote only verified assets.
 */
public final class NllbModelStore {
    private NllbModelStore() {}
    public static final String MODEL_DIR = "nllb";
    /** Release metadata sizes; lengths are NOT cryptographic checksums. */
    public static final Map<String, Long> RELEASE_ASSET_SIZES;
    public static final String RELEASE_BASE_URL = "https://github.com/niedev/RTranslator/releases/download/2.0.0/";
    static {
        Map<String, Long> sizes = new LinkedHashMap<>();
        sizes.put("NLLB_cache_initializer.onnx", 25368443L);
        sizes.put("NLLB_decoder.onnx", 179109694L);
        sizes.put("NLLB_embed_and_lm_head.onnx", 524712277L);
        sizes.put("NLLB_encoder.onnx", 266487014L);
        RELEASE_ASSET_SIZES = Collections.unmodifiableMap(sizes);
    }
    /** Completeness only; SHA-256 verification remains mandatory before use. */
    public static boolean hasCompleteReleaseGraphs(Context context) {
        File dir = directory(context);
        for (Map.Entry<String, Long> entry : RELEASE_ASSET_SIZES.entrySet()) {
            File file = new File(dir, entry.getKey());
            if (!file.isFile() || file.length() != entry.getValue()) return false;
        }
        return true;
    }
    /**
     * Installs a single model from a caller-owned stream without exposing partially
     * downloaded files to the inference engine. Call on a worker thread.
     * A pinned SHA-256 is mandatory; never trust filename or content length alone.
     */
    public static synchronized void installVerified(
            Context context, String filename, java.io.InputStream source,
            String expectedSha256) throws Exception {
        if (context == null || source == null) throw new IllegalArgumentException("Missing installation input");
        if (expectedSha256 == null || !expectedSha256.matches("[a-fA-F0-9]{64}"))
            throw new IllegalArgumentException("Expected SHA-256 is required");
        boolean allowed = false;
        for (String name : NllbOnnxRuntimeProbe.REQUIRED) {
            if (name.equals(filename)) { allowed = true; break; }
        }
        if (!allowed) throw new IllegalArgumentException("Unknown NLLB model file");
        File dir = directory(context);
        if (!dir.isDirectory() && !dir.mkdirs())
            throw new java.io.IOException("Cannot create NLLB directory");
        File destination = new File(dir, filename);
        File staging = new File(dir, filename + ".part");
        java.security.MessageDigest sha = java.security.MessageDigest.getInstance("SHA-256");
        long bytes = 0;
        try {
            try (java.io.InputStream in = source;
                 java.io.FileOutputStream out = new java.io.FileOutputStream(staging)) {
                byte[] buffer = new byte[65536];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    if (n == 0) continue;
                    bytes += n;
                    if (bytes > 1_200_000_000L)
                        throw new java.io.IOException("NLLB file exceeds safety limit");
                    sha.update(buffer, 0, n);
                    out.write(buffer, 0, n);
                }
                out.getFD().sync();
            }
            StringBuilder hex = new StringBuilder(64);
            for (byte b : sha.digest())
                hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
            if (bytes == 0 || !hex.toString().equalsIgnoreCase(expectedSha256))
                throw new SecurityException("NLLB model SHA-256 mismatch: " + filename);
            if (!staging.renameTo(destination))
                throw new java.io.IOException("Unable to promote verified model");
        } finally {
            if (staging.exists()) staging.delete();
        }
    }

    public static File directory(Context context) { return new File(context.getFilesDir(), MODEL_DIR); }
    public static boolean verify(Context context, Map<String,String> expectedSha256) throws Exception {
        if (expectedSha256 == null || expectedSha256.size() != NllbOnnxRuntimeProbe.REQUIRED.length)
            return false;
        File dir = directory(context);
        for (String name : NllbOnnxRuntimeProbe.REQUIRED) {
            String expected = expectedSha256.get(name);
            if (expected == null || !expected.matches("[a-fA-F0-9]{64}")) return false;
            File file = new File(dir, name);
            if (!file.isFile() || file.length() == 0) return false;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream in = new FileInputStream(file)) {
                byte[] buffer = new byte[65536];
                int read;
                while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
            }
            byte[] hash = digest.digest();
            StringBuilder hex = new StringBuilder(64);
            for (byte b : hash) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
            if (!hex.toString().equalsIgnoreCase(expected)) return false;
        }
        return true;
    }
}
