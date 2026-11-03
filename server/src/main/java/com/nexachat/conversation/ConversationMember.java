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
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
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

    @Column(name = "joined_at", nullable = false, updatable = false)
    private Instant joinedAt;

    @Column(name = "last_read_seq", nullable = false)
    private long lastReadSeq;

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

    @PrePersist
    void onPrePersist() {
        if (joinedAt == null) {
            joinedAt = Instant.now();
        }
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

    public Instant getJoinedAt() {
        return joinedAt;
    }

    public long getLastReadSeq() {
        return lastReadSeq;
    }
}
