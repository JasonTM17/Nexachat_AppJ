package com.nexachat.user.dto;

import jakarta.validation.constraints.AssertTrue;

public record UpdateUserSettingsRequest(Boolean showPresence, Boolean aiSummaryConsent) {

    @AssertTrue(message = "At least one setting must be provided")
    public boolean isNonEmptyPatch() {
        return showPresence != null || aiSummaryConsent != null;
    }
}
