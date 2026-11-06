package com.nexachat.core.network.dto;

import androidx.annotation.Nullable;
import androidx.annotation.NonNull;
import java.util.Objects;

/** Reflective Moshi bean. Absent JSON keeps the no-arg defaults. */
public final class LoginRequest {
    private final String phone;
    private final String password;

    public LoginRequest() {
        this(null, null);
    }

    public LoginRequest(@NonNull String phone, @NonNull String password) {
        this.phone = phone;
        this.password = password;
    }

    @NonNull public String getPhone() {
        return phone;
    }

    @NonNull public String getPassword() {
        return password;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof LoginRequest that)) {
            return false;
        }
        return Objects.equals(phone, that.phone)
                && Objects.equals(password, that.password);
    }

    @Override
    public int hashCode() {
        return Objects.hash(phone, password);
    }
}
