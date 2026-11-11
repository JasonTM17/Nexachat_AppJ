package com.nexachat.core.network.dto;

import androidx.annotation.NonNull;
import java.util.Objects;

/** {@code POST /api/v1/auth/password-reset/request}. */
public final class PasswordResetRequest {
    private final String phone;

    public PasswordResetRequest() {
        this(null);
    }

    public PasswordResetRequest(@NonNull String phone) {
        this.phone = phone;
    }

    @NonNull
    public String getPhone() {
        return phone;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof PasswordResetRequest that)) {
            return false;
        }
        return Objects.equals(phone, that.phone);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(phone);
    }
}
