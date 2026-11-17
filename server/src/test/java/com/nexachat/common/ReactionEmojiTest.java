package com.nexachat.common;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReactionEmojiTest {

    @Test
    void trimsSurroundingWhitespace() {
        assertThat(ReactionEmoji.normalize("  \uD83D\uDC4D  ")).isEqualTo("\uD83D\uDC4D");
    }

    @Test
    void normalizesToNfc() {
        // "e" + combining acute accent composes to a single code point.
        String decomposed = "e\u0301";

        String normalized = ReactionEmoji.normalize(decomposed);

        assertThat(normalized).isEqualTo("\u00e9");
        assertThat(normalized.codePointCount(0, normalized.length())).isEqualTo(1);
    }

    @Test
    void keepsAMultiCodePointSequenceIntact() {
        // Family emoji: four people joined by ZWJ, seven code points.
        String family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67\u200D\uD83D\uDC66";

        assertThat(ReactionEmoji.normalize(family)).isEqualTo(family);
    }

    @Test
    void acceptsExactlyTheColumnWidth() {
        String sixteen = "\uD83D\uDC4D".repeat(16);

        assertThat(ReactionEmoji.normalize(sixteen)).isEqualTo(sixteen);
    }

    @Test
    void rejectsOneCodePointOverTheColumnWidth() {
        String seventeen = "\uD83D\uDC4D".repeat(17);

        assertThatThrownBy(() -> ReactionEmoji.normalize(seventeen))
                .isInstanceOf(ApiException.class)
                .extracting(failure -> ((ApiException) failure).getCode())
                .isEqualTo(ApiErrorCode.VALIDATION_FAILED);
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> ReactionEmoji.normalize(null))
                .isInstanceOf(ApiException.class)
                .extracting(failure -> ((ApiException) failure).getCode())
                .isEqualTo(ApiErrorCode.VALIDATION_FAILED);
    }

    @Test
    void rejectsBlank() {
        assertThatThrownBy(() -> ReactionEmoji.normalize("   "))
                .isInstanceOf(ApiException.class)
                .extracting(failure -> ((ApiException) failure).getCode())
                .isEqualTo(ApiErrorCode.VALIDATION_FAILED);
    }
}
