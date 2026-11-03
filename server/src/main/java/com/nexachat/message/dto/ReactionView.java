package com.nexachat.message.dto;

/**
 * Per-emoji reaction summary inside {@link MessageResponse} (plan §10.3
 * example: {@code {"emoji":"👍","count":1,"byMe":false}}). {@code byMe} is
 * viewer-relative — whether the requesting user holds this reaction.
 */
public record ReactionView(String emoji, long count, boolean byMe) {
}
