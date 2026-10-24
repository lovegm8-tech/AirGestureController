package tw.airgesture.transferbeta.subtitle;

/** Policy shared by streaming voice ingestion and its on-device translation queue. */
public final class SubtitleVoiceQueuePolicy {
    private SubtitleVoiceQueuePolicy() { }
    /** Incoming older interim results must not replace a newer final/result. */
    public static boolean replace(long pendingId, boolean pendingFinal,
                                  long incomingId, boolean incomingFinal) {
        if (incomingId < pendingId) return false;
        if (incomingId > pendingId) return true;
        return incomingFinal || !pendingFinal;
    }
    /** Vosk's final can equal the last partial verbatim: still finalize it. */
    public static boolean suppress(long priorId, String priorText, boolean priorFinal,
                                   long incomingId, String incomingText, boolean incomingFinal) {
        if (incomingId < priorId) return true;
        return incomingId == priorId &&
                (priorFinal || !incomingFinal) &&
                incomingText.equalsIgnoreCase(priorText);
    }
    /** A fresh sentence should never sit behind an artificial 750ms throttle. */
    public static long throttle(long previousId, long incomingId, boolean incomingFinal,
                                long elapsedSinceLastCaptionMs) {
        if (incomingId != previousId || incomingFinal) return 0L;
        return Math.max(0L, 280L - elapsedSinceLastCaptionMs);
    }
}
