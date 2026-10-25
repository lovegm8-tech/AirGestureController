package tw.airgesture.transferbeta.subtitle;

import java.util.ArrayDeque;

/** In-memory bounded diagnostics: no network, logcat, or persistent transcripts. */
public final class SubtitlePipelineTrace {
    private static final int MAX_EVENTS = 300;
    private static final int MAX_TEXT_CHARS = 160;
    private final ArrayDeque<String> recent = new ArrayDeque<>();

    public synchronized void record(long elapsedMs, String stage, String text) {
        String safe = text == null ? "" : text.replaceAll("[\\r\\n\\t]+", " ").trim();
        if (safe.length() > MAX_TEXT_CHARS) safe = safe.substring(0, MAX_TEXT_CHARS) + "…";
        String label = stage == null ? "" : stage.replaceAll("[\\r\\n]+", " ");
        if (recent.size() == MAX_EVENTS) recent.removeFirst();
        recent.addLast("t+" + elapsedMs + "ms [" + label + "] " + safe);
    }

    public synchronized String snapshot() {
        if (recent.isEmpty()) return "尚無診斷事件";
        StringBuilder out = new StringBuilder();
        for (String line : recent) out.append(line).append('\n');
        return out.toString().trim();
    }

    public synchronized int size() { return recent.size(); }
    public synchronized void clear() { recent.clear(); }
}
