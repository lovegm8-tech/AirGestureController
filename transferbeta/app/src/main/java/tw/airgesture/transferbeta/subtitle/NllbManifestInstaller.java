package tw.airgesture.transferbeta.subtitle;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import org.json.JSONObject;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Installs staged NLLB files from a separately sourced checksum manifest.
 * Manifest must be verified independently by the user against its publisher.
 * Never treats self-computed download hashes as trusted.
 */
public final class NllbManifestInstaller {
    private NllbManifestInstaller() {}

    public static String install(Context context, Uri manifestUri) throws Exception {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
            throw new IllegalStateException("Must run on worker thread");
        if (manifestUri == null) throw new IllegalArgumentException("Manifest missing");
        byte[] bytes;
        try (InputStream input = context.getContentResolver().openInputStream(manifestUri)) {
            if (input == null) throw new java.io.IOException("Cannot open manifest");
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = input.read(buffer)) != -1) {
                if (output.size() + n > 16384) throw new java.io.IOException("Manifest too large");
                output.write(buffer, 0, n);
            }
            bytes = output.toByteArray();
        }
        JSONObject json = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        if (!"RTranslator-2.0.0-NLLB".equals(json.optString("model_release")))
            throw new SecurityException("Unexpected model release identifier");
        JSONObject files = json.getJSONObject("sha256");
        Map<String, String> hashes = new LinkedHashMap<>();
        File staged = new File(context.getFilesDir(), "nllb-unverified");
        for (String name : NllbOnnxRuntimeProbe.REQUIRED) {
            String hash = files.getString(name);
            if (!hash.matches("[a-fA-F0-9]{64}"))
                throw new SecurityException("Invalid SHA-256 for " + name);
            File candidate = new File(staged, name);
            if (!candidate.isFile() || candidate.length() == 0)
                throw new java.io.IOException("Missing staged file: " + name);
            Long expectedSize = NllbModelStore.RELEASE_ASSET_SIZES.get(name);
            if (expectedSize != null && candidate.length() != expectedSize)
                throw new java.io.IOException("Unexpected file size: " + name);
            if (!NllbModelStore.sha256(candidate).equalsIgnoreCase(hash))
                throw new SecurityException("Checksum mismatch: " + name);
            hashes.put(name, hash);
        }
        Map<String, String> graphs = new LinkedHashMap<>();
        for (String name : NllbModelStore.RELEASE_ASSET_SIZES.keySet())
            graphs.put(name, hashes.get(name));
        NllbQuarantineDownloader.promoteVerified(context, graphs);
        try (InputStream tokenizer = new java.io.FileInputStream(
                new File(staged, "sentencepiece_bpe.model"))) {
            NllbModelStore.installVerified(context, "sentencepiece_bpe.model",
                    tokenizer, hashes.get("sentencepiece_bpe.model"));
        }
        if (!NllbModelStore.verify(context, hashes))
            throw new SecurityException("Installed model verification failed");
        SharedPreferences.Editor editor = context.getSharedPreferences(
                "floating_subtitle", Context.MODE_PRIVATE).edit();
        for (Map.Entry<String, String> item : hashes.entrySet())
            editor.putString("nllb_sha256_" + item.getKey(), item.getValue());
        editor.putBoolean("nllb_enabled", false);
        if (!editor.commit()) throw new java.io.IOException("Cannot save verified manifest");
        return "五個模型檔案已安裝並通過 SHA-256 比對。請先執行英文／日文推論測試，再開啟 NLLB。";
    }
}
