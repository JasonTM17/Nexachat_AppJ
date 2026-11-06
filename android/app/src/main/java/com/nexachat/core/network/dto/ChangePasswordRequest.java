package com.nexachat.core.network.dto;

import androidx.annotation.Nullable;
import androidx.annotation.NonNull;
import java.util.Objects;

/** Reflective Moshi bean. Absent JSON keeps the no-arg defaults. */
public final class ChangePasswordRequest {
    private final String oldPassword;
    private final String newPassword;
    @Nullable private final String refreshToken;

    public ChangePasswordRequest() {
        this(null, null, null);
    }

    public ChangePasswordRequest(@NonNull String oldPassword, @NonNull String newPassword, @Nullable String refreshToken) {
        this.oldPassword = oldPassword;
        this.newPassword = newPassword;
        this.refreshToken = refreshToken;
    }

    @NonNull public String getOldPassword() {
        return oldPassword;
    }

    @NonNull public String getNewPassword() {
        return newPassword;
    }

    @Nullable public String getRefreshToken() {
        return refreshToken;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ChangePasswordRequest that)) {
            return false;
        }
        return Objects.equals(oldPassword, that.oldPassword)
                && Objects.equals(newPassword, that.newPassword)
                && Objects.equals(refreshToken, that.refreshToken);
    }

    @Override
    public int hashCode() {
        return Objects.hash(oldPassword, newPassword, refreshToken);
    }
}
