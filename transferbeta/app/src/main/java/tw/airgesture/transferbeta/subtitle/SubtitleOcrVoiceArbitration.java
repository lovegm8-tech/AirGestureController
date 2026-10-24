package tw.airgesture.transferbeta.subtitle;

/** Conservative OCR probes during active audio; use OCR only for stable video text. */
public final class SubtitleOcrVoiceArbitration {
    public static final long PASSIVE_PROBE_MS = 1200L;
    public static final long ACTIVE_PROBE_MS = 650L;
    public static final long TRUST_FRESH_MS = 2100L;
    private SubtitleOcrVoiceArbitration() { }

    public static boolean prefersOcr(long nowMs, long lastTrustedOcrMs) {
        return lastTrustedOcrMs > 0L && nowMs >= lastTrustedOcrMs
                && nowMs - lastTrustedOcrMs <= TRUST_FRESH_MS;
    }

    public static boolean shouldProbe(long nowMs, long lastProbeMs,
                                      long lastTrustedOcrMs) {
        long interval = prefersOcr(nowMs, lastTrustedOcrMs)
                ? ACTIVE_PROBE_MS : PASSIVE_PROBE_MS;
        return lastProbeMs <= 0 || nowMs - lastProbeMs >= interval;
    }

    public static boolean isTrusted(SubtitleCandidatePolicy.Candidate current,
                                    SubtitleCandidatePolicy.Candidate previous,
                                    int stableFrames, boolean manualRoi,
                                    int width, int height) {
        if (current == null || previous == null || width < 1 || height < 1
                || stableFrames < 1 || current.text.length() < 8
                || !current.text.equals(previous.text)) return false;
        if (manualRoi) return true;
        // A high OCR rank is NOT proof of a subtitle. Require two frames
        // with identical text in the video band, away from page controls.
        float x = current.centerX() / (float) width;
        float y = current.centerY() / (float) height;
        float span = (current.right - current.left) / (float) width;
        if (x < 0.20f || x > 0.80f || span < 0.17f) return false;
        boolean portrait = height > width * 1.2f;
        boolean inVideoBand = portrait
                ? ((y >= 0.10f && y <= 0.37f)
                    || (y >= 0.66f && y <= 0.88f))
                : y >= 0.56f && y <= 0.92f;
        return inVideoBand
                && SubtitleCandidatePolicy.score(current, width, height,
                        previous, stableFrames) >= 50.0;
    }
}
