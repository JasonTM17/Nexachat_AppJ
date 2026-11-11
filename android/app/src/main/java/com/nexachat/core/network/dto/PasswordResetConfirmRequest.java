package com.nexachat.core.network.dto;

import androidx.annotation.NonNull;
import java.util.Objects;

/** {@code POST /api/v1/auth/password-reset/confirm}. */
public final class PasswordResetConfirmRequest {
    private final String phone;
    private final String code;
    private final String newPassword;

    public PasswordResetConfirmRequest() {
        this(null, null, null);
    }

    public PasswordResetConfirmRequest(
            @NonNull String phone, @NonNull String code, @NonNull String newPassword) {
        this.phone = phone;
        this.code = code;
        this.newPassword = newPassword;
    }

    @NonNull
    public String getPhone() {
        return phone;
    }

    @NonNull
    public String getCode() {
        return code;
    }

    @NonNull
    public String getNewPassword() {
        return newPassword;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof PasswordResetConfirmRequest that)) {
            return false;
        }
        return Objects.equals(phone, that.phone)
                && Objects.equals(code, that.code)
                && Objects.equals(newPassword, that.newPassword);
    }

    @Override
    public int hashCode() {
        return Objects.hash(phone, code, newPassword);
    }
}
