package com.nexachat.contact;

import com.nexachat.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * Maps {@code contacts} (V1__init.sql): a directed address-book edge
 * {@code owner -> contactUser}. The PK {@code (owner_id, contact_user_id)}
 * makes "add contact" naturally idempotent-guarded — a duplicate insert hits
 * the PK, which the service maps to {@code ALREADY_CONTACT} (same pattern as
 * {@code AuthService.register} with the phone UNIQUE).
 *
 * <p>Both sides are {@code @ManyToOne LAZY} so listing contacts never drags
 * the owner row along; the list query joins {@code contactUser} explicitly
 * (plan §9.3.7: no eager associations, projection for the read model). The DB
 * {@code CHECK (owner_id <> contact_user_id)} is the backstop for self-adds —
 * the service rejects them with {@code VALIDATION_FAILED} first so callers get
 * a precise 400 instead of a mapped constraint error.
 *
 * <p>{@link Persistable} matters here: the composite id is always populated
 * upfront, so Spring Data's default newness check would call {@code merge()}
 * instead of {@code persist()} — silently UPSERTING instead of surfacing the
 * PK violation that {@code ALREADY_CONTACT} depends on (and skipping
 * {@code @PrePersist} on the caller's instance). {@code isNew} forces an
 * INSERT for freshly built contacts.
 */
@Entity
@Table(name = "contacts")
@IdClass(ContactId.class)
public class Contact implements Persistable<ContactId> {

    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "contact_user_id", nullable = false)
    private User contactUser;

    /** Owner-chosen display override; {@code null} = show the user's displayName. */
    @Column(name = "alias_name", length = 60)
    private String aliasName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** {@code true} until the row exists in the DB — drives persist-vs-merge. */
    @Transient
    private boolean isNew = true;

    protected Contact() {
        // JPA only
    }

    public static Contact add(User owner, User contactUser) {
        Contact contact = new Contact();
        contact.owner = owner;
        contact.contactUser = contactUser;
        return contact;
    }

    @PrePersist
    void onPrePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        isNew = false;
    }

    @Override
    public ContactId getId() {
        return new ContactId(owner.getId(), contactUser.getId());
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public User getOwner() {
        return owner;
    }

    public User getContactUser() {
        return contactUser;
    }

    public String getAliasName() {
        return aliasName;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** {@code null} clears the alias back to the user's own display name. */
    public void changeAlias(String aliasName) {
        this.aliasName = aliasName;
    }
}
