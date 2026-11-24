package com.nexachat.message;

import com.nexachat.message.dto.MessageResponse;

import java.util.List;
import java.util.UUID;

/**
 * Persist-before-broadcast signal (plan §11.3.1): published inside the send
 * transaction, consumed by {@link MessageBroadcastListener} AFTER COMMIT so a
 * {@code MESSAGE_CREATED} frame only ever carries a durable message.
 *
 * <p>{@code subjectUserId} is the member a {@code SYSTEM} event is about (the
 * removed member, or the member whose role changed). It exists only for push
 * gating — it is never persisted or put on the wire — and is {@code null} for
 * messages without a member subject.
 *
 * <p>{@code replay} is {@code true} when the send call returned an already
 * stored {@code (sender, clientMessageId)} row instead of creating one. The
 * event is still published so a retrying sender receives its SENT signal over
 * the WebSocket, but push fan-out must skip it: the recipient was already
 * notified when the row was created.
 *
 * <p>{@code admittedUserIds} and {@code peerSuppressedSenderId} form the
 * fan-out snapshot: the member list admitted when the message was created
 * ({@code joinedAt <= createdAt}) and the sender id when the row was stored
 * peer-suppressed. Both are computed inside the publishing transaction —
 * which holds the conversation write lock every group membership mutation
 * serializes on, and which wrote the one-way {@code peer_suppressed} flag —
 * so the AFTER_COMMIT listeners can reuse them instead of re-reading the
 * database. Both are {@code null} when the publisher did not freeze them
 * (replays and lost insert races: the surviving row's own flag and snapshot
 * belong to another transaction), and listeners then fall back to their
 * existing post-commit queries. When {@code admittedUserIds} is non-null,
 * {@code peerSuppressedSenderId} is authoritative and {@code null} means
 * "not peer-suppressed".
 */
public record MessageCreatedEvent(UUID conversationId, MessageResponse message,
                                  UUID subjectUserId, boolean replay,
                                  List<UUID> admittedUserIds, UUID peerSuppressedSenderId) {

    /** Convenience constructor for a newly created message without a SYSTEM subject member. */
    public MessageCreatedEvent(UUID conversationId, MessageResponse message) {
        this(conversationId, message, null, false, null, null);
    }

    /** Convenience constructor for a newly created SYSTEM event. */
    public MessageCreatedEvent(UUID conversationId, MessageResponse message, UUID subjectUserId) {
        this(conversationId, message, subjectUserId, false, null, null);
    }

    /**
     * Convenience constructor for a replayed send — no fan-out snapshot, so
     * the listeners fall back to their post-commit queries as before.
     */
    public MessageCreatedEvent(UUID conversationId, MessageResponse message,
                               UUID subjectUserId, boolean replay) {
        this(conversationId, message, subjectUserId, replay, null, null);
    }
}
