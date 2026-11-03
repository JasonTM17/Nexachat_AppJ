package com.nexachat.conversation.dto;

import java.util.List;
import java.util.UUID;

/**
 * Payload of the {@code CONVERSATION_UPDATED} frame (plan §11.2), sent on the
 * conversation topic and to every affected {@code /user/queue/inbox}: a full
 * member/state snapshot so a client can apply the change with zero extra
 * reads — or simply resync via REST (the inbox query already excludes
 * conversations the user no longer belongs to).
 *
 * @param change why the frame was emitted:
 *               {@code CREATED | MEMBERS_ADDED | MEMBER_REMOVED | MEMBER_LEFT | UPDATED}
 */
public record ConversationUpdatePayload(
        UUID conversationId,
        String title,
        String avatarUrl,
        String change,
        List<MemberResponse> members
) {
}
