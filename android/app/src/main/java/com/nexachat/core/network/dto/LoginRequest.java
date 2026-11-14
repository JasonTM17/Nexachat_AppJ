package com.nexachat.core.network.dto;

import androidx.annotation.Nullable;
import androidx.annotation.NonNull;
import java.util.Objects;

/** Reflective Moshi bean. Absent JSON keeps the no-arg defaults. */
public final class LoginRequest {
    private final String phone;
    private final String identifier;
    private final String password;

    public LoginRequest() {
        this(null, null);
    }

    public LoginRequest(@NonNull String phone, @NonNull String password) {
        this.phone = phone;
        this.identifier = null;
        this.password = password;
    }

    private LoginRequest(@Nullable String phone, @Nullable String identifier, @NonNull String password) {
        this.phone = phone;
        this.identifier = identifier;
        this.password = password;
    }

    /** Email sign-in: the server resolves {@code identifier} to a verified email. */
    @NonNull public static LoginRequest byEmail(@NonNull String email, @NonNull String password) {
        return new LoginRequest(null, email, password);
    }

    @NonNull public String getPhone() {
        return phone;
    }

    @Nullable public String getIdentifier() {
        return identifier;
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
                && Objects.equals(identifier, that.identifier)
                && Objects.equals(password, that.password);
    }

    @Override
    public int hashCode() {
        return Objects.hash(phone, identifier, password);
    }
}
