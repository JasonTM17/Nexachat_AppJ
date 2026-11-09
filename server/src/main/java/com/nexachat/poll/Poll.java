package com.nexachat.poll;

import com.nexachat.conversation.Conversation;
import com.nexachat.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Maps {@code polls} (V15__group_polls.sql): one poll in a GROUP conversation.
 *
 * <p>{@code version} is a server-assigned revision from
 * {@code poll_version_seq}, consumed on create and on every visible state
 * change. It is monotonically increasing across the deployment, so clients can
 * request {@code version > since} on the change feed without a WebSocket frame
 * for polls. A no-op retry (same selection, repeated close/delete) leaves the
 * version untouched.
 *
 * <p>Deletion is a tombstone: the row and its options/votes stay so the change
 * feed can report {@code deleted:true}; every normal read filters
 * {@code deleted_at IS NULL}.
 */
@Entity
@Table(name = "polls")
public class Poll {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false, updatable = false)
    private Conversation conversation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false, updatable = false)
    private User createdBy;

    @Column(name = "question", nullable = false, length = 200)
    private String question;

    @Column(name = "multiple_choice", nullable = false)
    private boolean multipleChoice;

    @Column(name = "anonymous", nullable = false)
    private boolean anonymous;

    @Column(name = "closes_at")
    private Instant closesAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "closed_by")
    private User closedBy;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Poll() {
        // JPA only
    }

    public static Poll create(Conversation conversation, User createdBy, String question,
                              boolean multipleChoice, boolean anonymous, Instant closesAt,
                              long version) {
        Poll poll = new Poll();
        poll.conversation = conversation;
        poll.createdBy = createdBy;
        poll.question = question;
        poll.multipleChoice = multipleChoice;
        poll.anonymous = anonymous;
        poll.closesAt = closesAt;
        poll.version = version;
        return poll;
    }

    @PrePersist
    void onPrePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    /**
     * Expiry boundary: a poll whose {@code closes_at} equals {@code now} is
     * already closed; a vote can never land at the configured instant.
     */
    static boolean isExpired(Instant closesAt, Instant now) {
        return closesAt != null && !now.isBefore(closesAt);
    }

    public boolean isClosedAt(Instant now) {
        return closedAt != null || isExpired(closesAt, now);
    }

    public void close(User actor, Instant now) {
        this.closedAt = now;
        this.closedBy = actor;
    }

    public void markDeleted(Instant now) {
        this.deletedAt = now;
    }

    /** Advances the change-feed revision; only call when visible state changed. */
    public void bumpVersion(long nextVersion) {
        this.version = nextVersion;
    }

    public UUID getId() {
        return id;
    }

    public Conversation getConversation() {
        return conversation;
    }

    public User getCreatedBy() {
        return createdBy;
    }

    public String getQuestion() {
        return question;
    }

    public boolean isMultipleChoice() {
        return multipleChoice;
    }

    public boolean isAnonymous() {
        return anonymous;
    }

    public Instant getClosesAt() {
        return closesAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public User getClosedBy() {
        return closedBy;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
