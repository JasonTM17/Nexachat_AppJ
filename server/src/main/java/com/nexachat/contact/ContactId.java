package com.nexachat.contact;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Composite key for {@link Contact}: {@code (owner_id, contact_user_id)}.
 *
 * <p>{@code @IdClass} on relationship ids: the field names must match the
 * entity's id attribute names ({@code owner}, {@code contactUser}) while the
 * field types are the target entities' PK type ({@code UUID}).
 */
public class ContactId implements Serializable {

    private static final long serialVersionUID = 1L;

    private UUID owner;
    private UUID contactUser;

    protected ContactId() {
        // JPA only
    }

    public ContactId(UUID owner, UUID contactUser) {
        this.owner = owner;
        this.contactUser = contactUser;
    }

    public UUID getOwner() {
        return owner;
    }

    public UUID getContactUser() {
        return contactUser;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ContactId other)) {
            return false;
        }
        return Objects.equals(owner, other.owner) && Objects.equals(contactUser, other.contactUser);
    }

    @Override
    public int hashCode() {
        return Objects.hash(owner, contactUser);
    }
}
