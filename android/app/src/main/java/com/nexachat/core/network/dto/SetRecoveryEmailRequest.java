package com.nexachat.core.network.dto;

import androidx.annotation.NonNull;
import java.util.Objects;

/** {@code PUT /api/v1/users/me/email} body. */
public final class SetRecoveryEmailRequest {
    @NonNull private final String email;

    public SetRecoveryEmailRequest(@NonNull String email) {
        this.email = Objects.requireNonNull(email, "email");
    }

    @NonNull
    public String getEmail() {
        return email;
    }
}
