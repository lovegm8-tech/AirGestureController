package tw.airgesture.transferbeta.subtitle;

/** Voice-first scheduler with passive OCR fallback. All times are elapsedRealtime milliseconds. */
public final class SubtitleVoicePriority {
    private SubtitleVoicePriority() { }
    public static final long STARTUP_GRACE_MS = 5500L;
    public static final long VOICE_FRESH_MS = 5400L;

    /** ASR overrides OCR when audio is working, but never while the UI is editing/paused. */
    public static boolean mayDisplayVoice(boolean voiceEnabled, boolean modelReady,
                                          boolean editing, boolean paused,
                                          long nowMs, long lastOcrMs, long lastVoiceMs) {
        // The subtitle service owns the 750 ms display cadence and queues
        // incremental hypotheses. Do not drop words merely because a caption
        // was displayed recently.
        return voiceEnabled && modelReady && !editing && !paused;
    }

    /** Only run expensive image recognition if audio is disabled, absent or stale. */
    public static boolean mayScanOcr(boolean voiceEnabled, boolean voiceUnavailable,
                                     boolean editing, boolean paused, long nowMs,
                                     long sessionStartedMs, long lastVoiceUtteranceMs) {
        if (editing || paused) return false;
        if (!voiceEnabled || voiceUnavailable) return true;
        if (lastVoiceUtteranceMs > 0)
            return nowMs - lastVoiceUtteranceMs >= VOICE_FRESH_MS;
        return nowMs - sessionStartedMs >= STARTUP_GRACE_MS;
    }

    /** Image subtitles cannot overwrite recently translated speech. */
    public static boolean mayDisplayOcr(boolean voiceEnabled, long nowMs,
                                        long lastVoiceCaptionMs) {
        return !voiceEnabled || lastVoiceCaptionMs <= 0
                || nowMs - lastVoiceCaptionMs >= VOICE_FRESH_MS;
    }
}
