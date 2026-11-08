package com.nexachat.message.dto;

import java.util.UUID;

/**
 * Projection of one {@code reaction_events} row for the delta query: the
 * event id (cursor) and the message it belongs to. Only these two columns are
 * read — the payload itself is rebuilt from the current
 * {@code message_reactions} state so a removal is reflected.
 */
public interface ReactionEventId {

    Long getId();

    UUID getMessageId();
}
