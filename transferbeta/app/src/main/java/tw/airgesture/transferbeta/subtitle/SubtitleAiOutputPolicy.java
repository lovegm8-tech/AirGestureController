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

    public static boolean accept(String text, String quickTranslation) {
        if (text == null || text.length() < 2 || text.length() > 110) return false;
        if (text.contains("原文：") || text.contains("解釋：")
                || text.contains("system:") || text.contains("assistant:")) return false;
        int han = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) >= 0x4e00 && text.charAt(i) <= 0x9fff) han++;
        }
        if (han < 2) return false;
        if (quickTranslation != null && quickTranslation.length() > 8
                && text.length() > quickTranslation.length() * 2.7) return false;
        return true;
    }
}
