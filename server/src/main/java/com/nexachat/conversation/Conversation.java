package com.nexachat.conversation;

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
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Maps {@code conversations} (V1__init.sql).
 *
 * <p>For DIRECT rows {@code direct_key} holds {@code LEAST(u1,u2):GREATEST(u1,u2)}
 * (lowercase UUID strings, lexicographic compare) and carries the UNIQUE
 * constraint that makes "open chat" idempotent under races (plan §9.3.3 — the
 * constraint resolves the race, not defensive code). The DB CHECK enforces
 * {@code type='DIRECT' <=> direct_key IS NOT NULL}.
 *
 * <p>Members are read via {@link ConversationMemberRepository} projections —
 * no {@code @OneToMany} collection is mapped, per plan §9.3.7 (list reads use
 * direct queries instead of entity graphs).
 */
@Entity
@Table(name = "conversations")
public class Conversation {

    /** {@code VARCHAR(6) CHECK (type IN ('DIRECT','GROUP'))}. */
    public enum Type {
        DIRECT,
        GROUP
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 6)
    private Type type;

    /** Canonical pair key, DIRECT only; UNIQUE in DDL. */
    @Column(name = "direct_key", length = 80, unique = true)
    private String directKey;

    @Column(name = "title", length = 80)
    private String title;

    @Column(name = "avatar_url")
    private String avatarUrl;

    /** LAZY per plan §9.3.7 — listing conversations must not join users. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    /** Denormalized inbox pointers; updated inside the message-send transaction. */
    @Column(name = "last_message_id")
    private UUID lastMessageId;

    @Column(name = "last_message_seq")
    private Long lastMessageSeq;

    /** Updated atomically by the sync allocator, never by a managed entity flush. */
    @Column(name = "change_version", nullable = false, insertable = false, updatable = false)
    private Long changeVersion;

    /** Reconciliation epoch, maintained only by explicit recovery SQL. */
    @Column(name = "sync_generation", nullable = false, insertable = false, updatable = false)
    private Long syncGeneration;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Conversation() {
        // JPA only
    }

    public static Conversation direct(User createdBy, String directKey) {
        Conversation conversation = new Conversation();
        conversation.type = Type.DIRECT;
        conversation.directKey = directKey;
        conversation.createdBy = createdBy;
        return conversation;
    }

    /**
     * GROUP factory: no {@code directKey} — the DDL CHECK requires
     * {@code type='GROUP' <=> direct_key IS NULL}. {@code title} arrives
     * already validated and trimmed by the caller (service layer).
     */
    public static Conversation group(User createdBy, String title) {
        Conversation conversation = new Conversation();
        conversation.type = Type.GROUP;
        conversation.title = title;
        conversation.createdBy = createdBy;
        return conversation;
    }

    @PrePersist
    void onPrePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public Type getType() {
        return type;
    }

    public String getDirectKey() {
        return directKey;
    }

    public String getTitle() {
        return title;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public User getCreatedBy() {
        return createdBy;
    }

    public UUID getLastMessageId() {
        return lastMessageId;
    }

    public Long getLastMessageSeq() {
        return lastMessageSeq;
    }

    public Long getChangeVersion() {
        return changeVersion;
    }

    public Long getSyncGeneration() {
        return syncGeneration;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** Admin-only rename (GROUP) — same mutator convention as {@link User#changeDisplayName}. */
    public void changeTitle(String title) {
        this.title = title;
    }

    /** Admin-only avatar update (GROUP); {@code null} clears the avatar. */
    public void changeAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }
}
