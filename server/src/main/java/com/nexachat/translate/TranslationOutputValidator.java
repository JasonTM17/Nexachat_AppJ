package com.nexachat.translate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Validates the provider's translate response before it can be cached or
 * returned. Rejects blank or whitespace-only output, unparseable or fenced
 * JSON, delimiter/system-prompt echoes, implausible length ratios and
 * script-mismatched output — the injection-hardening counterpart to
 * {@link TranslationPromptBuilder}.
 */
@Component
public final class TranslationOutputValidator {

    /** Absolute ceiling for a returned translation, independent of input size. */
    private static final int MAX_OUTPUT_CHARS = 8_000;
    /** Inputs shorter than this skip the script check (too little signal). */
    private static final int SCRIPT_CHECK_MIN_INPUT_LETTERS = 10;
    private static final Pattern LETTERS = Pattern.compile("\\p{L}");
    private static final Pattern ISO_639_1 = Pattern.compile("[a-z]{2,3}");

    private final ObjectMapper objectMapper;

    public TranslationOutputValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * True when {@code value} is null, empty, or nothing but Unicode
     * whitespace — including the non-breaking and exotic space characters
     * {@link String#isBlank()} misses. A completion or translation text that
     * passes no letter at all carries no result: {@link LlmTranslationProvider}
     * counts it as a breaker failure, and this validator rejects it so it is
     * never returned or cached.
     */
    static boolean whitespaceOnly(String value) {
        if (value == null || value.isEmpty()) {
            return true;
        }
        return value.codePoints().allMatch(cp -> Character.isWhitespace(cp) || Character.isSpaceChar(cp));
    }

    /**
     * Parses and validates the provider completion.
     *
     * @return the validated output, or {@code null} when it fails any check —
     *         the caller treats that as a provider failure and may retry on
     *         the fallback tier.
     */
    public TranslationOutput validate(String completion, TranslationPrompt prompt,
                                      String sourceText, TranslationLanguage target) {
        if (completion == null || prompt == null || sourceText == null || target == null) {
            return null;
        }
        if (whitespaceOnly(completion)) {
            return null;
        }
        String trimmed = completion.trim();
        if (trimmed.startsWith("```")) {
            return null;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(trimmed);
        } catch (Exception exception) {
            return null;
        }
        if (!root.isObject()) {
            return null;
        }
        JsonNode textNode = root.get("text");
        JsonNode sourceNode = root.get("source_lang");
        if (textNode == null || !textNode.isTextual()) {
            return null;
        }
        String text = textNode.asText();
        if (whitespaceOnly(text)) {
            return null;
        }
        int outputChars = text.codePointCount(0, text.length());
        int inputChars = sourceText.codePointCount(0, sourceText.length());
        if (outputChars > MAX_OUTPUT_CHARS
                || outputChars > Math.max(inputChars * 4, inputChars + 400)) {
            return null;
        }
        // Injection check: the model must not replay the delimiters, the raw
        // payload, or any recognisable part of the system prompt.
        if (text.contains(TranslationPromptBuilder.UNTRUSTED_PREFIX)
                || text.contains(TranslationPromptBuilder.UNTRUSTED_SUFFIX)
                || text.contains(TranslationPromptBuilder.SYSTEM_INTRO)
                || text.contains(prompt.untrustedJson())) {
            return null;
        }
        String sourceLang = normalizeSourceLang(sourceNode);
        if (sourceLang == null) {
            return null;
        }
        // Script check on distinctive-script targets; skipped when the model
        // detected the source IS the target (unchanged echo is legal there)
        // and on tiny inputs where one emoji/name can be the whole output.
        boolean sameLanguage = target.code().equals(sourceLang);
        if (!sameLanguage && !text.equals(sourceText)
                && countLetters(sourceText) >= SCRIPT_CHECK_MIN_INPUT_LETTERS
                && !target.matches(text)) {
            return null;
        }
        return new TranslationOutput(text, sourceLang);
    }

    /** Lowercases the provider's language hint; accepts ISO-639-1/2 codes. */
    private static String normalizeSourceLang(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        String value = node.asText().trim().toLowerCase(Locale.ROOT).replace('_', '-');
        String primary = value.split("-", 2)[0];
        return ISO_639_1.matcher(primary).matches() ? primary : null;
    }

    private static int countLetters(String text) {
        int count = 0;
        java.util.regex.Matcher matcher = LETTERS.matcher(text);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    /** Convenience for callers holding a raw target code string. */
    public TranslationOutput validate(String completion, TranslationPrompt prompt,
                                      String sourceText, String targetCode) {
        Optional<TranslationLanguage> language = TranslationLanguage.of(targetCode);
        return language.map(value -> validate(completion, prompt, sourceText, value)).orElse(null);
    }
}
