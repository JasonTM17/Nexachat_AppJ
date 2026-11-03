package com.nexachat.message;

import com.nexachat.conversation.Conversation;
import com.nexachat.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Maps {@code pinned_messages} (V3__interactions.sql): one pin per
 * {@code (conversation_id, message_id)} pair. The composite PK makes a repeat
 * pin a no-op by construction; the ≤ 3-per-conversation ceiling is a service
 * invariant enforced under a row lock on the conversation (see
 * {@link PinService}).
 *
 * <p>Read model only — writes are the native statements in
 * {@link PinnedMessageRepository}.
 */
@Entity
@Table(name = "pinned_messages")
@IdClass(PinnedMessageId.class)
public class PinnedMessage {

    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false)
    private Conversation conversation;

    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "message_id", nullable = false)
    private Message message;

    /** The member who pinned — audit metadata, NOT NULL but no cascade. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pinned_by", nullable = false)
    private User pinnedBy;

    @Column(name = "pinned_at", nullable = false, updatable = false)
    private Instant pinnedAt;

    protected PinnedMessage() {
        // JPA only
    }

    public Conversation getConversation() {
        return conversation;
    }

    public Message getMessage() {
        return message;
    }

    public User getPinnedBy() {
        return pinnedBy;
    }

    public Instant getPinnedAt() {
        return pinnedAt;
    }
}
