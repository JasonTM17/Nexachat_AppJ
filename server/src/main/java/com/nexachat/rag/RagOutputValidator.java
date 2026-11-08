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
        if (rawOutput == null || rawOutput.isBlank() || rawOutput.length() > MAX_OUTPUT_CHARS) {
            return null;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(rawOutput);
        } catch (JsonProcessingException | RuntimeException exception) {
            return null;
        }
        if (root == null || !root.isObject()) {
            return null;
        }
        JsonNode answerNode = root.get("answer");
        if (answerNode == null || !(answerNode.isNull() || answerNode.isTextual())) {
            return null;
        }
        String answer = answerNode.isNull() ? null : cleanText(answerNode.textValue());
        if (answer == null || answer.isBlank()) {
            return null;
        }
        JsonNode citationsNode = root.get("citations");
        if (citationsNode == null || !citationsNode.isArray() || citationsNode.size() > MAX_CITATIONS) {
            return null;
        }
        Set<UUID> citationIds = new LinkedHashSet<>();
        for (JsonNode citation : citationsNode) {
            if (!citation.isIntegralNumber() || !citation.canConvertToInt()) {
                return null;
            }
            List<UUID> mapped = prompt.messageIdsByIndex().get(citation.intValue());
            if (mapped != null) {
                citationIds.addAll(mapped);
            }
        }
        if (citationIds.isEmpty()) {
            return null;
        }
        if (!LanguageCheck.matches(answer, prompt.language())) {
            return null;
        }
        return new RagLlmAnswer(answer, List.copyOf(citationIds));
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
