package com.nexachat.rag;

import com.nexachat.summary.SummaryLanguage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RagScorerTest {

    @Test
    void lexicalRankPutsTheMessageContainingTheQuestionTermsFirst() {
        RagMessage match = message("m1", "The deployment window is Friday at 10am.");
        RagMessage other = message("m2", "Please bring the printed agenda to the room.");
        List<RagMessage> messages = List.of(match, other);

        List<ScoredMessage> ranked = RagScorer.lexicalRank(
                messages, "When is the deployment window?", SummaryLanguage.EN);

        assertThat(ranked).isNotEmpty();
        assertThat(ranked.get(0).message().id()).isEqualTo(match.id());
        assertThat(ranked.get(0).score()).isGreaterThan(ranked.get(1).score());
    }

    @Test
    void vectorRankOrdersByCosineSimilarity() {
        RagMessage first = message("m1", "first");
        RagMessage second = message("m2", "second");
        float[] questionVector = new float[] {1.0f, 0.0f};
        Map<UUID, float[]> vectors = Map.of(
                first.id(), new float[] {1.0f, 0.0f},
                second.id(), new float[] {0.0f, 1.0f});

        List<ScoredMessage> ranked = RagScorer.vectorRank(
                List.of(first, second), vectors, questionVector);

        assertThat(ranked.get(0).message().id()).isEqualTo(first.id());
        assertThat(ranked.get(0).score()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1.0e-6));
    }

    @Test
    void fusionRewardsMessagesPresentInBothLists() {
        RagMessage both = message("m1", "both");
        RagMessage lexicalOnly = message("m2", "lexical");
        RagMessage vectorOnly = message("m3", "vector");
        List<ScoredMessage> lexical = List.of(
                new ScoredMessage(both, 1.0), new ScoredMessage(lexicalOnly, 0.5));
        List<ScoredMessage> vector = List.of(
                new ScoredMessage(both, 1.0), new ScoredMessage(vectorOnly, 0.5));

        List<ScoredMessage> fused = RagScorer.fuse(lexical, vector, 60);

        assertThat(fused).hasSize(3);
        assertThat(fused.get(0).message().id()).isEqualTo(both.id());
        assertThat(fused.get(0).score()).isGreaterThanOrEqualTo(fused.get(1).score());
    }

    private static RagMessage message(String id, String content) {
        return new RagMessage(UUID.nameUUIDFromBytes(id.getBytes()), UUID.nameUUIDFromBytes("c".getBytes()),
                1, UUID.nameUUIDFromBytes("u".getBytes()), "User", content,
                Instant.now(), null, null);
    }
}
