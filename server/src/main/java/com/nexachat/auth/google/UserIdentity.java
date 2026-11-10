package com.nexachat.auth.google;

import com.nexachat.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One external identity linked to a NexaChat account (V27).
 *
 * <p>{@code subject} is the provider's immutable user identifier — for Google
 * the OpenID Connect {@code sub} claim. Email is stored for display only and
 * is never used to match accounts.
 */
@Entity
@Table(name = "user_identities")
public class UserIdentity {

    public static final String PROVIDER_GOOGLE = "google";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Column(name = "provider", nullable = false, length = 32, updatable = false)
    private String provider;

    @Column(name = "subject", nullable = false, length = 255, updatable = false)
    private String subject;

    @Column(name = "email_at_link", length = 320)
    private String emailAtLink;

    @Column(name = "linked_at", nullable = false, updatable = false)
    private Instant linkedAt;

    protected UserIdentity() {
        // JPA only
    }

    public static UserIdentity link(User user, String provider, String subject, String emailAtLink) {
        UserIdentity identity = new UserIdentity();
        identity.user = user;
        identity.provider = provider;
        identity.subject = subject;
        identity.emailAtLink = emailAtLink;
        identity.linkedAt = Instant.now();
        return identity;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return user.getId();
    }

    public String getProvider() {
        return provider;
    }

    public String getSubject() {
        return subject;
    }

    public String getEmailAtLink() {
        return emailAtLink;
    }

    public Instant getLinkedAt() {
        return linkedAt;
    }
}
