package com.nexachat.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.summary.SummaryLanguage;
import com.nexachat.summary.llm.Redactor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
        assertThat(validator.validate("{\"answer\":null,\"citations\":[1]}", prompt)).isNull();
    }

    @Test
    void distinguishesAValidAbstentionFromInvalidOutput() {
        RagOutputValidator.Validation validation = validator.validateDetailed(
                "{\"answer\":null,\"citations\":[]}", prompt);

        assertThat(validation.status()).isEqualTo(RagOutputValidator.Status.ABSTAIN);
        assertThat(validation.answer()).isNull();
        assertThat(validator.validateDetailed("not json", prompt).status())
                .isEqualTo(RagOutputValidator.Status.INVALID);
        assertThat(validator.validateDetailed("{\"answer\":\"   \",\"citations\":[1]}", prompt).status())
                .isEqualTo(RagOutputValidator.Status.INVALID);
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

    @Test
    void speakerQuoteMustMatchACompleteSettledSentenceInOneSentSource() throws Exception {
        ObjectMapper json = new ObjectMapper();
        var builder = new RagPromptBuilder(json, new Redactor());
        String settled = "The maintenance slot is March 14 at 02:00.";
        String unsettled = "I have not confirmed the maintenance slot.";
        String original = "My test address is qa@example.invalid.";
        var context = new RagContextItem(1, List.of(messageId), 2, "U1", unsettled + " " + settled + " " + original);
        RagPrompt speakerPrompt = builder.buildSpeakerQuote("What is the maintenance slot?", SummaryLanguage.EN, List.of(context));

        RagLlmAnswer verified = validator.validate(json.writeValueAsString(Map.of("answer", settled, "citations", List.of(1))), speakerPrompt);
        assertThat(verified).isNotNull();
        assertThat(verified.citationIds()).containsExactly(messageId);
        assertThat(speakerPrompt.userMessage()).doesNotContain("qa@example.invalid", "priorTurns");
        for (String invalid : List.of("March 14", unsettled, original, settled.replace("March 14", "March 15"))) {
            String raw = json.writeValueAsString(Map.of("answer", invalid, "citations", List.of(1)));
            assertThat(validator.validateDetailed(raw, speakerPrompt).status()).isEqualTo(RagOutputValidator.Status.INVALID);
        }
        String fabricatedCitation = json.writeValueAsString(Map.of("answer", settled, "citations", List.of(1, 8)));
        assertThat(validator.validateDetailed(fabricatedCitation, speakerPrompt).status()).isEqualTo(RagOutputValidator.Status.INVALID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Hạn gửi bản thảo là ngày nào?", "Hạn gửi bản thảo là ngày nào ạ?"})
    void speakerDateQuoteRejectsADateOnlyAttachedToAnotherEvent(String question) throws Exception {
        ObjectMapper json = new ObjectMapper();
        var builder = new RagPromptBuilder(json, new Redactor());
        String relative = "Hạn gửi bản thảo nằm sau buổi hướng dẫn ngày 7/5.";
        String explicit = "Hạn gửi bản thảo là ngày 9/5, sau buổi hướng dẫn.";
        var context = new RagContextItem(1, List.of(messageId), 2, "U1", relative + " " + explicit);
        RagPrompt prompt = builder.buildSpeakerQuote(question,
                SummaryLanguage.VI, List.of(context));

        String rejected = json.writeValueAsString(Map.of("answer", relative, "citations", List.of(1)));
        assertThat(validator.validateDetailed(rejected, prompt).status()).isEqualTo(RagOutputValidator.Status.INVALID);
        String accepted = json.writeValueAsString(Map.of("answer", explicit, "citations", List.of(1)));
        assertThat(validator.validate(accepted, prompt)).isNotNull();
        var relativeOnly = new RagContextItem(1, List.of(messageId), 2, "U1", relative);
        RagPrompt noQuote = builder.buildSpeakerQuote(question,
                SummaryLanguage.VI, List.of(relativeOnly));
        assertThat(validator.validateDetailed(rejected, noQuote).status()).isEqualTo(RagOutputValidator.Status.INVALID);
    }

    @Test
    void directDateQuoteMustMatchTheDatedAssertionInItsOwnSource() throws Exception {
        ObjectMapper json = new ObjectMapper();
        var builder = new RagPromptBuilder(json, new Redactor());
        String statement = "Hạn nộp phiếu khảo sát là mười hai tháng Tám.";
        String differentEvent = "Hạn nộp phiếu khảo sát sẽ công bố riêng, còn ngày duyệt là 6/8.";
        var source = new RagContextItem(1, List.of(messageId), 2, "U1", statement);
        var distractor = new RagContextItem(2, List.of(UUID.randomUUID()), 3, "U1", differentEvent);
        RagPrompt prompt = builder.buildSpeakerQuote("Hạn nộp phiếu khảo sát là ngày nào?",
                SummaryLanguage.VI, List.of(source, distractor));

        String accepted = json.writeValueAsString(Map.of("answer", statement, "citations", List.of(1)));
        assertThat(validator.validate(accepted, prompt).citationIds()).containsExactly(messageId);
        for (String rejected : List.of(statement, differentEvent)) {
            String raw = json.writeValueAsString(Map.of("answer", rejected, "citations", List.of(2)));
            assertThat(validator.validateDetailed(raw, prompt).status()).isEqualTo(RagOutputValidator.Status.INVALID);
        }
    }

    @Test
    void dateQuoteRejectsPendingConfirmationAndAnIsolatedDateFromAnotherClause() throws Exception {
        ObjectMapper json = new ObjectMapper();
        var builder = new RagPromptBuilder(json, new Redactor());
        String pending = "Buổi kiểm kê kho là ngày 14/7, nhóm mình còn chờ xác nhận cuối cùng.";
        String confirmed = "Buổi kiểm kê kho là ngày 18/7, còn ngày duyệt phiếu là 16/7;"
                + " nhóm chờ thông báo chính thức về chỗ đỗ xe.";
        var context = new RagContextItem(1, List.of(messageId), 2, "U1", pending + " " + confirmed);
        RagPrompt prompt = builder.buildSpeakerQuote("Buổi kiểm kê kho là ngày nào?",
                SummaryLanguage.VI, List.of(context));

        for (String quote : List.of(pending, "16/7")) {
            String raw = json.writeValueAsString(Map.of("answer", quote, "citations", List.of(1)));
            assertThat(validator.validateDetailed(raw, prompt).status()).isEqualTo(RagOutputValidator.Status.INVALID);
        }
        String fullQuote = json.writeValueAsString(Map.of("answer", confirmed, "citations", List.of(1)));
        assertThat(validator.validate(fullQuote, prompt).citationIds()).containsExactly(messageId);
    }
}
