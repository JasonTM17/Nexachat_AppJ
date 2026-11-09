package com.nexachat.summary;

import java.util.UUID;

/**
 * Caller-scoped AI opt-out state for one conversation. {@code aiOptOut}
 * describes only the requesting member; other members' choices are never
 * exposed.
 */
public record ConversationAiOptOutResponse(UUID conversationId, boolean aiOptOut) {
}
