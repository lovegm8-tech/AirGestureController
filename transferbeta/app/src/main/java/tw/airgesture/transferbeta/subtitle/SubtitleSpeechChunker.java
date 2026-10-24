package tw.airgesture.transferbeta.subtitle;

/**
 * Splits a long Vosk utterance into subtitle-sized speech chunks BEFORE Vosk
 * decides the entire utterance is over. Vosk's interim hypothesis contains
 * all speech since its last endpoint; the prefix is consumed exactly once.
 *
 * No timers, Android classes, or network calls: timestamps come from caller.
 */
public final class SubtitleSpeechChunker {
    public interface Listener {
        void onChunk(String text, long id, boolean isFinal);
    }

    private static final long MAX_UNBROKEN_CHUNK_MS = 1450L;
    private final String language;
    private final Listener listener;
    private long currentId = 1L;
    private long chunkStartMs = -1L;
    private String consumedPrefix = "";
    private String current = "";
    private String latestStableFull = "";
    public static final long QUIET_BOUNDARY_MS = 780L;

    public SubtitleSpeechChunker(String language, Listener listener) {
        this.language = language;
        this.listener = listener;
    }

    public void onStable(String fullHypothesis, long elapsedMs) {
        String currentHypothesis = normalize(fullHypothesis);
        if (currentHypothesis.isEmpty()) return;
        latestStableFull = currentHypothesis;
        String suffix = suffixAfterCommitted(currentHypothesis);
        if (suffix == null || suffix.isEmpty()) return;
        if (chunkStartMs < 0) chunkStartMs = elapsedMs;
        if (SubtitleSpeechEmissionPolicy.meaningfulChange(current, suffix, language)) {
            current = suffix;
            listener.onChunk(current, currentId, false);
        }
        // Vosk may wait many seconds to create a "final". Cut a stable,
        // meaningful phrase into its own row without waiting for silence.
        if (!current.isEmpty() && elapsedMs - chunkStartMs >= MAX_UNBROKEN_CHUNK_MS
                && isChunkReady(current)) {
            listener.onChunk(current, currentId, true);
            consumedPrefix = currentHypothesis;
            current = "";
            currentId++;
            chunkStartMs = -1L;
        }
    }

    /**
     * Lightweight playback-energy boundary hint. Called after a real quiet
     * interval, not after every sample; recognition itself continues running.
     * The pending stable phrase is finalized only once. The recognizer's
     * subsequent final output will not replay that already consumed prefix.
     */
    public void onQuiet(long elapsedMs) {
        if (current.isEmpty() || latestStableFull.isEmpty()) return;
        listener.onChunk(current, currentId, true);
        consumedPrefix = latestStableFull;
        current = "";
        currentId++;
        chunkStartMs = -1L;
    }

    public void onFinal(String fullResult) {
        String result = normalize(fullResult);
        String suffix = suffixAfterCommitted(result);
        // Even when a partial and Vosk final are identical, send isFinal so
        // the local Qwen can refine without a second ML Kit translation.
        boolean emittedNewPhrase = false;
        if (suffix != null && !suffix.isEmpty()) {
            listener.onChunk(suffix, currentId, true);
            emittedNewPhrase = true;
        } else if (!current.isEmpty()) {
            listener.onChunk(current, currentId, true);
            emittedNewPhrase = true;
        }
        consumedPrefix = "";
        current = "";
        latestStableFull = "";
        // A quiet boundary may have already finalized and advanced the
        // phrase ID. An empty Vosk final must not advance it AGAIN.
        if (emittedNewPhrase) currentId++;
        chunkStartMs = -1L;
    }

    private String suffixAfterCommitted(String full) {
        if (consumedPrefix.isEmpty()) return full;
        if (full.regionMatches(true, 0, consumedPrefix, 0, consumedPrefix.length()))
            return full.substring(consumedPrefix.length()).trim();
        // The recognizer sometimes revises an already-consumed prefix. Do
        // not replay that old sentence as a new subtitle, particularly if it
        // shortened to a prefix. Wait for fresh, unconsumed audio instead.
        if (consumedPrefix.regionMatches(true, 0, full, 0, full.length()))
            return "";
        // Word-aligned fallback for small corrections in the prefix.
        int common = 0;
        int length = Math.min(full.length(), consumedPrefix.length());
        while (common < length &&
                Character.toLowerCase(full.charAt(common)) ==
                        Character.toLowerCase(consumedPrefix.charAt(common))) common++;
        if (common >= consumedPrefix.length() / 2
                && full.length() > consumedPrefix.length()) {
            int offset = Math.min(consumedPrefix.length(), full.length());
            return full.substring(offset).trim();
        }
        return null;
    }

    private boolean isChunkReady(String text) {
        if ("ja".equals(language)) return text.length() >= 16;
        int spaces = 0;
        for (int i = 0; i < text.length(); i++)
            if (text.charAt(i) == ' ') spaces++;
        return spaces >= 5 && text.length() >= 24;
    }

    private static String normalize(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }
}
