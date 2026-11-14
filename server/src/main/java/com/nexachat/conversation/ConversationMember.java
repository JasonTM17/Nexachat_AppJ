package com.nexachat.conversation;

import com.nexachat.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;
import org.springframework.data.domain.Persistable;

import java.time.Instant;

/**
 * Maps {@code conversation_members} (V1__init.sql): membership row carrying
 * {@code role} and the read-watermark {@code last_read_seq}.
 *
 * <p>For DIRECT conversations both members are {@code MEMBER} — ADMIN only
 * makes sense for GROUP (a later phase). {@code joined_at} is set on persist;
 * {@code last_read_seq} starts at 0 like the column default.
 *
 * <p>{@link Persistable} like {@code Contact}: the composite id is populated
 * upfront, and {@code isNew} keeps {@code save()} on the persist path instead
 * of merge's phantom SELECT-then-insert.
 */
@Entity
@Table(name = "conversation_members")
@IdClass(ConversationMemberId.class)
public class ConversationMember implements Persistable<ConversationMemberId> {

    /** {@code VARCHAR(6) CHECK (role IN ('ADMIN','MEMBER'))}. */
    public enum Role {
        ADMIN,
        MEMBER
    }

    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false)
    private Conversation conversation;

    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 6)
    private Role role = Role.MEMBER;

    // Sync compares admission with database-timestamped messages and reaction events.
    @Generated(event = EventType.INSERT, sql = "clock_timestamp()")
    @Column(name = "joined_at", nullable = false, updatable = false)
    private Instant joinedAt;

    @Column(name = "last_read_seq", nullable = false)
    private long lastReadSeq;

    @Column(name = "last_delivered_seq", nullable = false)
    private long lastDeliveredSeq;

    /**
     * Per-member mute flag. {@code mutedUntil} is the expiry and is {@code null}
     * for a forever mute; a past expiry is treated as not muted by readers, so
     * no sweep job is needed.
     */
    @Column(name = "muted", nullable = false)
    private boolean muted;

    @Column(name = "muted_until")
    private Instant mutedUntil;

    /** Per-member inbox visibility: archived rows are excluded from the default inbox. */
    @Column(name = "archived", nullable = false)
    private boolean archived;

    /**
     * Per-member inbox priority: pinned conversations sort ahead of the
     * activity order. {@code pinnedAt} is the start of the current pin and is
     * {@code null} exactly while the row is not pinned (V16 check constraint).
     */
    @Column(name = "pinned", nullable = false)
    private boolean pinned;

    @Column(name = "pinned_at")
    private Instant pinnedAt;

    /** {@code true} until the row exists in the DB — drives persist-vs-merge. */
    @Transient
    private boolean isNew = true;

    protected ConversationMember() {
        // JPA only
    }

    public static ConversationMember join(Conversation conversation, User user, Role role) {
        ConversationMember member = new ConversationMember();
        member.conversation = conversation;
        member.user = user;
        member.role = role;
        return member;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        isNew = false;
    }

    @Override
    public ConversationMemberId getId() {
        return new ConversationMemberId(conversation.getId(), user.getId());
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public Conversation getConversation() {
        return conversation;
    }

    public User getUser() {
        return user;
    }

    public Role getRole() {
        return role;
    }

    public void changeRole(Role role) {
        this.role = role;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }

    public long getLastReadSeq() {
        return lastReadSeq;
    }

    public long getLastDeliveredSeq() {
        return lastDeliveredSeq;
    }

    public boolean isMuted() {
        return muted;
    }

    /** Mute expiry; {@code null} means forever. */
    public Instant getMutedUntil() {
        return mutedUntil;
    }

    public boolean isArchived() {
        return archived;
    }

    public boolean isPinned() {
        return pinned;
    }

    /** Start of the current pin; {@code null} when the row is not pinned. */
    public Instant getPinnedAt() {
        return pinnedAt;
    }

    /**
     * Mutes this member's view of the conversation. {@code until} {@code null}
     * means forever; a non-null value is already validated by the service.
     */
    public void mute(Instant until) {
        this.muted = true;
        this.mutedUntil = until;
    }

    /** Clears the mute. Idempotent: clearing an unmuted row is a no-op. */
    public void unmute() {
        this.muted = false;
        this.mutedUntil = null;
    }

    /** Archives this member's view. Idempotent. */
    public void archive() {
        this.archived = true;
    }

    /** Restores an archived row to the default inbox. Idempotent. */
    public void unarchive() {
        this.archived = false;
    }

    /** Pins this member's view; {@code at} is the start of the current pin. */
    public void pin(Instant at) {
        this.pinned = true;
        this.pinnedAt = at;
    }

    /** Clears the pin. Idempotent: clearing a never-pinned row is a no-op. */
    public void unpin() {
        this.pinned = false;
        this.pinnedAt = null;
    }
}
