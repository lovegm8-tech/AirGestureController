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

    /** Never promote a speculative preview to a confirmed sentence merely
     * because both engines share a short prefix. Confirmation remains the
     * responsibility of the final recognizer. */
    public static boolean canReuseTranslation(String preview, String confirmed) {
        String a = normalize(preview).toLowerCase(java.util.Locale.ROOT);
        String b = normalize(confirmed).toLowerCase(java.util.Locale.ROOT);
        return !a.isEmpty() && a.equals(b);
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

    /** A streaming model may revise its hypothesis repeatedly. Use a stable
     * prefix to prefetch translation, but never treat it as final text. */
    public static String stablePrefix(String previous, String current) {
        String a = normalize(previous);
        String b = normalize(current);
        if (a.isEmpty() || b.isEmpty()) return "";
        int n = Math.min(a.length(), b.length());
        int i = 0;
        while (i < n && Character.toLowerCase(a.charAt(i)) ==
                Character.toLowerCase(b.charAt(i))) i++;
        if (i < 12) return "";
        if (i == b.length()) return b;
        int boundary = b.lastIndexOf(' ', i - 1);
        return boundary >= 12 ? b.substring(0, boundary).trim() : "";
    }

    public static boolean shouldRevise(String preview, String confirmed) {
        return !normalize(preview).equalsIgnoreCase(normalize(confirmed))
                && samePhrase(preview, confirmed);
    }
}
