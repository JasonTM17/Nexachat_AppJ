package com.nexachat.user.dto;

public record UserSettingsResponse(boolean showPresence, boolean showSeen,
                                   boolean aiSummaryConsent, String translateTargetLang,
                                   boolean streakReminders) {

    /** Pre-streak shape for callers that predate the reminder opt-in. */
    public UserSettingsResponse(boolean showPresence, boolean showSeen,
                                boolean aiSummaryConsent, String translateTargetLang) {
        this(showPresence, showSeen, aiSummaryConsent, translateTargetLang, false);
    }
}
