package com.nexachat.admin;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Folding semantics used by the sensitive-message keyword rules. {@link #fold}
 * is the public shape; {@link #foldView} must produce exactly the same text
 * plus a faithful index map back to the original.
 */
class TextNormalizerTest {

    @Test
    void foldStripsVietnameseMarksAndLowercases() {
        assertThat(TextNormalizer.fold("Tự Tử")).isEqualTo("tu tu");
    }

    @Test
    void foldMapsDStroke() {
        assertThat(TextNormalizer.fold("Đi đâu Đó")).isEqualTo("di dau do");
    }

    @Test
    void foldCollapsesWhitespaceRunsAndTrimsTheEdges() {
        assertThat(TextNormalizer.fold("  \t a \n\n b   ")).isEqualTo("a b");
    }

    @Test
    void foldIsNullSafe() {
        assertThat(TextNormalizer.fold(null)).isEmpty();
    }

    @Test
    void foldIsIdempotent() {
        String once = TextNormalizer.fold("Chào  bạn, Tự Tử nhé!");
        assertThat(TextNormalizer.fold(once)).isEqualTo(once);
    }

    @Test
    void foldViewTextMatchesFold() {
        String input = "   Chào bạn   Tự Tử nhé!  ";
        assertThat(TextNormalizer.foldView(input).text())
                .isEqualTo(TextNormalizer.fold(input));
    }

    @Test
    void foldViewOffsetsPointAtTheOriginalChars() {
        // Original "tự  tử": t(0) ự(1) ' '(2) ' '(3) t(4) ự(5); the middle
        // space run collapses, so folded "tu tu" maps t→0, u→1, ' '→2, t→4, u→5.
        TextNormalizer.FoldedView view = TextNormalizer.foldView("tự  tử");

        assertThat(view.text()).isEqualTo("tu tu");
        assertThat(view.originalOffset(0)).isEqualTo(0);
        assertThat(view.originalOffset(1)).isEqualTo(1);
        assertThat(view.originalOffset(2)).isEqualTo(2);
        assertThat(view.originalOffset(3)).isEqualTo(4);
        assertThat(view.originalOffset(4)).isEqualTo(5);
        assertThat(view.originalEnd(0, 5)).isEqualTo(6);
    }

    @Test
    void leetMapsObfuscationOntoLetters() {
        assertThat(TextNormalizer.leet(TextNormalizer.fold("s3nd"))).isEqualTo("send");
    }
}
