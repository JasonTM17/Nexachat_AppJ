package com.nexachat.core.network.dto;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.Objects;

/** {@code POST /api/v1/auth/password-reset/confirm}: names the account by phone or by verified email. */
public final class PasswordResetConfirmRequest {
    private final String phone;
    private final String email;
    private final String code;
    private final String newPassword;

    public PasswordResetConfirmRequest() {
        this(null, null, null, null);
    }

    public PasswordResetConfirmRequest(
            @NonNull String phone, @NonNull String code, @NonNull String newPassword) {
        this(phone, null, code, newPassword);
    }

    private PasswordResetConfirmRequest(
            @Nullable String phone,
            @Nullable String email,
            @Nullable String code,
            @Nullable String newPassword) {
        this.phone = phone;
        this.email = email;
        this.code = code;
        this.newPassword = newPassword;
    }

    @NonNull public static PasswordResetConfirmRequest byEmail(
            @NonNull String email, @NonNull String code, @NonNull String newPassword) {
        return new PasswordResetConfirmRequest(null, email, code, newPassword);
    }

    @Nullable public String getPhone() {
        return phone;
    }

    @Nullable public String getEmail() {
        return email;
    }

    @NonNull public String getCode() {
        return code;
    }

    @NonNull public String getNewPassword() {
        return newPassword;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof PasswordResetConfirmRequest that)) {
            return false;
        }
        return Objects.equals(phone, that.phone)
                && Objects.equals(email, that.email)
                && Objects.equals(code, that.code)
                && Objects.equals(newPassword, that.newPassword);
    }

    @Override
    public int hashCode() {
        return Objects.hash(phone, email, code, newPassword);
    }
}
