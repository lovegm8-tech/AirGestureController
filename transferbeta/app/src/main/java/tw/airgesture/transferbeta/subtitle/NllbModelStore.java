package tw.airgesture.transferbeta.subtitle;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.Map;

/**
 * Fail-closed model installation verifier.
 * Models are provisioned separately, never silently downloaded from unverified URLs.
 * A future downloader must stage files and atomically promote only verified assets.
 */
public final class NllbModelStore {
    private NllbModelStore() {}
    public static final String MODEL_DIR = "nllb";
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
