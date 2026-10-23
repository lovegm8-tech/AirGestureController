package tw.airgesture.transferbeta.subtitle;

/** Pure configuration: each on-device speech source has its own licensed Vosk model. */
public final class SubtitleSpeechModels {
    public static final String ENGLISH = "vosk-model-small-en-us-0.15";
    public static final String JAPANESE = "vosk-model-small-ja-0.22";
    public static final String MODEL_BASE = "https://alphacephei.com/vosk/models/";

    private SubtitleSpeechModels() { }

    public static boolean supports(String sourceLanguage) {
        return "en".equals(sourceLanguage) || "ja".equals(sourceLanguage);
    }

    public static String folder(String sourceLanguage) {
        switch (sourceLanguage) {
            case "en": return ENGLISH;
            case "ja": return JAPANESE;
            default: throw new IllegalArgumentException("unsupported speech language");
        }
    }

    public static String downloadUrl(String language) {
        return MODEL_BASE + folder(language) + ".zip";
    }

    public static int minFinalLength(String language) {
        return "ja".equals(language) ? 3 : 7;
    }

    public static int minPartialLength(String language) {
        return "ja".equals(language) ? 7 : 19;
    }
}
