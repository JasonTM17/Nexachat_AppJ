package com.nexachat.core.network.dto;

import androidx.annotation.Nullable;
import androidx.annotation.NonNull;
import java.util.Objects;

/** Reflective Moshi bean. Absent JSON keeps the no-arg defaults. */
public final class AuthResponse {
    private final UserDto user;
    private final String accessToken;
    private final long accessTokenExpiresIn;
    private final String refreshToken;

    public AuthResponse() {
        this(null, null, 0L, null);
    }

    public AuthResponse(@NonNull UserDto user, @NonNull String accessToken, long accessTokenExpiresIn, @NonNull String refreshToken) {
        this.user = user;
        this.accessToken = accessToken;
        this.accessTokenExpiresIn = accessTokenExpiresIn;
        this.refreshToken = refreshToken;
    }

    @NonNull public UserDto getUser() {
        return user;
    }

    @NonNull public String getAccessToken() {
        return accessToken;
    }

    public long getAccessTokenExpiresIn() {
        return accessTokenExpiresIn;
    }

    @NonNull public String getRefreshToken() {
        return refreshToken;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof AuthResponse that)) {
            return false;
        }
        return Objects.equals(user, that.user)
                && Objects.equals(accessToken, that.accessToken)
                && accessTokenExpiresIn == that.accessTokenExpiresIn
                && Objects.equals(refreshToken, that.refreshToken);
    }

    @Override
    public int hashCode() {
        return Objects.hash(user, accessToken, accessTokenExpiresIn, refreshToken);
    }
}
