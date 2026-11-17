package com.nexachat.core.network.dto;

import androidx.annotation.NonNull;
import java.util.Objects;

/** {@code POST /api/v1/auth/register/email-code}. */
public final class EmailCodeRequest {
    private final String email;

    public EmailCodeRequest() {
        this(null);
    }

    public EmailCodeRequest(@NonNull String email) {
        this.email = email;
    }

    @NonNull public String getEmail() {
        return email;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof EmailCodeRequest that && Objects.equals(email, that.email);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(email);
    }
}
