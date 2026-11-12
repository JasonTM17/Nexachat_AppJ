package com.nexachat.core.network.dto;

import androidx.annotation.Nullable;
import java.util.Objects;

/** Reflective Moshi bean: send exactly one of {@code phone} or {@code email}. */
public final class AddContactRequest {
    @Nullable private final String phone;
    @Nullable private final String email;

    public AddContactRequest() {
        this(null, null);
    }

    private AddContactRequest(@Nullable String phone, @Nullable String email) {
        this.phone = phone;
        this.email = email;
    }

    public static AddContactRequest byPhone(@Nullable String phone) {
        return new AddContactRequest(phone, null);
    }

    public static AddContactRequest byEmail(@Nullable String email) {
        return new AddContactRequest(null, email);
    }

    @Nullable public String getPhone() {
        return phone;
    }

    @Nullable public String getEmail() {
        return email;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof AddContactRequest that)) {
            return false;
        }
        return Objects.equals(phone, that.phone) && Objects.equals(email, that.email);
    }

    @Override
    public int hashCode() {
        return Objects.hash(phone, email);
    }
}
