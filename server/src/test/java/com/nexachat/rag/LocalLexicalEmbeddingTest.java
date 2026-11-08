package com.nexachat.rag;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LocalLexicalEmbeddingTest {

    private final LocalLexicalEmbedding embedding = new LocalLexicalEmbedding();

    @Test
    void producesDeterministicNormalizedVectors() {
        float[] first = embedding.embed("Chào bạn, cuộc họp lúc 3 giờ");
        float[] second = embedding.embed("Chào bạn, cuộc họp lúc 3 giờ");

        assertThat(first).hasSize(256);
        assertThat(first).containsExactly(second);
        assertThat(l2Norm(first)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1.0e-5));
    }

    @Test
    void foldsVietnameseAccentsSoSimilarQuestionsStayClose() {
        float[] accented = embedding.embed("Chào bạn, cuộc họp lúc 3 giờ");
        float[] folded = embedding.embed("Chao ban, cuoc hop luc 3 gio");

        assertThat(VectorCodec.cosine(accented, folded)).isGreaterThan(0.95);
    }

    @Test
    void differentContentProducesDifferentVectors() {
        float[] left = embedding.embed("The release is on Friday");
        float[] right = embedding.embed("Please water the office plants");

        assertThat(VectorCodec.cosine(left, right)).isLessThan(0.6);
    }

    @Test
    void emptyInputProducesAZeroVector() {
        float[] vector = embedding.embed("");

        assertThat(vector).hasSize(256);
        assertThat(l2Norm(vector)).isZero();
    }

    private static double l2Norm(float[] vector) {
        double sum = 0.0;
        for (float value : vector) {
            sum += (double) value * value;
        }
        return Math.sqrt(sum);
    }
}
