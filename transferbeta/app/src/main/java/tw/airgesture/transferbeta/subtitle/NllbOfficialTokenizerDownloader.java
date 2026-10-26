package tw.airgesture.transferbeta.subtitle;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** Fetches the tokenizer pinned to RTranslator v2.00 by its Git object identity. */
public final class NllbOfficialTokenizerDownloader {
    private NllbOfficialTokenizerDownloader() {}
    private static final String URL_STRING =
            "https://raw.githubusercontent.com/niedev/RTranslator/v2.00/app/src/main/assets/sentencepiece_bpe.model";
    private static final long SIZE = 4852054L;
    private static final String GIT_BLOB_SHA1 = "3fc376422b02050741c6aa6ffa9c9ba476167712";

    public static String download(Context context) throws Exception {
        File dir = new File(context.getFilesDir(), "nllb-unverified");
        if (!dir.isDirectory() && !dir.mkdirs())
            throw new java.io.IOException("Cannot create model staging directory");
        File target = new File(dir, "sentencepiece_bpe.model");
        if (target.isFile() && target.length() == SIZE && matchesGitBlob(target))
            return "RTranslator v2.00 tokenizer 已存在，Git 物件驗證成功；SHA-256=" +
                    NllbModelStore.sha256(target);
        File partial = new File(dir, "sentencepiece_bpe.model.partial");
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(URL_STRING).openConnection();
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(60000);
            if (connection.getResponseCode() != 200)
                throw new java.io.IOException("Tokenizer HTTP " + connection.getResponseCode());
            long received = 0;
            try (InputStream input = connection.getInputStream();
                 FileOutputStream output = new FileOutputStream(partial)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    received += count;
                    if (received > SIZE) throw new java.io.IOException("Tokenizer size overflow");
                    output.write(buffer, 0, count);
                }
                output.getFD().sync();
            }
            if (received != SIZE || !matchesGitBlob(partial))
                throw new SecurityException("Tokenizer differs from pinned RTranslator Git object");
            if (target.exists() && !target.delete())
                throw new java.io.IOException("Cannot replace old tokenizer");
            if (!partial.renameTo(target))
                throw new java.io.IOException("Cannot finalize tokenizer");
            return "Tokenizer 下載完成並符合 RTranslator Git 物件；SHA-256=" +
                    NllbModelStore.sha256(target);
        } finally {
            if (connection != null) connection.disconnect();
            if (partial.exists()) partial.delete();
        }
    }

    private static boolean matchesGitBlob(File file) throws Exception {
        if (file.length() != SIZE) return false;
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        digest.update(("blob " + SIZE + "\u0000").getBytes(StandardCharsets.UTF_8));
        try (InputStream input = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
        return GIT_BLOB_SHA1.equals(hex.toString());
    }
}
