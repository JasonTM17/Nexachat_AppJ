package com.nexachat.summary.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.nexachat.summary.ProviderResult;
import com.nexachat.summary.SummaryActionItem;
import com.nexachat.summary.SummaryContent;
import com.nexachat.summary.SummaryFallbackReason;
import com.nexachat.summary.SummaryItem;
import com.nexachat.summary.SummaryLanguage;
import com.nexachat.summary.SummaryProviderException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public final class LlmOutputValidator {

    private static final int MAX_OUTPUT_CHARS = 64_000;
    private static final int MAX_TEXT_CHARS = 1_000;
    private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[([^\\]]+)]\\([^)]*\\)");
    private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?://|www\\.)[^\\s<>]+|\\b[a-z0-9.-]+\\.[a-z]{2,}(?:/[^\\s<>]*)?");
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]*>");
    private static final Pattern MARKUP = Pattern.compile("[`*_~>#]+");

    private final ObjectMapper objectMapper = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build());

    ProviderResult validate(String rawOutput, Prompt prompt, String model) {
        if (rawOutput == null || rawOutput.isBlank() || rawOutput.length() > MAX_OUTPUT_CHARS) {
            throw badOutput();
        }
        JsonNode root;
        try {
            root = objectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(rawOutput);
        } catch (JsonProcessingException | RuntimeException exception) {
            throw badOutput();
        }
        requireObjectFields(root, Set.of("overview", "keyPoints", "decisions", "actionItems", "questions", "topics"));

        String overview = cleanText(requiredText(root, "overview"), 400);
        if (overview.isBlank()) {
            throw badOutput();
        }
        List<SummaryItem> keyPoints = readItems(root.get("keyPoints"), 3, prompt);
        List<SummaryItem> decisions = readItems(root.get("decisions"), 5, prompt);
        List<SummaryActionItem> actionItems = readActionItems(root.get("actionItems"), 5, prompt);
        List<SummaryItem> questions = readItems(root.get("questions"), 5, prompt);
        List<String> topics = readTopics(root.get("topics"), 5);

        String languageProbe = languageProbe(overview, keyPoints, decisions, actionItems, questions, topics);
        if (!LanguageCheck.matches(languageProbe, prompt.language())) {
            throw badOutput();
        }
        SummaryContent content = new SummaryContent(
                overview,
                keyPoints,
                decisions,
                actionItems,
                questions,
                topics,
                prompt.participants());
        return new ProviderResult(content, prompt.language(), model);
    }

    private static List<SummaryItem> readItems(JsonNode node, int maxItems, Prompt prompt) {
        requireArray(node, maxItems);
        List<SummaryItem> items = new ArrayList<>();
        for (JsonNode item : node) {
            requireObjectFields(item, Set.of("text", "citations"));
            String text = cleanText(requiredText(item, "text"), 280);
            List<UUID> citations = citations(item.get("citations"), prompt);
            if (!text.isBlank() && !citations.isEmpty()) {
                items.add(new SummaryItem(text, citations));
            }
        }
        return List.copyOf(items);
    }

    private static List<SummaryActionItem> readActionItems(JsonNode node, int maxItems, Prompt prompt) {
        requireArray(node, maxItems);
        List<SummaryActionItem> items = new ArrayList<>();
        for (JsonNode item : node) {
            requireObjectFields(item, Set.of("text", "owner", "dueHint", "citations"));
            String text = cleanText(requiredText(item, "text"), 280);
            List<UUID> citations = citations(item.get("citations"), prompt);
            UUID owner = null;
            JsonNode ownerNode = item.get("owner");
            if (ownerNode == null || !(ownerNode.isNull() || ownerNode.isTextual())) {
                throw badOutput();
            }
            if (ownerNode.isTextual()) {
                owner = prompt.senderIdsByLabel().get(ownerNode.textValue());
            }
            String dueHint = nullableText(item.get("dueHint"), 80);
            if (!text.isBlank() && !citations.isEmpty()) {
                items.add(new SummaryActionItem(text, owner, dueHint, citations));
            }
        }
        return List.copyOf(items);
    }

    private static List<String> readTopics(JsonNode node, int maxItems) {
        requireArray(node, maxItems);
        List<String> topics = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                throw badOutput();
            }
            String topic = cleanText(item.textValue(), 80);
            if (!topic.isBlank()) {
                topics.add(topic);
            }
        }
        return List.copyOf(topics);
    }

    private static List<UUID> citations(JsonNode node, Prompt prompt) {
        if (node == null || !node.isArray() || node.size() > 20) {
            throw badOutput();
        }
        LinkedHashSet<UUID> ids = new LinkedHashSet<>();
        for (JsonNode indexNode : node) {
            if (!indexNode.isIntegralNumber() || !indexNode.canConvertToInt()) {
                throw badOutput();
            }
            UUID id = prompt.messageIdsByIndex().get(indexNode.intValue());
            if (id != null) {
                ids.add(id);
            }
        }
        return List.copyOf(ids);
    }

    private static String requiredText(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual() || value.textValue().length() > MAX_TEXT_CHARS) {
            throw badOutput();
        }
        return value.textValue();
    }

    private static String nullableText(JsonNode value, int maxChars) {
        if (value == null || !(value.isNull() || value.isTextual())) {
            throw badOutput();
        }
        if (value.isNull()) {
            return null;
        }
        if (value.textValue().length() > MAX_TEXT_CHARS) {
            throw badOutput();
        }
        return cleanText(value.textValue(), maxChars);
    }

    private static void requireArray(JsonNode node, int maxItems) {
        if (node == null || !node.isArray() || node.size() > maxItems) {
            throw badOutput();
        }
    }

    private static void requireObjectFields(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject() || node.size() != expected.size()) {
            throw badOutput();
        }
        Set<String> actual = new HashSet<>();
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            actual.add(names.next());
        }
        if (!actual.equals(expected)) {
            throw badOutput();
        }
    }

    private static String cleanText(String input, int maxChars) {
        String cleaned = MARKDOWN_LINK.matcher(input).replaceAll("$1");
        cleaned = HTML_TAG.matcher(cleaned).replaceAll(" ");
        cleaned = URL.matcher(cleaned).replaceAll(" ");
        cleaned = MARKUP.matcher(cleaned).replaceAll("");
        cleaned = cleaned.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", " ")
                .replaceAll("\\s+", " ").trim();
        int codePoints = cleaned.codePointCount(0, cleaned.length());
        if (codePoints > maxChars) {
            cleaned = cleaned.substring(0, cleaned.offsetByCodePoints(0, maxChars)).trim();
        }
        return cleaned;
    }

    private static String languageProbe(
            String overview,
            List<SummaryItem> keyPoints,
            List<SummaryItem> decisions,
            List<SummaryActionItem> actionItems,
            List<SummaryItem> questions,
            List<String> topics) {
        StringBuilder probe = new StringBuilder(overview);
        keyPoints.forEach(item -> probe.append(' ').append(item.text()));
        decisions.forEach(item -> probe.append(' ').append(item.text()));
        actionItems.forEach(item -> probe.append(' ').append(item.text()));
        questions.forEach(item -> probe.append(' ').append(item.text()));
        topics.forEach(topic -> probe.append(' ').append(topic));
        return probe.toString();
    }

    private static SummaryProviderException badOutput() {
        return new SummaryProviderException(SummaryFallbackReason.LLM_BAD_OUTPUT);
    }
}
