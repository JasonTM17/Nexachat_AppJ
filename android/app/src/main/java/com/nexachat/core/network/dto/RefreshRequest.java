package com.nexachat.core.network.dto;

import androidx.annotation.Nullable;
import androidx.annotation.NonNull;
import java.util.Objects;

/** Reflective Moshi bean. Absent JSON keeps the no-arg defaults. */
public final class RefreshRequest {
    private final String refreshToken;

    public RefreshRequest() {
        this(null);
    }

    public RefreshRequest(@NonNull String refreshToken) {
        this.refreshToken = refreshToken;
    }

    @NonNull public String getRefreshToken() {
        return refreshToken;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof RefreshRequest that)) {
            return false;
        }
        return Objects.equals(refreshToken, that.refreshToken);
    }

    @Override
    public int hashCode() {
        return Objects.hash(refreshToken);
    }
}
