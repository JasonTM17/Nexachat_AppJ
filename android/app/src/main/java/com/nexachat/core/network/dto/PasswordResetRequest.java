package com.nexachat.core.network.dto;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.Objects;

/** {@code POST /api/v1/auth/password-reset/request}: names the account by phone or by verified email. */
public final class PasswordResetRequest {
    private final String phone;
    private final String email;

    public PasswordResetRequest() {
        this(null);
    }

    public PasswordResetRequest(@NonNull String phone) {
        this.phone = phone;
        this.email = null;
    }

    private PasswordResetRequest(@Nullable String phone, @Nullable String email) {
        this.phone = phone;
        this.email = email;
    }

    @NonNull public static PasswordResetRequest byEmail(@NonNull String email) {
        return new PasswordResetRequest(null, email);
    }

    @Nullable public String getPhone() {
        return phone;
    }

    @Nullable public String getEmail() {
        return email;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof PasswordResetRequest that)) {
            return false;
        }
        return Objects.equals(phone, that.phone) && Objects.equals(email, that.email);
    }

    @Override
    public int hashCode() {
        return Objects.hash(phone, email);
    }
}
