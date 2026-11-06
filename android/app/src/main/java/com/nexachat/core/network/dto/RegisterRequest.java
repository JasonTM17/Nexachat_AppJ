package com.nexachat.core.network.dto;

import androidx.annotation.Nullable;
import androidx.annotation.NonNull;
import java.util.Objects;

/** Reflective Moshi bean. Absent JSON keeps the no-arg defaults. */
public final class RegisterRequest {
    private final String phone;
    private final String password;
    private final String displayName;

    public RegisterRequest() {
        this(null, null, null);
    }

    public RegisterRequest(@NonNull String phone, @NonNull String password, @NonNull String displayName) {
        this.phone = phone;
        this.password = password;
        this.displayName = displayName;
    }

    @NonNull public String getPhone() {
        return phone;
    }

    @NonNull public String getPassword() {
        return password;
    }

    @NonNull public String getDisplayName() {
        return displayName;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof RegisterRequest that)) {
            return false;
        }
        return Objects.equals(phone, that.phone)
                && Objects.equals(password, that.password)
                && Objects.equals(displayName, that.displayName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(phone, password, displayName);
    }
}
