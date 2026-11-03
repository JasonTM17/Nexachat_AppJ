package com.nexachat.conversation;

import com.nexachat.conversation.dto.ConversationUpdatePayload;

import java.util.List;
import java.util.UUID;

/**
 * Persist-before-broadcast signal for conversation-level changes (group
 * created / members added / member removed / member left / renamed — plan
 * §11.2 {@code CONVERSATION_UPDATED}). Published inside the mutation
 * transaction, consumed by {@link ConversationBroadcastListener}
 * AFTER COMMIT.
 *
 * <p>{@code extraAudienceUserIds} lists users who must get the
 * {@code /user/queue/inbox} push even though they are NOT members at
 * fan-out time — a removed or self-left user: they are absent from
 * {@code conversation_members} after commit, so the listener's member query
 * cannot reach them and they are named explicitly. Their client resyncs and
 * the conversation disappears because the inbox query only lists member rows.
 */
public record ConversationUpdatedEvent(
        UUID conversationId,
        ConversationUpdatePayload payload,
        List<UUID> extraAudienceUserIds
) {
}
