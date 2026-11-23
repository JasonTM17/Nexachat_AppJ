package com.nexachat.ws;

/**
 * Frozen event vocabulary (plan §11.2): {@code eventType ∈
 * {MESSAGE_CREATED, MESSAGE_UPDATED, MESSAGE_DELETED, MESSAGE_READ,
 * REACTION_UPDATED, PIN_UPDATED, USER_TYPING, CONVERSATION_UPDATED,
 * USER_ONLINE, USER_OFFLINE, SCHEDULED_MESSAGE_UPDATED}}. Values serialize as
 * their name in the {@link EventEnvelope}. Only the subset emitted by this
 * phase is produced; the rest are contract vocabulary for later phases.
 */
public enum WsEventType {
    MESSAGE_CREATED,
    MESSAGE_UPDATED,
    MESSAGE_DELETED,
    MESSAGE_READ,
    MESSAGE_DELIVERED,
    REACTION_UPDATED,
    PIN_UPDATED,
    USER_TYPING,
    CONVERSATION_UPDATED,
    USER_ONLINE,
    USER_OFFLINE,
    SCHEDULED_MESSAGE_UPDATED
}
