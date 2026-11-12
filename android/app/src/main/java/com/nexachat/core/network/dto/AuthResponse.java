package com.nexachat.core.network.dto;

import androidx.annotation.Nullable;
import androidx.annotation.NonNull;
import com.nexachat.core.storage.RecallWindowConfig;
import java.util.Objects;

/** Reflective Moshi bean. Absent JSON keeps the no-arg defaults. */
public final class AuthResponse {
    private final UserDto user;
    private final String accessToken;
    private final long accessTokenExpiresIn;
    private final String refreshToken;
    private final long recallWindowSeconds;
    private final boolean groupAdminMayRecallAfterWindow;
    private final boolean newAccount;

    public AuthResponse() {
        this(null, null, 0L, null,
                RecallWindowConfig.DEFAULT_RECALL_WINDOW_SECONDS, false, false);
    }

    public AuthResponse(@NonNull UserDto user, @NonNull String accessToken, long accessTokenExpiresIn, @NonNull String refreshToken) {
        this(user, accessToken, accessTokenExpiresIn, refreshToken,
                RecallWindowConfig.DEFAULT_RECALL_WINDOW_SECONDS, false, false);
    }

    public AuthResponse(@NonNull UserDto user, @NonNull String accessToken,
            long accessTokenExpiresIn, @NonNull String refreshToken,
            long recallWindowSeconds, boolean groupAdminMayRecallAfterWindow) {
        this(user, accessToken, accessTokenExpiresIn, refreshToken,
                recallWindowSeconds, groupAdminMayRecallAfterWindow, false);
    }

    public AuthResponse(@NonNull UserDto user, @NonNull String accessToken,
            long accessTokenExpiresIn, @NonNull String refreshToken,
            long recallWindowSeconds, boolean groupAdminMayRecallAfterWindow,
            boolean newAccount) {
        this.user = user;
        this.accessToken = accessToken;
        this.accessTokenExpiresIn = accessTokenExpiresIn;
        this.refreshToken = refreshToken;
        this.recallWindowSeconds = RecallWindowConfig.isValidWindow(recallWindowSeconds)
                ? recallWindowSeconds : RecallWindowConfig.DEFAULT_RECALL_WINDOW_SECONDS;
        this.groupAdminMayRecallAfterWindow = groupAdminMayRecallAfterWindow;
        this.newAccount = newAccount;
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

    public long getRecallWindowSeconds() {
        return recallWindowSeconds;
    }

    public boolean getGroupAdminMayRecallAfterWindow() {
        return groupAdminMayRecallAfterWindow;
    }

    public boolean isNewAccount() {
        return newAccount;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof AuthResponse that)) {
            return false;
        }
        return Objects.equals(user, that.user)
                && Objects.equals(accessToken, that.accessToken)
                && accessTokenExpiresIn == that.accessTokenExpiresIn
                && Objects.equals(refreshToken, that.refreshToken)
                && recallWindowSeconds == that.recallWindowSeconds
                && groupAdminMayRecallAfterWindow == that.groupAdminMayRecallAfterWindow
                && newAccount == that.newAccount;
    }

    @Override
    public int hashCode() {
        return Objects.hash(user, accessToken, accessTokenExpiresIn, refreshToken,
                recallWindowSeconds, groupAdminMayRecallAfterWindow, newAccount);
    }
}
