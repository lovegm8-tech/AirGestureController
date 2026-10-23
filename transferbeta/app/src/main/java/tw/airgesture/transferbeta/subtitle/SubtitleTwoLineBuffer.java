package tw.airgesture.transferbeta.subtitle;

/**
 * Two independent caption rows, oldest on top. A stable utterance identity
 * updates its existing row (ASR partial -> final) rather than creating rows.
 * On the third new sentence, evict the first and shift the second upwards.
 *
 * Plain Java so behavior is covered by unit tests without an Android device.
 */
public final class SubtitleTwoLineBuffer {
    private String firstKey = "";
    private String firstText = "";
    private String secondKey = "";
    private String secondText = "";
    private long newestVoiceId = 0L;

    public boolean putVoice(long utteranceId, String translation) {
        if (utteranceId <= 0 || utteranceId < newestVoiceId - 1) return false;
        if (utteranceId > newestVoiceId) newestVoiceId = utteranceId;
        return put("voice:" + utteranceId, translation);
    }

    public boolean putOcr(String original, String translation) {
        if (original == null || original.trim().isEmpty()) return false;
        return put("ocr:" + original.trim(), translation);
    }

    private boolean put(String key, String translation) {
        if (translation == null) return false;
        // Some ML Kit outputs include literal newline. One sentence is still
        // precisely one row; do not allow a sentence to consume both rows.
        String text = translation.replaceAll("\\s+", " ").trim();
        if (text.isEmpty()) return false;
        if (key.equals(secondKey)) {
            if (secondText.equals(text)) return false;
            secondText = text;
            return true;
        }
        if (key.equals(firstKey)) {
            if (firstText.equals(text)) return false;
            firstText = text;
            return true;
        }
        // Discard an older delayed voice result if the newest two sentences
        // are already visible; a late callback must never rotate them back.
        if (key.startsWith("voice:")) {
            long id = Long.parseLong(key.substring(6));
            if (id < newestVoiceId - 1) return false;
        }
        if (firstKey.isEmpty()) {
            firstKey = key;
            firstText = text;
        } else if (secondKey.isEmpty()) {
            secondKey = key;
            secondText = text;
        } else {
            firstKey = secondKey;
            firstText = secondText;
            secondKey = key;
            secondText = text;
        }
        return true;
    }

    public boolean hasCaptions() { return !firstKey.isEmpty(); }
    public String first() { return firstText; }
    public String second() { return secondText; }
    public int count() { return secondKey.isEmpty() ? (firstKey.isEmpty() ? 0 : 1) : 2; }

    public void clear() {
        firstKey = "";
        firstText = "";
        secondKey = "";
        secondText = "";
        newestVoiceId = 0L;
    }
}
