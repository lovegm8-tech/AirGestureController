package tw.airgesture.transferbeta.subtitle;

/**
 * Select only a likely subtitle region. All bounds are relative to the current
 * MediaProjection dimensions; rotating the device must not keep portrait crops.
 * The values are intentionally user-adjustable because apps place subtitles
 * differently and OCR cannot infer a video player's content rect reliably.
 */
public final class SubtitleRegionPolicy {
    public static final int AUTO = 0;
    public static final int NORMAL_PORTRAIT = 1;
    public static final int SHORT_VIDEO = 2;
    public static final int BOTTOM = 3;
    public static final int FULL_SCREEN = 4;
    public static final int CUSTOM = 5;
    private SubtitleRegionPolicy() { }

    /**
     * @return pixel bounds [top inclusive, bottom exclusive] for the screenshot.
     */
    public static int[] verticalBounds(int width, int height, int mode,
                                       int customTopPercent, int customBottomPercent) {
        if (height <= 0) return new int[] { 0, 1 };
        boolean landscape = width > height;
        int top;
        int bottom;
        switch (mode) {
            case NORMAL_PORTRAIT:
                top = 9; bottom = landscape ? 59 : 53; break;
            case SHORT_VIDEO:
                top = landscape ? 48 : 32;
                bottom = landscape ? 98 : 91; break;
            case BOTTOM:
                top = 50; bottom = 98; break;
            case FULL_SCREEN:
                top = 0; bottom = 100; break;
            case CUSTOM:
                top = Math.max(0, Math.min(customTopPercent, 85));
                bottom = Math.max(top + 12, Math.min(customBottomPercent, 100));
                break;
            default:
                // Portrait normal videos have captions inside the top player.
                // Landscape full-screen videos usually caption near the bottom.
                top = landscape ? 54 : 9;
                bottom = landscape ? 98 : 51;
                break;
        }
        int y0 = Math.max(0, Math.min(height - 1, Math.round(height * top / 100f)));
        int y1 = Math.max(y0 + 1, Math.min(height, Math.round(height * bottom / 100f)));
        return new int[] { y0, y1 };
    }

    /** Reject most UI counters, URLs and pre-translated Chinese-only text. */
    public static boolean isPossibleSubtitle(String text, String language) {
        if (text == null) return false;
        String t = text.trim();
        if (t.length() < 3 || t.length() > 260) return false;
        int scriptHits = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if ("ja".equals(language)) {
                if ((c >= '\u3040' && c <= '\u30ff')
                        || (c >= '\u4e00' && c <= '\u9fff')) scriptHits++;
            } else if ("ko".equals(language)) {
                if ((c >= '\uac00' && c <= '\ud7af')) scriptHits++;
            } else if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                scriptHits++;
            }
        }
        if ("en".equals(language)) {
            // Prevent Chinese-heavy recommendation titles, page controls or our
            // translated overlay from being mistaken for English dialogue merely
            // because they contain two English words such as "Lofi Chill".
            int chinese = 0;
            for (int i = 0; i < t.length(); i++) {
                char c = t.charAt(i);
                if (c >= 0x4e00 && c <= 0x9fff) chinese++;
            }
            if (scriptHits < 2) return false;
            return chinese == 0 || scriptHits >= chinese * 1.7f;
        }
        return scriptHits >= 1;
    }
}
