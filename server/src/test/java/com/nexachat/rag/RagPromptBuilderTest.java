package com.nexachat.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.summary.SummaryLanguage;
import com.nexachat.summary.llm.Redactor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RagPromptBuilderTest {

    private final RagPromptBuilder builder = new RagPromptBuilder(new ObjectMapper(), new Redactor());

    @Test
    void redactsPiiInTheContextAndQuestionAndMarksTheBlockUntrusted() {
        RagContextItem item = new RagContextItem(1, List.of(UUID.randomUUID()), 1, "U1",
                "Call me at +84912345678 or email a@b.com, see https://secret.example.com/path");

        RagPrompt prompt = builder.build("What is my phone +84912345678?",
                SummaryLanguage.EN, List.of(item));

        String outbound = prompt.systemMessage() + "\n" + prompt.userMessage();
        assertThat(outbound).contains("[REDACTED_PHONE]");
        assertThat(outbound).contains("[REDACTED_EMAIL]");
        assertThat(outbound).doesNotContain("+84912345678");
        assertThat(outbound).doesNotContain("a@b.com");
        assertThat(outbound).doesNotContain("/path");
        assertThat(outbound).contains("BEGIN UNTRUSTED CHAT JSON");
        assertThat(outbound).contains("END UNTRUSTED CHAT JSON");
        assertThat(outbound).contains("untrusted conversation data");
    }

    @Test
    void mapsEveryContextIndexToItsCoveredMessageIds() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        RagContextItem item = new RagContextItem(1, List.of(first, second), 1, "U1", "merged");

        RagPrompt prompt = builder.build("Question?", SummaryLanguage.EN, List.of(item));

        assertThat(prompt.messageIdsByIndex()).containsEntry(1, List.of(first, second));
    }
}
