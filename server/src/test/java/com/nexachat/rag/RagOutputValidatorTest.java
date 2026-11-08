package com.nexachat.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.summary.SummaryLanguage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RagOutputValidatorTest {

    private final RagOutputValidator validator = new RagOutputValidator(new ObjectMapper());
    private final UUID messageId = UUID.randomUUID();
    private final RagPrompt prompt = new RagPrompt(
            "system", "user", Map.of(1, List.of(messageId)), SummaryLanguage.EN);

    @Test
    void acceptsAnAnswerWhoseCitationsMapToTheRetrievedContext() {
        RagLlmAnswer answer = validator.validate(
                "{\"answer\":\"The migration is on March 14.\",\"citations\":[1]}", prompt);

        assertThat(answer).isNotNull();
        assertThat(answer.answer()).contains("March 14");
        assertThat(answer.citationIds()).containsExactly(messageId);
    }

    @Test
    void rejectsAnAnswerLeftWithoutAnyValidCitation() {
        RagLlmAnswer answer = validator.validate(
                "{\"answer\":\"I cannot answer.\",\"citations\":[9]}", prompt);

        assertThat(answer).isNull();
    }

    @Test
    void rejectsMalformedJsonAndMissingFields() {
        assertThat(validator.validate("not json", prompt)).isNull();
        assertThat(validator.validate("{\"citations\":[1]}", prompt)).isNull();
        assertThat(validator.validate("{\"answer\":null,\"citations\":[]}", prompt)).isNull();
    }

    @Test
    void rejectsAnAnswerWrittenInTheWrongLanguage() {
        RagLlmAnswer answer = validator.validate(
                "{\"answer\":\"Cuộc họp diễn ra vào thứ Sáu.\",\"citations\":[1]}", prompt);

        assertThat(answer).isNull();
    }

    @Test
    void dropsUnknownIndexesButKeepsValidOnes() {
        RagLlmAnswer answer = validator.validate(
                "{\"answer\":\"The window is Friday.\",\"citations\":[1,42]}", prompt);

        assertThat(answer).isNotNull();
        assertThat(answer.citationIds()).containsExactly(messageId);
    }
}
