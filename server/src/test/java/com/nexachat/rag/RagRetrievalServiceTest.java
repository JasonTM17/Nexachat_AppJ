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
    void answerEvidenceGateRejectsAnUnrelatedLookupInANonemptyConversation() {
        UUID conversationId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        for (long seq = 1; seq <= 5; seq++) {
            index.add(RagTestSupport.message(conversationId, UUID.randomUUID(), seq, senderId,
                    "Lan", "Lunch planning and ordinary team chatter number " + seq));
        }

        RetrievalResult ungated = service.retrieve(conversationId,
                "What is the database password?", SummaryLanguage.EN, false);
        RetrievalResult gated = service.retrieve(conversationId,
                "What is the database password?", SummaryLanguage.EN, false, null, true);

        assertThat(ungated.isEmpty()).isFalse();
        assertThat(gated.isEmpty()).isTrue();
    }

    @Test
    void answerEvidenceGateKeepsAMatchingLookupCandidate() {
        UUID conversationId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        index.add(RagTestSupport.message(conversationId, UUID.randomUUID(), 1, senderId,
                "Lan", "The server migration is scheduled for March 14 at 02:00."));

        RetrievalResult gated = service.retrieve(conversationId,
                "When is the server migration scheduled?", SummaryLanguage.EN, false, null, true);

        assertThat(gated.isEmpty()).isFalse();
    }

    @Test
    void emptyConversationReturnsAnEmptyResult() {
        RetrievalResult result = service.retrieve(UUID.randomUUID(),
                "Any question?", SummaryLanguage.EN, false);

        assertThat(result.isEmpty()).isTrue();
        assertThat(result.context()).isEmpty();
    }

    @Test
    void rankedEvidenceOutsideTheRecentWindowIsRecoveredWithItsSequenceNeighborhood() {
        UUID conversationId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        properties.getRetrieval().setMaxCandidateMessages(4);
        RagMessage fact = RagTestSupport.message(conversationId, UUID.randomUUID(), 1, senderId,
                "Lan", "The migration freeze starts on March 14.");
        index.add(fact);
        List<UUID> fillerIds = new ArrayList<>();
        for (long seq = 2; seq <= 10; seq++) {
            UUID id = UUID.randomUUID();
            fillerIds.add(id);
            index.add(RagTestSupport.message(conversationId, id, seq, senderId,
                    "Lan", "Ordinary filler message " + seq));
        }

        RetrievalResult result = service.retrieve(conversationId,
                "When does the migration freeze start?", SummaryLanguage.EN, false);

        assertThat(result.topMessage().id()).isEqualTo(fact.id());
        assertThat(result.messages()).containsKey(fact.id());
        assertThat(result.context()).isNotEmpty();
        RagContextItem factItem = result.context().stream()
                .filter(item -> item.messageIds().contains(fact.id()))
                .findFirst()
                .orElseThrow();
        assertThat(factItem.primaryMessageIds()).contains(fact.id());
        assertThat(result.context().stream().flatMap(item -> item.messageIds().stream()))
                .contains(fact.id(), fillerIds.get(0), fillerIds.get(1));
    }

    @Test
    void replyParentInsideTheRecentWindowButOutsideTheRadiusIsRecovered() {
        UUID conversationId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        properties.getRetrieval().setNeighborRadius(0);
        RagMessage parent = RagTestSupport.message(conversationId, UUID.randomUUID(), 1, senderId,
                "Lan", "Parent context: the release train leaves on Friday.");
        RagMessage child = new RagMessage(UUID.randomUUID(), conversationId, 5, senderId,
                "Lan", "The migration freeze starts on March 14.", parent.createdAt().plusSeconds(10),
                null, parent.id());
        index.add(parent);
        for (long seq = 2; seq <= 4; seq++) {
            index.add(RagTestSupport.message(conversationId, UUID.randomUUID(), seq, senderId,
                    "Lan", "Ordinary filler message " + seq));
        }
        index.add(child);

        RetrievalResult result = service.retrieve(conversationId,
                "When does the migration freeze start?", SummaryLanguage.EN, false);

        assertThat(result.context().stream().flatMap(item -> item.messageIds().stream()))
                .contains(child.id(), parent.id());
    }

    @Test
    void budgetTruncationOmitsMessageIdsWhoseTextWasNeverSerialized() {
        UUID conversationId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        java.time.Instant base = java.time.Instant.parse("2026-01-01T10:00:00Z");
        RagMessage first = new RagMessage(UUID.randomUUID(), conversationId, 1, senderId,
                "Lan", "first exact context", base, null, null);
        RagMessage second = new RagMessage(UUID.randomUUID(), conversationId, 2, senderId,
                "Lan", "second exact context", base.plusSeconds(5), null, null);
        RagMessage third = new RagMessage(UUID.randomUUID(), conversationId, 3, senderId,
                "Lan", "third must not be cited", base.plusSeconds(10), null, null);
        RagProperties.Retrieval config = new RagProperties().getRetrieval();
        config.setNeighborRadius(2);
        config.setMaxContextChars("first exact context".length() + 1 + "second exact context".length());

        RagContextBuilder.ContextResult result = RagContextBuilder.build(
                List.of(new ScoredMessage(first, 1.0)), List.of(first, second, third), index, config);

        assertThat(result.context()).hasSize(1);
        assertThat(result.context().get(0).messageIds()).containsExactly(first.id(), second.id());
        assertThat(result.context().get(0).messageIds()).doesNotContain(third.id());
        assertThat(result.messages()).doesNotContainKey(third.id());
    }

    @Test
    void optedOutLexicalOverlapIsReportedSeparatelyFromRetrievedEvidence() {
        UUID conversationId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        RagMessage visible = RagTestSupport.message(conversationId, UUID.randomUUID(), 1, senderId,
                "Lan", "The migration freeze starts on March 14.");
        RagMessage omitted = RagTestSupport.message(conversationId, UUID.randomUUID(), 2, senderId,
                "Lan", "A different migration freeze note from an opted-out author.");
        index.add(visible);
        index.add(omitted);
        index.optOutMessage(omitted.id());

        RetrievalResult result = service.retrieve(conversationId,
                "When does the migration freeze start?", SummaryLanguage.EN, false);

        assertThat(result.topMessage().id()).isEqualTo(visible.id());
        assertThat(result.optedOutEvidenceOverlap()).isTrue();
    }
}
