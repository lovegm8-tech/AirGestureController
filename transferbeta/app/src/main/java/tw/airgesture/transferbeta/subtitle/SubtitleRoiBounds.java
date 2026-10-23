package tw.airgesture.transferbeta.subtitle;

/** Android-independent ROI math for portrait/landscape window size changes. */
public final class SubtitleRoiBounds {
    private SubtitleRoiBounds() {}

    /** Fractions [left, top, right, bottom] to strict pixel bounds. */
    public static int[] pixels(float left, float top, float right, float bottom,
                               int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("invalid screen");
        int l = Math.min(width - 1, Math.max(0, Math.round(left * width)));
        int t = Math.min(height - 1, Math.max(0, Math.round(top * height)));
        int r = Math.min(width, Math.max(l + 1, Math.round(right * width)));
        int b = Math.min(height, Math.max(t + 1, Math.round(bottom * height)));
        return new int[]{l, t, r, b};
    }

    /** Converts a coordinate inside scaled OCR bitmap back to full display. */
    public static int toScreen(int roiOffset, float recognizedCoordinate,
                               float ocrScale) {
        if (ocrScale <= 0) throw new IllegalArgumentException("scale");
        return roiOffset + Math.round(recognizedCoordinate / ocrScale);
    }
}
