package com.nexachat.core.network.dto;

import androidx.annotation.Nullable;
import androidx.annotation.NonNull;
import java.util.Objects;

/** Reflective Moshi bean. Absent JSON keeps the no-arg defaults. */
public final class AddContactRequest {
    private final String phone;

    public AddContactRequest() {
        this(null);
    }

    public AddContactRequest(@NonNull String phone) {
        this.phone = phone;
    }

    @NonNull public String getPhone() {
        return phone;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof AddContactRequest that)) {
            return false;
        }
        return Objects.equals(phone, that.phone);
    }

    @Override
    public int hashCode() {
        return Objects.hash(phone);
    }
}
