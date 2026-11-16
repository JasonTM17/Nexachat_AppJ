package com.nexachat.translate;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Target languages offered by the translate API. Codes are stored and
 * returned as BCP-47 primary subtags (lowercase); aliases such as
 * {@code zh-CN}, {@code iw} or {@code in} normalize onto this set.
 *
 * <p>{@link #SUPPORTED_PROMPT_NAMES} is the human-readable list embedded in
 * the provider prompt so the model echoes a code we can trust.
 */
public enum TranslationLanguage {
    VI("vi", "Vietnamese", null),
    EN("en", "English", null),
    ZH("zh", "Chinese", ScriptCheck.CJK),
    JA("ja", "Japanese", ScriptCheck.JAPANESE),
    KO("ko", "Korean", ScriptCheck.HANGUL),
    FR("fr", "French", null),
    DE("de", "German", null),
    ES("es", "Spanish", null),
    TH("th", "Thai", ScriptCheck.THAI),
    ID("id", "Indonesian", null),
    RU("ru", "Russian", ScriptCheck.CYRILLIC),
    AR("ar", "Arabic", ScriptCheck.ARABIC),
    PT("pt", "Portuguese", null),
    HI("hi", "Hindi", ScriptCheck.DEVANAGARI);

    private final String code;
    private final String englishName;
    private final ScriptCheck scriptCheck;

    TranslationLanguage(String code, String englishName, ScriptCheck scriptCheck) {
        this.code = code;
        this.englishName = englishName;
        this.scriptCheck = scriptCheck;
    }

    public String code() {
        return code;
    }

    public String englishName() {
        return englishName;
    }

    /** Normalizes an API/LLM language code onto a supported language. */
    public static Optional<TranslationLanguage> of(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        String primary = normalized.split("-", 2)[0];
        // Historical aliases still emitted by some providers and older clients.
        primary = switch (primary) {
            case "in" -> "id";
            case "iw", "he" -> "he";
            default -> primary;
        };
        for (TranslationLanguage language : values()) {
            if (language.code.equals(primary)) {
                return Optional.of(language);
            }
        }
        return Optional.empty();
    }

    /**
     * Whether a language name/code string is accepted as a translation target.
     * Null/blank is never valid; the check is {@link #of(String)} plus the
     * stored-column length limit so callers can use it for validation errors.
     */
    public static boolean isSupported(String value) {
        return value != null && value.length() <= 16 && of(value).isPresent();
    }

    /**
     * Whether {@code text} plausibly reads as this language. Used by the
     * output validator on script-distinctive targets only; Latin-script
     * targets always pass (the provider's detection is authoritative there).
     */
    public boolean matches(String text) {
        if (scriptCheck == null) {
            return true;
        }
        return scriptCheck.matches(text);
    }

    /**
     * Script check for non-Latin targets. A translation of a letter-bearing
     * message into e.g. Russian must contain Cyrillic; an all-emoji or all
     * digits message is exempt (it stays unchanged in every language).
     */
    enum ScriptCheck {
        CJK("\\p{IsHan}"),
        JAPANESE("\\p{IsHiragana}\\p{IsKatakana}\\p{IsHan}"),
        HANGUL("\\p{IsHangul}"),
        THAI("\\p{IsThai}"),
        CYRILLIC("\\p{IsCyrillic}"),
        ARABIC("\\p{IsArabic}"),
        DEVANAGARI("\\p{IsDevanagari}");

        private final Pattern script;
        private static final Pattern LETTERS = Pattern.compile("\\p{L}");

        ScriptCheck(String scriptPattern) {
            this.script = Pattern.compile("[" + scriptPattern + "]");
        }

        boolean matches(String text) {
            if (text == null) {
                return false;
            }
            if (!LETTERS.matcher(text).find()) {
                return true;
            }
            return script.matcher(text).find();
        }
    }

    /** Prompt-facing {@code code=name} list, used verbatim inside the system prompt. */
    public static final String SUPPORTED_PROMPT_NAMES;

    static {
        StringBuilder names = new StringBuilder();
        for (TranslationLanguage language : values()) {
            if (names.length() > 0) {
                names.append(", ");
            }
            names.append(language.code).append('=').append(language.englishName);
        }
        SUPPORTED_PROMPT_NAMES = names.toString();
    }

    /** Fast member check used by request validation. */
    public static final Map<String, TranslationLanguage> BY_CODE;

    static {
        java.util.LinkedHashMap<String, TranslationLanguage> map = new java.util.LinkedHashMap<>();
        for (TranslationLanguage language : values()) {
            map.put(language.code, language);
        }
        BY_CODE = java.util.Collections.unmodifiableMap(map);
    }
}
