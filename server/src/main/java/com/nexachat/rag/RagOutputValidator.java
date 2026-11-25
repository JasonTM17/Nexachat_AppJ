package com.nexachat.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.summary.llm.LanguageCheck;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Validates LLM ask output. Every citation must map to a message in the
 * retrieved context; unknown indexes are dropped and an answer left without a
 * valid citation is rejected.
 */
@Component
public final class RagOutputValidator {

    private static final int MAX_OUTPUT_CHARS = 16_000;
    private static final int MAX_ANSWER_CHARS = 2_000;
    private static final int MAX_CITATIONS = 20;
    private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[([^\\]]+)]\\([^)]*\\)");
    private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?://|www\\.)[^\\s<>]+|\\b[a-z0-9.-]+\\.[a-z]{2,}(?:/[^\\s<>]*)?");
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]*>");
    private static final Pattern MARKUP = Pattern.compile("[`*_~>#]+");

    private final ObjectMapper objectMapper;

    public RagOutputValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public RagLlmAnswer validate(String rawOutput, RagPrompt prompt) {
        Validation validation = validateDetailed(rawOutput, prompt);
        return validation.status() == Status.ANSWER ? validation.answer() : null;
    }

    /**
     * Classifies provider output. A protocol-valid null answer is a successful
     * abstention, not malformed output, so callers must not count it as provider
     * or circuit-breaker failure evidence.
     */
    public Validation validateDetailed(String rawOutput, RagPrompt prompt) {
        if (rawOutput == null || rawOutput.isBlank() || rawOutput.length() > MAX_OUTPUT_CHARS) {
            return Validation.invalid();
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(rawOutput);
        } catch (JsonProcessingException | RuntimeException exception) {
            return Validation.invalid();
        }
        if (root == null || !root.isObject()) {
            return Validation.invalid();
        }
        JsonNode answerNode = root.get("answer");
        if (answerNode == null || !(answerNode.isNull() || answerNode.isTextual())) {
            return Validation.invalid();
        }
        JsonNode citationsNode = root.get("citations");
        if (citationsNode == null || !citationsNode.isArray() || citationsNode.size() > MAX_CITATIONS) {
            return Validation.invalid();
        }
        if (answerNode.isNull()) {
            return citationsNode.isEmpty() ? Validation.abstain() : Validation.invalid();
        }
        String answer = cleanText(answerNode.textValue());
        if (answer == null || answer.isBlank()) {
            return Validation.invalid();
        }
        Set<UUID> citationIds = new LinkedHashSet<>();
        for (JsonNode citation : citationsNode) {
            if (!citation.isIntegralNumber() || !citation.canConvertToInt()) {
                return Validation.invalid();
            }
            List<UUID> mapped = prompt.messageIdsByIndex().get(citation.intValue());
            if (mapped != null) {
                citationIds.addAll(mapped);
            }
        }
        if (citationIds.isEmpty()) {
            return Validation.invalid();
        }
        if (!LanguageCheck.matches(answer, prompt.language())) {
            return Validation.invalid();
        }
        return Validation.answer(new RagLlmAnswer(answer, List.copyOf(citationIds)));
    }

    public enum Status {
        ANSWER,
        ABSTAIN,
        INVALID
    }

    public record Validation(Status status, RagLlmAnswer answer) {
        private static Validation answer(RagLlmAnswer answer) {
            return new Validation(Status.ANSWER, answer);
        }

        private static Validation abstain() {
            return new Validation(Status.ABSTAIN, null);
        }

        private static Validation invalid() {
            return new Validation(Status.INVALID, null);
        }
    }

    private static String cleanText(String input) {
        String cleaned = MARKDOWN_LINK.matcher(input).replaceAll("$1");
        cleaned = HTML_TAG.matcher(cleaned).replaceAll(" ");
        cleaned = URL.matcher(cleaned).replaceAll(" ");
        cleaned = MARKUP.matcher(cleaned).replaceAll("");
        cleaned = cleaned.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", " ")
                .replaceAll("\\s+", " ").trim();
        int codePoints = cleaned.codePointCount(0, cleaned.length());
        if (codePoints > MAX_ANSWER_CHARS) {
            cleaned = cleaned.substring(0, cleaned.offsetByCodePoints(0, MAX_ANSWER_CHARS)).trim();
        }
        return cleaned;
    }
}
