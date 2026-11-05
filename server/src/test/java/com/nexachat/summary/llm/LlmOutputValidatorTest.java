package com.nexachat.summary.llm;

import com.nexachat.summary.ProviderResult;
import com.nexachat.summary.SummaryFallbackReason;
import com.nexachat.summary.SummaryLanguage;
import com.nexachat.summary.SummaryProviderException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmOutputValidatorTest {

    private final LlmOutputValidator validator = new LlmOutputValidator();

    @Test
    void validatesSchemaMapsIndexesAndSenderLabelsThenStripsMarkupAndLinks() {
        UUID firstId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        Prompt prompt = prompt(firstId, senderId, SummaryLanguage.EN);
        String output = """
                {"overview":"The group agreed on a plan.",
                 "keyPoints":[{"text":"[Read](https://private.example/path) ***The group agreed.*** <b>Next step</b>","citations":[1]}],
                 "decisions":[],
                 "actionItems":[{"text":"Send the draft tomorrow.","owner":"U1","dueHint":"Tomorrow","citations":[1,999]}],
                 "questions":[],"topics":["Project plan"]}
                """;

        ProviderResult result = validator.validate(output, prompt, "test-model");

        assertThat(result.language()).isEqualTo(SummaryLanguage.EN);
        assertThat(result.model()).isEqualTo("test-model");
        assertThat(result.content().keyPoints()).singleElement().satisfies(item -> {
            assertThat(item.text()).isEqualTo("Read The group agreed. Next step");
            assertThat(item.messageIds()).containsExactly(firstId);
        });
        assertThat(result.content().actionItems()).singleElement().satisfies(item -> {
            assertThat(item.ownerUserId()).isEqualTo(senderId);
            assertThat(item.messageIds()).containsExactly(firstId);
        });
        assertThat(result.content().participants()).hasSize(1);
    }

    @Test
    void dropsItemsWithOnlyUnknownCitationsAndUnknownOwners() {
        Prompt prompt = prompt(UUID.randomUUID(), UUID.randomUUID(), SummaryLanguage.VI);
        ProviderResult result = validator.validate(vietnameseJson(
                "[{\"text\":\"Điểm mới trong kế hoạch.\",\"citations\":[999]}]",
                "[{\"text\":\"Gửi bản nháp ngày mai.\",\"owner\":\"U999\",\"dueHint\":null,\"citations\":[999]}]"),
                prompt, "test-model");

        assertThat(result.content().keyPoints()).isEmpty();
        assertThat(result.content().actionItems()).isEmpty();
    }

    @Test
    void rejectsWrongLanguageUnknownSchemaFieldsDuplicateKeysAndMalformedJson() {
        Prompt prompt = prompt(UUID.randomUUID(), UUID.randomUUID(), SummaryLanguage.EN);

        assertBadOutput(() -> validator.validate(vietnameseJson("[]", "[]"), prompt, "model"));
        assertBadOutput(() -> validator.validate(validEnglishJson().replace("\"topics\":[]", "\"topics\":[],\"extra\":true"), prompt, "model"));
        assertBadOutput(() -> validator.validate(validEnglishJson().replace("\"overview\":\"The group agreed.\"",
                "\"overview\":\"The group agreed.\",\"overview\":\"The group agreed.\""), prompt, "model"));
        assertBadOutput(() -> validator.validate("not json", prompt, "model"));
        assertBadOutput(() -> validator.validate(validEnglishJson() + " {}", prompt, "model"));
    }

    @Test
    void randomJsonMutationsNeverEscapeUnvalidatedMessageIds() {
        UUID allowedId = UUID.randomUUID();
        Prompt prompt = prompt(allowedId, UUID.randomUUID(), SummaryLanguage.EN);
        Set<UUID> allowed = Set.of(allowedId);
        Random random = new Random(42);

        for (int iteration = 0; iteration < 250; iteration++) {
            String output = validEnglishJson();
            switch (random.nextInt(5)) {
                case 0 -> output = output.replace("[1]", "[" + random.nextInt(500) + "]");
                case 1 -> output = output.replace("\"citations\":[1]", "\"citations\":[\"bad\"]");
                case 2 -> output = output.substring(0, random.nextInt(output.length() + 1));
                case 3 -> output = output.replace("\"keyPoints\"", "\"unknown\"");
                default -> output = output + "<injected>";
            }
            try {
                ProviderResult result = validator.validate(output, prompt, "model");
                assertThat(allCitations(result)).isSubsetOf(allowed);
            } catch (SummaryProviderException exception) {
                assertThat(exception.fallbackReason()).isEqualTo(SummaryFallbackReason.LLM_BAD_OUTPUT);
            }
        }
    }

    private static Set<UUID> allCitations(ProviderResult result) {
        java.util.HashSet<UUID> ids = new java.util.HashSet<>();
        result.content().keyPoints().forEach(item -> ids.addAll(item.messageIds()));
        result.content().decisions().forEach(item -> ids.addAll(item.messageIds()));
        result.content().questions().forEach(item -> ids.addAll(item.messageIds()));
        result.content().actionItems().forEach(item -> ids.addAll(item.messageIds()));
        return ids;
    }

    private static String validEnglishJson() {
        return "{\"overview\":\"The group agreed.\",\"keyPoints\":[{\"text\":\"The group agreed.\",\"citations\":[1]}],"
                + "\"decisions\":[],\"actionItems\":[],\"questions\":[],\"topics\":[]}";
    }

    private static String vietnameseJson(String keyPoints, String actionItems) {
        return "{\"overview\":\"Nhóm đã thống nhất kế hoạch.\",\"keyPoints\":" + keyPoints
                + ",\"decisions\":[],\"actionItems\":" + actionItems + ",\"questions\":[],\"topics\":[]}";
    }

    private static Prompt prompt(UUID messageId, UUID senderId, SummaryLanguage language) {
        return new Prompt("system", "user", java.util.Map.of(1, messageId), java.util.Map.of("U1", senderId),
                List.of(new com.nexachat.summary.SummaryParticipant(senderId, "Name", 1)), language);
    }

    private static void assertBadOutput(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(SummaryProviderException.class)
                .extracting("fallbackReason")
                .isEqualTo(SummaryFallbackReason.LLM_BAD_OUTPUT);
    }
}
