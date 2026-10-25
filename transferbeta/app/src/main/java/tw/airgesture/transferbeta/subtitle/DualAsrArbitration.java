package tw.airgesture.transferbeta.subtitle;

/**
 * Pure policy for dual-engine captions. Streaming Zipformer hypotheses are
 * speculative; SenseVoice confirms the phrase. No second subtitle is inserted.
 */
public final class DualAsrArbitration {
    private DualAsrArbitration() {}

    public static String normalize(String text) {
        return text == null ? "" : text.trim().replaceAll("\\s+", " ");
    }

    public static boolean stablePreview(String previous, String current) {
        String a = normalize(previous).toLowerCase(java.util.Locale.ROOT);
        String b = normalize(current).toLowerCase(java.util.Locale.ROOT);
        return b.length() >= 12 && a.equals(b);
    }

    public static boolean samePhrase(String preview, String confirmed) {
        String a = normalize(preview).toLowerCase(java.util.Locale.ROOT);
        String b = normalize(confirmed).toLowerCase(java.util.Locale.ROOT);
        if (a.isEmpty() || b.isEmpty()) return false;
        if (a.equals(b)) return true;
        int shared = 0;
        int limit = Math.min(a.length(), b.length());
        while (shared < limit && a.charAt(shared) == b.charAt(shared)) shared++;
        return shared >= 12 && shared * 5 >= Math.min(a.length(), b.length()) * 3;
    }

    public static boolean shouldRevise(String preview, String confirmed) {
        return !normalize(preview).equalsIgnoreCase(normalize(confirmed))
                && samePhrase(preview, confirmed);
    }
}
