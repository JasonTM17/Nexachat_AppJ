package com.nexachat.conversation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * One member inside a conversation response: just what a chat list needs —
 * no per-member role/watermark exposure yet (group phase adds it if needed).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConversationMemberView(
        UUID userId,
        String displayName,
        String phone,
        boolean deleted
) {
}
