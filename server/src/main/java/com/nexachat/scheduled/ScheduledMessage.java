package com.nexachat.scheduled;

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
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Maps {@code scheduled_messages} (V46): one TEXT message the author asked
 * the server to deliver at {@code sendAt}.
 *
 * <p>{@code clientMessageId} is both the POST idempotency key (unique with
 * the sender) and the {@code clientMessageId} of the message eventually
 * sent, so a crash between send and mark is deduplicated by
 * {@code messages_sender_client_unique}.</p>
 *
 * <p>{@code claimToken} is the dispatcher's lease token; a row can be
 * re-claimed only after the lease expires. {@code nextAttemptAt}/
 * {@code attemptCount} hold the exponential-backoff retry state for
 * transient send failures; both stay {@code NULL}/0 until the first retry
 * release.</p>
 *
 * <p>Every terminal transition scrubs {@code body} to {@code ""} — the
 * idempotency key and audit fields remain for replays and debugging.</p>
 */
@Entity
@Table(name = "scheduled_messages")
public class ScheduledMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false)
    private Conversation conversation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sender_id", nullable = false)
    private User sender;

    @Column(name = "client_message_id", nullable = false)
    private UUID clientMessageId;

    @Column(name = "body", nullable = false)
    private String body;

    @Column(name = "send_at", nullable = false)
    private Instant sendAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 8)
    private ScheduledMessageStatus status = ScheduledMessageStatus.PENDING;

    @Column(name = "failure_code", length = 40)
    private String failureCode;

    @Column(name = "sent_message_id")
    private UUID sentMessageId;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "claim_token")
    private UUID claimToken;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ScheduledMessage() {
        // JPA only
    }

    public static ScheduledMessage create(Conversation conversation, User sender,
                                          UUID clientMessageId, String body, Instant sendAt) {
        ScheduledMessage message = new ScheduledMessage();
        message.conversation = conversation;
        message.sender = sender;
        message.clientMessageId = clientMessageId;
        message.body = body;
        message.sendAt = sendAt;
        return message;
    }

    @PrePersist
    void onPrePersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void onPreUpdate() {
        this.updatedAt = Instant.now();
    }

    public void clearClaim() {
        this.claimedAt = null;
        this.claimToken = null;
    }

    /**
     * Marks the row delivered. {@code sentMessageId} is the id of the message
     * row the send path stored (or replayed) for {@code clientMessageId}.
     * Terminal: the body is scrubbed per the privacy contract.
     */
    public void markSent(UUID sentMessageId, Instant now) {
        this.status = ScheduledMessageStatus.SENT;
        this.sentMessageId = sentMessageId;
        this.failureCode = null;
        this.body = "";
        this.nextAttemptAt = null;
        clearClaim();
        this.updatedAt = now;
    }

    /** Marks the row permanently failed with a stable machine-readable code. */
    public void markFailed(String failureCode, Instant now) {
        this.status = ScheduledMessageStatus.FAILED;
        this.failureCode = failureCode;
        this.body = "";
        this.nextAttemptAt = null;
        clearClaim();
        this.updatedAt = now;
    }

    /** Cancels the row; {@code failureCode} may carry an administrative reason. */
    public void cancel(String failureCode, Instant now) {
        this.status = ScheduledMessageStatus.CANCELED;
        this.failureCode = failureCode;
        this.body = "";
        this.nextAttemptAt = null;
        clearClaim();
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public Conversation getConversation() {
        return conversation;
    }

    public User getSender() {
        return sender;
    }

    public UUID getClientMessageId() {
        return clientMessageId;
    }

    public String getBody() {
        return body;
    }

    public Instant getSendAt() {
        return sendAt;
    }

    public ScheduledMessageStatus getStatus() {
        return status;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public UUID getSentMessageId() {
        return sentMessageId;
    }

    public Instant getClaimedAt() {
        return claimedAt;
    }

    public UUID getClaimToken() {
        return claimToken;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
