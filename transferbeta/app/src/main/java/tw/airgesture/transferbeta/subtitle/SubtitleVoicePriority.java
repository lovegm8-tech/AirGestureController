package tw.airgesture.transferbeta.subtitle;

/** OCR is authoritative; spoken words may fill gaps, never overwrite live OCR. */
public final class SubtitleVoicePriority {
    private SubtitleVoicePriority() { }

    public static boolean mayDisplayVoice(boolean voiceEnabled, boolean modelReady,
                                          boolean editing, boolean paused,
                                          long nowMs, long lastOcrMs,
                                          long lastVoiceMs) {
        if (!voiceEnabled || !modelReady || editing || paused) return false;
        // If OCR is keeping up, audio transcription must not replace it.
        if (lastOcrMs > 0 && nowMs - lastOcrMs < 2300) return false;
        // Do not flicker between partial words and finalized utterances.
        return lastVoiceMs <= 0 || nowMs - lastVoiceMs >= 1900;
    }
}
