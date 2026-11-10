package com.nexachat.core.network.dto;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.Objects;

/** {@code POST /api/v1/users/me/email/verify} success body. */
public final class VerifyRecoveryEmailResponse {
    @Nullable private final String email;
    @Nullable private final Boolean verified;

    public VerifyRecoveryEmailResponse() {
        this(null, null);
    }

    public VerifyRecoveryEmailResponse(@Nullable String email, @Nullable Boolean verified) {
        this.email = email;
        this.verified = verified;
    }

    @Nullable
    public String getEmail() {
        return email;
    }

    @Nullable
    public Boolean getVerified() {
        return verified;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof VerifyRecoveryEmailResponse that)) {
            return false;
        }
        return Objects.equals(email, that.email) && Objects.equals(verified, that.verified);
    }

    @Override
    public int hashCode() {
        return Objects.hash(email, verified);
    }
}
