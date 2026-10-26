package tw.airgesture.transferbeta.subtitle;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;

/** Downloads official release graphs into quarantine, never into the trusted model directory. */
public final class NllbQuarantineDownloader {
    private NllbQuarantineDownloader() {}
    public interface Progress {
        void update(String file, long received, long total);
    }
    /**
     * Promote quarantined graphs only after comparing all four SHA-256 values
     * against a separately trusted publisher manifest. Never self-pin hashes.
     */
    public static void promoteVerified(Context context, Map<String, String> trustedHashes)
            throws Exception {
        if (trustedHashes == null || trustedHashes.size() != NllbModelStore.RELEASE_ASSET_SIZES.size())
            throw new SecurityException("Four independently trusted graph hashes required");
        File quarantine = new File(context.getFilesDir(), "nllb-unverified");
        for (Map.Entry<String, Long> entry : NllbModelStore.RELEASE_ASSET_SIZES.entrySet()) {
            File candidate = new File(quarantine, entry.getKey());
            String trusted = trustedHashes.get(entry.getKey());
            if (trusted == null || !trusted.matches("[a-fA-F0-9]{64}"))
                throw new SecurityException("Missing trusted hash: " + entry.getKey());
            if (!candidate.isFile() || candidate.length() != entry.getValue())
                throw new java.io.IOException("Missing or incomplete: " + entry.getKey());
            if (!NllbModelStore.sha256(candidate).equalsIgnoreCase(trusted))
                throw new SecurityException("Publisher SHA-256 mismatch: " + entry.getKey());
        }
        for (String name : NllbModelStore.RELEASE_ASSET_SIZES.keySet()) {
            try (InputStream input = new java.io.FileInputStream(new File(quarantine, name))) {
                NllbModelStore.installVerified(context, name, input, trustedHashes.get(name));
            }
        }
    }

    public static String download(Context context, Progress progress) throws Exception {
        File quarantine = new File(context.getFilesDir(), "nllb-unverified");
        if (!quarantine.isDirectory() && !quarantine.mkdirs())
            throw new java.io.IOException("Cannot create staging directory");
        StringBuilder report = new StringBuilder();
        for (Map.Entry<String, Long> entry : NllbModelStore.RELEASE_ASSET_SIZES.entrySet()) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Cancelled");
            String name = entry.getKey();
            long expectedSize = entry.getValue();
            File destination = new File(quarantine, name);
            if (destination.isFile() && destination.length() == expectedSize) {
                report.append(name).append(" SHA-256=")
                      .append(NllbModelStore.sha256(destination)).append('\n');
                if (progress != null) progress.update(name, expectedSize, expectedSize);
                continue;
            }
            File partial = new File(quarantine, name + ".partial");
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(
                    NllbModelStore.RELEASE_BASE_URL + name).openConnection();
                connection.setConnectTimeout(20000);
                connection.setReadTimeout(60000);
                connection.setInstanceFollowRedirects(true);
                if (connection.getResponseCode() != 200)
                    throw new java.io.IOException("HTTP " + connection.getResponseCode() + ": " + name);
                long received = 0;
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (InputStream input = connection.getInputStream();
                     FileOutputStream output = new FileOutputStream(partial)) {
                    byte[] buffer = new byte[65536];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Cancelled");
                        received += count;
                        if (received > expectedSize) throw new java.io.IOException("Unexpected file size");
                        digest.update(buffer, 0, count);
                        output.write(buffer, 0, count);
                        if (progress != null) progress.update(name, received, expectedSize);
                    }
                    output.getFD().sync();
                }
                if (received != expectedSize) throw new java.io.IOException("Incomplete file: " + name);
                if (!partial.renameTo(destination))
                    throw new java.io.IOException("Unable to finalize: " + name);
                StringBuilder hash = new StringBuilder();
                for (byte b : digest.digest())
                    hash.append(String.format(Locale.ROOT, "%02x", b & 255));
                report.append(name).append(" SHA-256=").append(hash).append('\n');
            } finally {
                if (connection != null) connection.disconnect();
                if (partial.exists()) partial.delete();
            }
        }
        return report.append("UNVERIFIED: downloaded graphs are quarantined. ")
            .append("A trusted manifest and matching tokenizer are required before inference.").toString();
    }
}
