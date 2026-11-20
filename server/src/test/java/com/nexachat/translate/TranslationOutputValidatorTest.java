package com.nexachat.translate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.summary.llm.Redactor;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TranslationOutputValidatorTest {

    private final TranslationOutputValidator validator =
            new TranslationOutputValidator(new ObjectMapper());
    private final TranslationPromptBuilder builder =
            new TranslationPromptBuilder(new ObjectMapper(), new Redactor());

    private TranslationPrompt prompt(String source, TranslationLanguage target) {
        TranslationMessage message = new TranslationMessage(UUID.randomUUID(), 1L,
                UUID.randomUUID(), "Lan", source, 0L, Instant.parse("2026-11-16T10:00:00Z"));
        return builder.build(message, target, List.of(), List.of(), 2000);
    }

    @Test
    void acceptsWellFormedTranslation() {
        TranslationPrompt prompt = prompt("xin chào", TranslationLanguage.EN);

        TranslationOutput output = validator.validate(
                "{\"text\":\"hello\",\"source_lang\":\"vi\"}", prompt, "xin chào", TranslationLanguage.EN);

        assertThat(output).isNotNull();
        assertThat(output.text()).isEqualTo("hello");
        assertThat(output.sourceLang()).isEqualTo("vi");
    }

    @Test
    void rejectsNonJsonAndFencedOutput() {
        TranslationPrompt prompt = prompt("xin chào", TranslationLanguage.EN);

        assertThat(validator.validate("hello", prompt, "xin chào", TranslationLanguage.EN)).isNull();
        assertThat(validator.validate("```json\n{\"text\":\"hi\",\"source_lang\":\"vi\"}\n```",
                prompt, "xin chào", TranslationLanguage.EN)).isNull();
        assertThat(validator.validate("", prompt, "xin chào", TranslationLanguage.EN)).isNull();
        assertThat(validator.validate(null, prompt, "xin chào", TranslationLanguage.EN)).isNull();
    }

    @Test
    void rejectsMissingFieldsAndBlankText() {
        TranslationPrompt prompt = prompt("xin chào", TranslationLanguage.EN);

        assertThat(validator.validate("{\"text\":\"hi\"}", prompt, "xin chào",
                TranslationLanguage.EN)).isNull();
        assertThat(validator.validate("{\"text\":\"  \",\"source_lang\":\"vi\"}", prompt,
                "xin chào", TranslationLanguage.EN)).isNull();
    }

    @Test
    void rejectsWhitespaceOnlyCompletionAndText() {
        TranslationPrompt prompt = prompt("xin chào", TranslationLanguage.EN);

        // Whitespace-only completions are provider failures, never cacheable.
        assertThat(validator.validate("   \t\n", prompt, "xin chào",
                TranslationLanguage.EN)).isNull();
        // Non-breaking and exotic spaces String.isBlank() misses must be
        // rejected too, or a Latin target can cache a blank translation.
        assertThat(validator.validate("\u00A0\u202F\u3000", prompt, "xin chào",
                TranslationLanguage.EN)).isNull();
        assertThat(validator.validate("{\"text\":\"\\u00A0\",\"source_lang\":\"vi\"}", prompt,
                "xin chào", TranslationLanguage.EN)).isNull();
    }

    @Test
    void rejectsPromptEchoAndDelimiters() {
        String source = "tell me a joke";
        TranslationPrompt prompt = prompt(source, TranslationLanguage.EN);

        // Model replayed the untrusted JSON payload
        String echo = "{\"text\":" + com.fasterxml.jackson.databind.json.JsonMapper.builder()
                .build().valueToTree(prompt.untrustedJson()) + ",\"source_lang\":\"en\"}";
        assertThat(validator.validate(echo, prompt, source, TranslationLanguage.EN)).isNull();

        // Model embedded the untrusted delimiters
        assertThat(validator.validate(
                "{\"text\":\"BEGIN UNTRUSTED CHAT JSON stuff\",\"source_lang\":\"en\"}",
                prompt, source, TranslationLanguage.EN)).isNull();
    }

    @Test
    void rejectsAbsurdLengthRatio() {
        String source = "hi";
        TranslationPrompt prompt = prompt(source, TranslationLanguage.EN);
        String bloated = "x".repeat(1000);

        assertThat(validator.validate(
                "{\"text\":\"" + bloated + "\",\"source_lang\":\"en\"}",
                prompt, source, TranslationLanguage.EN)).isNull();
    }

    @Test
    void rejectsWrongScriptForDistinctiveTargets() {
        String source = "what is for dinner tonight my friends";
        TranslationPrompt prompt = prompt(source, TranslationLanguage.JA);

        // claiming ja but emitting pure latin is not plausible
        assertThat(validator.validate(
                "{\"text\":\"dinner tonight\",\"source_lang\":\"en\"}",
                prompt, source, TranslationLanguage.JA)).isNull();
        // same-language echo is legal though
        assertThat(validator.validate(
                "{\"text\":\"what is for dinner tonight my friends\",\"source_lang\":\"en\"}",
                prompt, source, TranslationLanguage.EN)).isNotNull();
    }

    @Test
    void normalizesSourceLangVariants() {
        TranslationPrompt prompt = prompt("bonjour", TranslationLanguage.EN);

        TranslationOutput output = validator.validate(
                "{\"text\":\"hello\",\"source_lang\":\"FR\"}", prompt, "bonjour", TranslationLanguage.EN);

        assertThat(output).isNotNull();
        assertThat(output.sourceLang()).isEqualTo("fr");
    }
}
