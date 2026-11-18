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
    @Nullable private final String translateTargetLang;
    @Nullable private final Boolean streakReminders;

    public UpdateUserSettingsRequest(boolean showPresence) {
        this(showPresence, null, null);
    }

    public UpdateUserSettingsRequest(@Nullable Boolean showPresence, @Nullable Boolean showSeen) {
        this(showPresence, showSeen, null);
    }

    public UpdateUserSettingsRequest(@Nullable Boolean showPresence, @Nullable Boolean showSeen,
            @Nullable String translateTargetLang) {
        this(showPresence, showSeen, translateTargetLang, null);
    }

    public UpdateUserSettingsRequest(@Nullable Boolean showPresence, @Nullable Boolean showSeen,
            @Nullable String translateTargetLang, @Nullable Boolean streakReminders) {
        this.showPresence = showPresence;
        this.showSeen = showSeen;
        this.translateTargetLang = translateTargetLang;
        this.streakReminders = streakReminders;
    }

    /** A partial update that changes only the streak-reminder preference. */
    public static UpdateUserSettingsRequest streakReminders(boolean enabled) {
        return new UpdateUserSettingsRequest(null, null, null, enabled);
    }

    @Nullable
    public Boolean getStreakReminders() {
        return streakReminders;
    }

    @Nullable
    public String getTranslateTargetLang() {
        return translateTargetLang;
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
                && Objects.equals(showSeen, request.showSeen)
                && Objects.equals(translateTargetLang, request.translateTargetLang)
                && Objects.equals(streakReminders, request.streakReminders);
    }

    @Override
    public int hashCode() {
        return Objects.hash(showPresence, showSeen, translateTargetLang, streakReminders);
    }
}
