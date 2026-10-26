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
