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

    public UserSettingsDto() {
        this(null);
    }

    public UserSettingsDto(@Nullable Boolean showPresence) {
        this.showPresence = showPresence;
    }

    @Nullable
    public Boolean getShowPresence() {
        return showPresence;
    }
}
