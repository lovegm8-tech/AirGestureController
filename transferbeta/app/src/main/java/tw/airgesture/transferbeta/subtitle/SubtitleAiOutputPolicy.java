package tw.airgesture.transferbeta.subtitle;

/** Quality gate: a local model may only revise one already-visible subtitle row. */
public final class SubtitleAiOutputPolicy {
    private SubtitleAiOutputPolicy() { }

    public static String normalize(String output) {
        if (output == null) return "";
        String text = output.replaceAll("(?s)<think>.*?</think>", "")
                .replaceAll("(?s)<\\|.*?\\|>", "")
                .replaceAll("[\\r\\n\\t]+", " ").trim();
        text = text.replaceFirst("^(?:翻譯|譯文|修正版|繁體中文|答案)[:：]\\s*", "");
        text = text.replaceAll("^[\\\"'「『]+|[\\\"'」』]+$", "").trim();
        text = text.replaceAll("\\s+", " ");
        return text;
    }

    /** Conservative offline gate: do not replace a plausible fast subtitle with
     * a short, malformed or obviously unrelated NLLB completion. */
    public static boolean acceptRevision(String source, String candidate, String quick) {
        String result = normalize(candidate);
        if (!accept(result, quick)) return false;
        if (result.contains("⁇") || result.contains("�") || result.contains("??")) return false;
        if (source == null || source.trim().length() < 5) return false;
        // Short ASR fragments have no reliable context for a second-pass rewrite.
        if (source.trim().split("\\s+").length <= 2
                && source.matches("(?s).*\\p{IsLatin}.*")) return false;
        if (quick != null && quick.length() >= 3) {
            int quickHan = countHan(quick);
            int resultHan = countHan(result);
            if (quickHan >= 3 && resultHan < Math.max(2, quickHan / 2)) return false;
            if (quickHan >= 3 && resultHan > quickHan * 2 + 5) return false;
        }
        return true;
    }

    private static int countHan(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++)
            if (s.charAt(i) >= 0x4e00 && s.charAt(i) <= 0x9fff) n++;
        return n;
    }

    public static boolean accept(String text, String quickTranslation) {
        if (text == null || text.length() < 2 || text.length() > 110) return false;
        if (text.contains("原文：") || text.contains("解釋：")
                || text.contains("system:") || text.contains("assistant:")) return false;
        int han = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) >= 0x4e00 && text.charAt(i) <= 0x9fff) han++;
        }
        if (han < 2) return false;
        if (quickTranslation != null && quickTranslation.length() >= 3
                && text.length() > Math.max(16, quickTranslation.length() * 2.7)) return false;
        return true;
    }
}
