package tw.airgesture.transferbeta.subtitle;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Converts one potentially very long Vosk hypothesis into short, stable
 * subtitle phrases. A Vosk endpoint is not necessarily a sentence boundary:
 * continuous talking otherwise updates ONE row indefinitely.
 *
 * This class is Android-free and is exercised by JVM unit tests.
 */
public final class SubtitleLivePhraseSegmenter {
    public static final class Phrase {
        public final long id;
        public final String source;
        public final boolean isFinal;
        Phrase(long id, String source, boolean isFinal) {
            this.id = id; this.source = source; this.isFinal = isFinal;
        }
    }

    // This does NOT pause audio capture/recognition. It only separates an
    // already recognizable phrase when a quiet interval is observed.
    public static final long QUIET_BOUNDARY_MS = 780L;
    public static final long MAX_ACTIVE_PHRASE_MS = 2500L;
    private final String language;
    private long nextId = 1L;
    private long activeId = 1L;
    private long activeStartedMs = 0L;
    private String consumed = "";       // already sealed prefix of this Vosk result
    private String currentSource = "";  // current subtitle row's source words
    private String lastFullStable = "";
    private boolean hadPreview = false;

    public SubtitleLivePhraseSegmenter(String language) {
        this.language = "ja".equals(language) ? "ja" : "en";
    }

    private static String norm(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    private String remaining(String full) {
        if (consumed.isEmpty()) return full;
        if (full.startsWith(consumed)) return full.substring(consumed.length()).trim();
        // Vosk routinely revises the beginning of a partial hypothesis.
        // For a previously sealed prefix, never re-display the entire old
        // paragraph. Approximate the new tail using the previous length.
        if (full.length() <= consumed.length()) return "";
        // Look for the last complete English word of the old prefix.
        if ("en".equals(language)) {
            String[] tokens = consumed.split(" ");
            if (tokens.length >= 3) {
                String anchor = (tokens[tokens.length - 2] + " " +
                        tokens[tokens.length - 1]).toLowerCase(Locale.ROOT);
                String candidate = full.toLowerCase(Locale.ROOT);
                int at = candidate.indexOf(anchor);
                if (at >= 0) return full.substring(at + anchor.length()).trim();
            }
        }
        // No safe anchor: avoid generating stale/repeated captions.
        return "";
    }

    public List<Phrase> partial(String hypothesis, long nowMs) {
        List<Phrase> out = new ArrayList<>();
        String full = norm(hypothesis);
        if (full.isEmpty()) return out;
        lastFullStable = full;
        String tail = remaining(full);
        if (tail.isEmpty()) return out;
        if (activeStartedMs == 0L) activeStartedMs = nowMs;
        // A long uninterrupted Vosk segment must not be stuck on the bottom
        // row for ten seconds. Split only at a COMPLETED word boundary (EN)
        // or a conservative Japanese clause/character boundary.
        if (nowMs - activeStartedMs >= MAX_ACTIVE_PHRASE_MS) {
            int cut = splitPoint(tail);
            if (cut > 0 && cut < tail.length()) {
                String sealed = norm(tail.substring(0, cut));
                if (!sealed.isEmpty() && !sealed.equals(currentSource))
                    out.add(new Phrase(activeId, sealed, false));
                consumed = full.substring(0, full.indexOf(tail) + cut).trim();
                activeId = ++nextId;
                activeStartedMs = nowMs;
                hadPreview = false;
                currentSource = "";
                tail = norm(tail.substring(cut));
            }
        }
        if (!tail.isEmpty() && SubtitleSpeechEmissionPolicy.meaningfulChange(
                currentSource, tail, language)) {
            currentSource = tail;
            hadPreview = true;
            out.add(new Phrase(activeId, tail, false));
        }
        return out;
    }

    public List<Phrase> silence(long nowMs) {
        List<Phrase> out = new ArrayList<>();
        if (!hadPreview || currentSource.isEmpty()) return out;
        // Do not wait for Vosk internal endpoint if a phrase has gone quiet:
        // same ID gets a final correction, then the next words go to a new row.
        out.add(new Phrase(activeId, currentSource, true));
        consumed = lastFullStable;
        activeId = ++nextId;
        activeStartedMs = nowMs;
        currentSource = "";
        hadPreview = false;
        return out;
    }

    public List<Phrase> finish(String finalText, long nowMs) {
        List<Phrase> out = new ArrayList<>();
        String full = norm(finalText);
        String tail = remaining(full);
        if (!tail.isEmpty()) {
            // A true Vosk result revises the current row rather than
            // inventing a new one. The consumed prefix stays sealed.
            out.add(new Phrase(activeId, tail, true));
        } else if (!consumed.isEmpty() && !currentSource.isEmpty()) {
            out.add(new Phrase(activeId, currentSource, true));
        }
        consumed = "";
        currentSource = "";
        lastFullStable = "";
        hadPreview = false;
        activeStartedMs = 0L;
        activeId = ++nextId;
        return out;
    }

    public boolean hasPreview() { return hadPreview; }

    private int splitPoint(String tail) {
        if ("ja".equals(language)) {
            if (tail.length() < 20) return -1;
            int aim = Math.min(tail.length() - 4, 14);
            for (int i = aim; i >= Math.max(5, aim - 8); i--) {
                char c = tail.charAt(i);
                if ("。！？、， ".indexOf(c) >= 0) return i + 1;
            }
            return aim;
        }
        String[] words = tail.split(" ");
        if (words.length < 11) return -1;
        // 7–8 completed words is a readable fast preview; at least three
        // remain for the next row. No incomplete English word is exposed.
        int boundary = 0;
        for (int i = 0; i < 8; i++) {
            boundary = tail.indexOf(' ', boundary);
            if (boundary < 0) return -1;
            boundary++;
        }
        return boundary;
    }
}
