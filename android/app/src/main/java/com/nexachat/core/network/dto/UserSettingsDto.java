package com.nexachat.core.network.dto;

import androidx.annotation.Nullable;

/**
 * {@code GET}/{@code PATCH /api/v1/users/me/settings}. Only the privacy flag
 * ships in this release; the response is the same shape for both verbs.
 *
 * <p>Moshi binds this Java bean with its reflective adapter. Keep a no-arg
 * constructor and a full constructor.
 */
public final class UserSettingsDto {
    @Nullable private final Boolean showPresence;
    @Nullable private final Boolean showSeen;

    public UserSettingsDto() {
        this(null, null);
    }

    public UserSettingsDto(@Nullable Boolean showPresence) {
        this(showPresence, null);
    }

    public UserSettingsDto(@Nullable Boolean showPresence, @Nullable Boolean showSeen) {
        this.showPresence = showPresence;
        this.showSeen = showSeen;
    }

    @Nullable
    public Boolean getShowPresence() {
        return showPresence;
    }

    @Nullable
    public Boolean getShowSeen() {
        return showSeen;
    }
}
