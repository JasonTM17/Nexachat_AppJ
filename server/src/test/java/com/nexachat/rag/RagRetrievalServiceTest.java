package com.nexachat.rag;

import com.nexachat.summary.SummaryLanguage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RagRetrievalServiceTest {

    private final RagProperties properties = new RagProperties();
    private final InMemoryRagMessageIndex index = new InMemoryRagMessageIndex();
    private final RagRetrievalService service = RagTestSupport.retrieval(index, properties);

    @Test
    void retrievesTheMessageThatAnswersALookupQuestion() {
        UUID conversationId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        RagMessage fact = RagTestSupport.message(conversationId, UUID.randomUUID(), 5, senderId,
                "Lan", "The server migration is scheduled for March 14 at 02:00.");
        for (long seq = 1; seq <= 4; seq++) {
            index.add(RagTestSupport.message(conversationId, UUID.randomUUID(), seq, senderId,
                    "Lan", "Normal chatter number " + seq));
        }
        index.add(fact);

        RetrievalResult result = service.retrieve(conversationId,
                "When is the server migration scheduled?", SummaryLanguage.EN, false);

        assertThat(result.isEmpty()).isFalse();
        assertThat(result.topMessage().id()).isEqualTo(fact.id());
        assertThat(result.context()).isNotEmpty();
        assertThat(result.inputChars()).isPositive();
        assertThat(result.inputChars()).isLessThanOrEqualTo(properties.getRetrieval().getMaxContextChars());
        assertThat(result.context()).allSatisfy(item ->
                assertThat(item.messageIds()).isNotEmpty());
    }

    @Test
    void contextStaysUnderTheHardCharacterBudget() {
        UUID conversationId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        properties.getRetrieval().setMaxContextChars(200);
        for (long seq = 1; seq <= 20; seq++) {
            index.add(RagTestSupport.message(conversationId, UUID.randomUUID(), seq, senderId,
                    "Lan", "Message " + seq + " ".repeat(80) + "deployment window"));
        }

        RetrievalResult result = service.retrieve(conversationId,
                "What did we decide about the deployment window?", SummaryLanguage.EN, false);

        assertThat(result.inputChars()).isLessThanOrEqualTo(200);
    }

    @Test
    void neighbourExpansionIncludesAdjacentMessages() {
        UUID conversationId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        List<UUID> ids = new ArrayList<>();
        for (long seq = 1; seq <= 10; seq++) {
            UUID id = UUID.randomUUID();
            ids.add(id);
            index.add(RagTestSupport.message(conversationId, id, seq, senderId, "Lan",
                    seq == 5 ? "The budget was approved at 1,200 dollars." : "Filler message " + seq));
        }

        RetrievalResult result = service.retrieve(conversationId,
                "How much budget was approved?", SummaryLanguage.EN, false);

        List<UUID> contextIds = result.context().stream()
                .flatMap(item -> item.messageIds().stream())
                .toList();
        assertThat(contextIds).contains(ids.get(3), ids.get(4), ids.get(5));
    }

    @Test
    void emptyConversationReturnsAnEmptyResult() {
        RetrievalResult result = service.retrieve(UUID.randomUUID(),
                "Any question?", SummaryLanguage.EN, false);

        assertThat(result.isEmpty()).isTrue();
        assertThat(result.context()).isEmpty();
    }
}
