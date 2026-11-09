package com.nexachat.invite;

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
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Maps {@code group_join_requests} (V20__group_invite_links.sql).
 *
 * <p>Created when an authenticated user attempts to join a group whose active
 * invite link requires admin approval. Group admins can list, approve, or reject
 * pending requests.
 */
@Entity
@Table(name = "group_join_requests")
public class GroupJoinRequest {

    public enum Status {
        PENDING,
        APPROVED,
        REJECTED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false, updatable = false)
    private Conversation conversation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invite_link_id")
    private GroupInviteLink inviteLink;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by")
    private User reviewedBy;

    protected GroupJoinRequest() {
        // JPA only
    }

    public GroupJoinRequest(Conversation conversation,
                            GroupInviteLink inviteLink,
                            User user) {
        this.conversation = Objects.requireNonNull(conversation, "conversation");
        this.inviteLink = inviteLink;
        this.user = Objects.requireNonNull(user, "user");
        this.status = Status.PENDING;
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public void approve(User reviewer, Instant now) {
        this.status = Status.APPROVED;
        this.reviewedAt = now != null ? now : Instant.now();
        this.reviewedBy = Objects.requireNonNull(reviewer, "reviewer");
    }

    public void reject(User reviewer, Instant now) {
        this.status = Status.REJECTED;
        this.reviewedAt = now != null ? now : Instant.now();
        this.reviewedBy = Objects.requireNonNull(reviewer, "reviewer");
    }

    public UUID getId() {
        return id;
    }

    public Conversation getConversation() {
        return conversation;
    }

    public GroupInviteLink getInviteLink() {
        return inviteLink;
    }

    public User getUser() {
        return user;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public User getReviewedBy() {
        return reviewedBy;
    }
}
