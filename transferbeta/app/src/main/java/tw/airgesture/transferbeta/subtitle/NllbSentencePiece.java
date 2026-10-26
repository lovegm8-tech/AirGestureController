package tw.airgesture.transferbeta.subtitle;

import java.io.File;

/**
 * JNI-backed SentencePiece tokenizer for the original NLLB sentencepiece_bpe.model.
 * Native library must be compiled with Google SentencePiece for Android arm64-v8a.
 * Fails explicitly when native library or model is unavailable.
 */
public final class NllbSentencePiece implements NllbTokenCodec.SentencePiece, AutoCloseable {
    private static final boolean NATIVE_AVAILABLE;
    static {
        boolean loaded;
        try {
            System.loadLibrary("nllb_sentencepiece");
            loaded = true;
        } catch (UnsatisfiedLinkError e) {
            loaded = false;
        }
        NATIVE_AVAILABLE = loaded;
    }

    private long handle;

    public static boolean isNativeAvailable() { return NATIVE_AVAILABLE; }

    public NllbSentencePiece(File model) {
        if (!NATIVE_AVAILABLE) throw new IllegalStateException("NLLB SentencePiece native library not installed");
        if (model == null || !model.isFile() || model.length() == 0)
            throw new IllegalArgumentException("Missing SentencePiece model");
        handle = nativeOpen(model.getAbsolutePath());
        if (handle == 0L) throw new IllegalStateException("Failed to load SentencePiece model");
    }

    @Override public synchronized int[] encode(String text) {
        requireOpen();
        if (text == null) throw new IllegalArgumentException("Null text");
        int[] tokens = nativeEncode(handle, text);
        if (tokens == null) throw new IllegalStateException("SentencePiece encoding failed");
        return tokens;
    }

    @Override public synchronized String decode(int[] ids) {
        requireOpen();
        if (ids == null) throw new IllegalArgumentException("Null ids");
        String result = nativeDecode(handle, ids);
        if (result == null) throw new IllegalStateException("SentencePiece decoding failed");
        return result;
    }

    private void requireOpen() {
        if (handle == 0L) throw new IllegalStateException("SentencePiece tokenizer closed");
    }

    @Override public synchronized void close() {
        if (handle != 0L) {
            nativeClose(handle);
            handle = 0L;
        }
    }

    private static native long nativeOpen(String modelPath);
    private static native int[] nativeEncode(long handle, String text);
    private static native String nativeDecode(long handle, int[] ids);
    private static native void nativeClose(long handle);
}
