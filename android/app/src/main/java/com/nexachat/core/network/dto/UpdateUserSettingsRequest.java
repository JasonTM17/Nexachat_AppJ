package com.nexachat.core.network.dto;

import androidx.annotation.Nullable;
import java.util.Objects;

/**
 * {@code PATCH /users/me/settings} body — partial update for presence and seen privacy flags.
 * Bound by Moshi's reflective Java adapter.
 */
public final class UpdateUserSettingsRequest {
    @Nullable private final Boolean showPresence;
    @Nullable private final Boolean showSeen;

    public UpdateUserSettingsRequest(boolean showPresence) {
        this(showPresence, null);
    }

    public UpdateUserSettingsRequest(@Nullable Boolean showPresence, @Nullable Boolean showSeen) {
        this.showPresence = showPresence;
        this.showSeen = showSeen;
    }

    @Nullable
    public Boolean getShowPresence() {
        return showPresence;
    }

    public boolean isShowPresence() {
        return Boolean.TRUE.equals(showPresence);
    }

    @Nullable
    public Boolean getShowSeen() {
        return showSeen;
    }

    public boolean isShowSeen() {
        return Boolean.TRUE.equals(showSeen);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof UpdateUserSettingsRequest request)) {
            return false;
        }
        return Objects.equals(showPresence, request.showPresence)
                && Objects.equals(showSeen, request.showSeen);
    }

    @Override
    public int hashCode() {
        return Objects.hash(showPresence, showSeen);
    }
}
