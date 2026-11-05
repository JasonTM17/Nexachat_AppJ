package com.nexachat.message.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * Reply-target preview embedded in {@link MessageResponse} (the contract's
 * {@code replyTo} object): enough to render the quote line without a second
 * fetch — the target's id, sender, type, and content. {@code deleted} is
 * {@code true} when the target was soft-deleted; its {@code content} is then
 * {@code null} (deleted text is never re-broadcast — plan §9.3.6).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReplyRefResponse(UUID id,
                               UUID senderId,
                               String senderDisplayName,
                               String type,
                               @JsonInclude(JsonInclude.Include.ALWAYS)
                               String content,
                               boolean deleted) {
}
