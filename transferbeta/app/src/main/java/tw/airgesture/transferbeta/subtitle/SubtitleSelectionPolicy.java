package tw.airgesture.transferbeta.subtitle;

import java.util.ArrayList;
import java.util.List;

/**
 * Separates dialogue rendered inside a portrait video from the surrounding
 * page UI. The full screen is still OCR-scanned, but non-dialogue page text
 * must never become a fallback translation.
 */
public final class SubtitleSelectionPolicy {
    private SubtitleSelectionPolicy() { }

    /**
     * Normal vertical video pages often show search/tabs/navigation after
     * the top embedded player and a recommended-feed card further down.
     * In a full-screen Short this page-like text combination is absent.
     */
    public static boolean isEmbeddedPlayerPage(
            List<SubtitleCandidatePolicy.Candidate> recognizedLines,
            int width, int height) {
        if (recognizedLines == null || width <= 0 || height <= width * 1.2f)
            return false;
        int middleCount = 0, lowerCount = 0, middleEdgeCount = 0;
        for (SubtitleCandidatePolicy.Candidate line : recognizedLines) {
            float y = line.centerY() / (float) height;
            float x = line.centerX() / (float) width;
            if (y >= 0.37f && y <= 0.64f) {
                middleCount++;
                if (x < 0.36f || x > 0.66f) middleEdgeCount++;
            } else if (y >= 0.65f && y <= 0.92f) {
                lowerCount++;
            }
        }
        return middleCount >= 3
                || (middleCount >= 2 && (middleEdgeCount >= 1 || lowerCount >= 1))
                || (middleCount >= 1 && lowerCount >= 2)
                || (middleCount >= 1 && middleEdgeCount >= 1 && lowerCount >= 1);
    }

    /**
     * No caption is better than translating page recommendations or search
     * chips. Shorts are allowed below the player band only when no embedded
     * video page was detected.
     */
    public static SubtitleCandidatePolicy.Candidate select(
            List<SubtitleCandidatePolicy.Candidate> candidates,
            int width, int height,
            SubtitleCandidatePolicy.Candidate previous, int stableFrames,
            boolean embeddedPage, boolean playerOnlyPass) {
        if (candidates == null) return null;
        List<SubtitleCandidatePolicy.Candidate> accepted = new ArrayList<>();
        boolean portrait = height > width * 1.2f;
        for (SubtitleCandidatePolicy.Candidate c : candidates) {
            float y = c.centerY() / (float) height;
            if (portrait && (embeddedPage || playerOnlyPass)) {
                // The bottom of the embedded player in the recorded S26 Ultra
                // portrait layout is around 37.5%. Do not translate text
                // from the search results immediately below it.
                if (y > 0.375f || y < 0.075f) continue;
            }
            if (portrait && y >= 0.375f && y < 0.65f) continue;
            double score = SubtitleCandidatePolicy.score(
                    c, width, height, previous, stableFrames);
            if (score < (portrait ? 35.0 : 20.0)) continue;
            accepted.add(c);
        }
        return SubtitleCandidatePolicy.best(
                accepted, width, height, previous, stableFrames);
    }
}
