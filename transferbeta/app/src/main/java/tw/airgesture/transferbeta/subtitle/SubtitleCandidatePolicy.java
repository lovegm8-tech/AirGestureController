package tw.airgesture.transferbeta.subtitle;

import java.util.List;

/**
 * Ranks OCR subtitle candidates in screen coordinates without restricting OCR
 * to a hardcoded YouTube/Shorts/app-specific region.
 */
public final class SubtitleCandidatePolicy {
    private SubtitleCandidatePolicy() { }

    public static final class Candidate {
        public final String text;
        public final int left, top, right, bottom;

        public Candidate(String text, int left, int top, int right, int bottom) {
            this.text = text == null ? "" : text.trim();
            this.left = left; this.top = top; this.right = right; this.bottom = bottom;
        }

        public int centerX() { return (left + right) / 2; }
        public int centerY() { return (top + bottom) / 2; }
    }

    public static Candidate best(List<Candidate> candidates, int screenWidth,
                                 int screenHeight, Candidate previous, int stableFrames) {
        if (candidates == null || candidates.isEmpty() || screenWidth < 1 || screenHeight < 1)
            return null;
        Candidate selected = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Candidate candidate : candidates) {
            double score = score(candidate, screenWidth, screenHeight, previous, stableFrames);
            if (score > bestScore) { bestScore = score; selected = candidate; }
        }
        return selected;
    }

    public static double score(Candidate c, int width, int height, Candidate previous, int stableFrames) {
        if (c == null || c.text.length() < 3 || c.text.length() > 280) {
            return Double.NEGATIVE_INFINITY;
        }
        double x = c.centerX() / (double) width;
        double y = c.centerY() / (double) height;
        double span = (c.right - c.left) / (double) width;
        // An OCR line in the middle of the video is more likely to be dialog
        // than channel / navigation UI text hugging a screen edge.
        double score = 24.0 - 31.0 * Math.abs(x - 0.5);
        score += Math.min(c.text.length(), 55) * 0.36;
        score += Math.min(Math.max(span, 0.0), 0.75) * 20.0;
        if (y > 0.95 || y < 0.045) score -= 38.0; // system bars and persistent UI
        else if (width > height) {
            if (y >= 0.57 && y <= 0.91) score += 18.0;
        } else {
            // Standard portrait players occupy roughly the upper 40% of a
            // tall screen. Video captions belong there; title/metadata below
            // the player must not win just because their words are longer.
            if (y >= 0.11 && y <= 0.42) score += 28.0;
            else if (y >= 0.47 && y <= 0.60) score -= 16.0;
            else if (y >= 0.64 && y <= 0.88) score += 3.0; // Shorts captions
            if (x < 0.22 || x > 0.78) score -= 9.0;
        }
        String lower = c.text.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("http") || lower.contains("@") ||
                lower.contains("subscribe") || lower.contains("comments") ||
                lower.contains("views") || lower.contains("share") ||
                lower.contains("like &") || lower.contains("recommended")) score -= 34.0;
        if (c.text.length() <= 7) score -= 10.0;
        // Two-line sentence candidates have a larger line span. Discourage
        // choosing a tiny isolated word over a complete subtitle sentence.
        if (c.text.length() >= 24 && c.text.contains(" ")) score += 6.0;
        if (previous != null) {
            double distance = Math.abs(c.centerX() - previous.centerX()) / (double) width
                    + Math.abs(c.centerY() - previous.centerY()) / (double) height;
            // A subtitle usually stays at the same screen position while text changes.
            if (distance < 0.13) score += 22.0;
            else if (distance < 0.24) score += 10.0;
            // Prefer updates to currently tracked dialogue over static labels.
            if (distance < 0.20 && !c.text.equals(previous.text)) score += 10.0;
            // Static headings/subscriptions are often mistaken for dialogue.
            // Once one OCR string stays unchanged for several frames, compare
            // other dynamic candidates again rather than locking on the title.
            if (distance < 0.13 && c.text.equals(previous.text) && stableFrames >= 3)
                score -= Math.min(44.0, 15.0 + stableFrames * 4.5);
        }
        return score;
    }
}
