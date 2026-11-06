package com.nexachat.core.network.dto;

import androidx.annotation.Nullable;
import java.util.Objects;
import com.squareup.moshi.Json;

/** Reflective Moshi bean. Absent JSON keeps the no-arg defaults. */
public final class ContactEnvelopeDto {
    @Nullable private final ContactDto contact;
    @Json(name = "contactUserId") @Nullable private final String contactUserId;
    @Json(name = "userId") @Nullable private final String userId;
    @Nullable private final String phone;
    @Nullable private final String displayName;
    @Nullable private final String aliasName;
    @Nullable private final String createdAt;

    public ContactEnvelopeDto() {
        this(null, null, null, null, null, null, null);
    }

    public ContactEnvelopeDto(@Nullable ContactDto contact, @Nullable String contactUserId, @Nullable String userId, @Nullable String phone, @Nullable String displayName, @Nullable String aliasName, @Nullable String createdAt) {
        this.contact = contact;
        this.contactUserId = contactUserId;
        this.userId = userId;
        this.phone = phone;
        this.displayName = displayName;
        this.aliasName = aliasName;
        this.createdAt = createdAt;
    }

    @Nullable public ContactDto getContact() {
        return contact;
    }

    @Nullable public String getContactUserId() {
        return contactUserId;
    }

    @Nullable public String getUserId() {
        return userId;
    }

    @Nullable public String getPhone() {
        return phone;
    }

    @Nullable public String getDisplayName() {
        return displayName;
    }

    @Nullable public String getAliasName() {
        return aliasName;
    }

    @Nullable public String getCreatedAt() {
        return createdAt;
    }


    public ContactDto unwrap() {
        if (contact != null) {
            return contact;
        }
        return new ContactDto(contactUserId, userId, phone, displayName, aliasName, null, createdAt);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ContactEnvelopeDto that)) {
            return false;
        }
        return Objects.equals(contact, that.contact)
                && Objects.equals(contactUserId, that.contactUserId)
                && Objects.equals(userId, that.userId)
                && Objects.equals(phone, that.phone)
                && Objects.equals(displayName, that.displayName)
                && Objects.equals(aliasName, that.aliasName)
                && Objects.equals(createdAt, that.createdAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(contact, contactUserId, userId, phone, displayName, aliasName, createdAt);
    }
}
