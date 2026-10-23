package tw.airgesture.transferbeta.subtitle;

/** Shared placement math for draggable overlay ball and its context menu. */
public final class SubtitleBubblePosition {
    private SubtitleBubblePosition() {}

    /** A window coordinate clamped into the visible screen. */
    public static int clamp(int wanted, int maximum) {
        if (maximum < 0) return 0;
        return Math.max(0, Math.min(wanted, maximum));
    }
}
