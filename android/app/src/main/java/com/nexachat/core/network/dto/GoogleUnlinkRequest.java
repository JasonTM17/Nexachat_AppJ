package com.nexachat.core.network.dto;

import androidx.annotation.NonNull;
import java.util.Objects;

/** Reflective Moshi bean for {@code DELETE /api/v1/auth/identities/google}. */
public final class GoogleUnlinkRequest {
    private final String password;

    public GoogleUnlinkRequest() {
        this(null);
    }

    public GoogleUnlinkRequest(@NonNull String password) {
        this.password = password;
    }

    @NonNull public String getPassword() {
        return password;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof GoogleUnlinkRequest that
                && Objects.equals(password, that.password);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(password);
    }
}
