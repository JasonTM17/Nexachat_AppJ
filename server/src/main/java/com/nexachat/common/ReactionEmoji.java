package com.nexachat.common;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;

import java.text.Normalizer;

/**
 * The one emoji rule shared by message reactions and feed post reactions.
 *
 * <p>An emoji is trimmed, NFC-normalized and bounded to the 16 characters the
 * {@code emoji} columns allow, counted as code points so multi-code-unit
 * sequences (flags, ZWJ families) are measured the way PostgreSQL measures
 * {@code VARCHAR(16)}. Anything blank or longer is a client bug and fails
 * {@code VALIDATION_FAILED}.
 */
public final class ReactionEmoji {

    /** {@code message_reactions.emoji} and {@code public_post_reactions.emoji}. */
    public static final int MAX_LENGTH = 16;

    private ReactionEmoji() {
    }

    /**
     * @param raw the client-supplied emoji
     * @return the normalized emoji, never blank
     * @throws ApiException {@code VALIDATION_FAILED} when blank or too long
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "emoji is required");
        }
        String emoji = Normalizer.normalize(raw.trim(), Normalizer.Form.NFC);
        if (emoji.isEmpty() || emoji.codePointCount(0, emoji.length()) > MAX_LENGTH) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "emoji must be 1-" + MAX_LENGTH + " characters");
        }
        return emoji;
    }
}
