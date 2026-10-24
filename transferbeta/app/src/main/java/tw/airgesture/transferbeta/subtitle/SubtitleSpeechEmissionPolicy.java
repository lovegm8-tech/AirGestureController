package tw.airgesture.transferbeta.subtitle;

/**
 * Emit words that were stable across consecutive on-device Vosk partial
 * hypotheses instead of waiting for a 2.9-second polling interval.
 * English candidates end at a complete word boundary; Japanese sentences
 * use stable character prefixes because they have no spaces between words.
 */
public final class SubtitleSpeechEmissionPolicy {
    private SubtitleSpeechEmissionPolicy() { }
    public static final long PARTIAL_POLL_MS = 360L;

    public static String stablePartial(String previous, String current, String language) {
        if (previous == null || current == null) return "";
        previous = previous.replaceAll("\\s+", " ").trim();
        current = current.replaceAll("\\s+", " ").trim();
        if (previous.isEmpty() || current.isEmpty()) return "";
        int n = Math.min(previous.length(), current.length());
        int common = 0;
        while (common < n && previous.charAt(common) == current.charAt(common)) common++;
        if (common == 0) return "";
        // Avoid splitting a UTF-16 surrogate pair while comparing Japanese text.
        if (common < current.length() && common > 0
                && Character.isLowSurrogate(current.charAt(common))) common--;
        String stable = current.substring(0, common).trim();
        boolean sameHypothesisTwice = previous.equals(current);
        if ("en".equals(language) && !sameHypothesisTwice) {
            // Only translate completed words, not a half-recognized syllable.
            int lastSpace = stable.lastIndexOf(' ');
            if (lastSpace < 0) return "";
            stable = stable.substring(0, lastSpace).trim();
        }
        int minimum = "ja".equals(language) ? 3 : 5;
        return stable.length() >= minimum ? stable : "";
    }

    public static boolean meaningfulChange(String latestSent, String candidate, String language) {
        if (candidate == null || candidate.isEmpty()) return false;
        if (latestSent == null || latestSent.isEmpty()) return true;
        if (candidate.equalsIgnoreCase(latestSent) ||
                latestSent.startsWith(candidate)) return false;
        if (candidate.startsWith(latestSent)) {
            int minimumMore = "ja".equals(language) ? 2 : 4;
            return candidate.length() - latestSent.length() >= minimumMore;
        }
        return true;
    }
}
