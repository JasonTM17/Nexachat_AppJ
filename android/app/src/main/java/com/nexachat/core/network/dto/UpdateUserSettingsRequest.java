package com.nexachat.core.network.dto;

import java.util.Objects;

/**
 * {@code PATCH /users/me/settings} body — a partial update, so only the flag.
 * Bound by Moshi's reflective Java adapter.
 */
public final class UpdateUserSettingsRequest {
    private final boolean showPresence;

    public UpdateUserSettingsRequest(boolean showPresence) {
        this.showPresence = showPresence;
    }

    public boolean isShowPresence() {
        return showPresence;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof UpdateUserSettingsRequest request)) {
            return false;
        }
        return showPresence == request.showPresence;
    }

    @Override
    public int hashCode() {
        return Objects.hash(showPresence);
    }
}
