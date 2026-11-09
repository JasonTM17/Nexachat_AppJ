package com.nexachat.invite;

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
import java.util.Objects;
import java.util.UUID;

/**
 * Maps {@code group_invite_bans} (V20__group_invite_links.sql).
 *
 * <p>Tracks users removed by admin from a group so they cannot rejoin via
 * the same invite link unless an admin re-invites them.
 */
@Entity
@Table(name = "group_invite_bans")
public class GroupInviteBan {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false, updatable = false)
    private Conversation conversation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invite_link_id")
    private GroupInviteLink inviteLink;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected GroupInviteBan() {
        // JPA only
    }

    public GroupInviteBan(Conversation conversation, User user, GroupInviteLink inviteLink) {
        this.conversation = Objects.requireNonNull(conversation, "conversation");
        this.user = Objects.requireNonNull(user, "user");
        this.inviteLink = inviteLink;
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public Conversation getConversation() {
        return conversation;
    }

    public User getUser() {
        return user;
    }

    public GroupInviteLink getInviteLink() {
        return inviteLink;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
