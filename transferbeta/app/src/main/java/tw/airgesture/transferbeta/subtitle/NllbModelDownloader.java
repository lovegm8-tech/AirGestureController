package tw.airgesture.transferbeta.subtitle;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;

/**
 * Explicit, blocking model installer. Call from a worker thread, never the UI thread.
 * Only caller-provided trusted SHA-256 hashes authorize installation.
 * No download starts unless a complete trusted hash manifest is supplied.
 */
public final class NllbModelDownloader {
    private NllbModelDownloader() {}

    public interface Progress {
        void onProgress(String filename, long received, long total);
    }

    public static void install(Context context, Map<String, String> expectedHashes, Progress progress)
            throws Exception {
        if (expectedHashes == null || expectedHashes.size() != 5)
            throw new IllegalArgumentException("Five trusted SHA-256 hashes required");
        for (String name : NllbOnnxRuntimeProbe.REQUIRED) {
            String hash = expectedHashes.get(name);
            if (hash == null || !hash.matches("[0-9a-fA-F]{64}"))
                throw new IllegalArgumentException("Missing trusted SHA-256: " + name);
        }
        File dir = NllbModelStore.directory(context);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("Cannot create model directory");
        for (String name : NllbOnnxRuntimeProbe.REQUIRED) {
            File target = new File(dir, name);
            String expected = expectedHashes.get(name);
            if (target.isFile() && hashMatches(target, expected)) continue;
            // Tokenizer is not published alongside the four ONNX release assets.
            if (!NllbModelStore.RELEASE_ASSET_SIZES.containsKey(name))
                throw new IllegalStateException("Tokenizer requires a separately verified source: " + name);
            File partial = new File(dir, name + ".partial");
            if (partial.exists() && !partial.delete()) throw new java.io.IOException("Cannot remove old partial file");
            HttpURLConnection connection = null;
            try {
                URL url = new URL(NllbModelStore.RELEASE_BASE_URL + name);
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(20000);
                connection.setReadTimeout(30000);
                connection.setInstanceFollowRedirects(true);
                connection.connect();
                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK)
                    throw new java.io.IOException("HTTP " + connection.getResponseCode());
                long total = NllbModelStore.RELEASE_ASSET_SIZES.get(name);
                long received = 0;
                try (InputStream in = connection.getInputStream();
                     FileOutputStream out = new FileOutputStream(partial)) {
                    byte[] buffer = new byte[65536];
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Download interrupted");
                        received += n;
                        if (received > total) throw new java.io.IOException("Oversized model asset");
                        out.write(buffer, 0, n);
                        if (progress != null) progress.onProgress(name, received, total);
                    }
                    out.getFD().sync();
                }
                if (received != total || !hashMatches(partial, expected))
                    throw new java.io.IOException("Size or SHA-256 mismatch: " + name);
                Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } finally {
                if (connection != null) connection.disconnect();
                if (partial.exists()) partial.delete();
            }
        }
        if (!NllbModelStore.verify(context, expectedHashes))
            throw new java.io.IOException("Model installation verification failed");
    }

    private static boolean hashMatches(File file, String expected) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[65536];
            int n;
            while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
        }
        byte[] bytes = digest.digest();
        StringBuilder actual = new StringBuilder(64);
        for (byte b : bytes) actual.append(String.format(Locale.ROOT, "%02x", b & 255));
        return actual.toString().equalsIgnoreCase(expected);
    }
}
