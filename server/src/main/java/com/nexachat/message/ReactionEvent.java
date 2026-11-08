package com.nexachat.message;

import com.nexachat.conversation.Conversation;
import com.nexachat.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Maps {@code reaction_events} (V10): one immutable row per reaction
 * {@code ADD}/{@code REMOVE}. The row is written in the same transaction as
 * the {@code message_reactions} change, so the delta sync can report removals
 * that leave no {@code message_reactions} row behind.
 *
 * <p>This is an append-only audit table: rows are never updated and are purged
 * after a bounded retention window. Reads go through
 * {@link ReactionEventRepository}'s projections; the entity exists for the
 * retention delete and for schema binding.
 */
@Entity
@Table(name = "reaction_events")
public class ReactionEvent {

    public enum Action {
        ADD,
        REMOVE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false)
    private Conversation conversation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "message_id", nullable = false)
    private Message message;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** {@code VARCHAR(16)} in DDL — same bound as {@link MessageReaction#getEmoji()}. */
    @Column(name = "emoji", nullable = false, length = 16)
    private String emoji;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 6)
    private Action action;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ReactionEvent() {
        // JPA only
    }

    public Long getId() {
        return id;
    }

    public Conversation getConversation() {
        return conversation;
    }

    public Message getMessage() {
        return message;
    }

    public User getUser() {
        return user;
    }

    public String getEmoji() {
        return emoji;
    }

    public Action getAction() {
        return action;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
