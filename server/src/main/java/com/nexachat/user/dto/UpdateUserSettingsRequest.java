package com.nexachat.user.dto;

import jakarta.validation.constraints.AssertTrue;

public record UpdateUserSettingsRequest(Boolean showPresence, Boolean showSeen,
                                        Boolean aiSummaryConsent) {

    @AssertTrue(message = "At least one setting must be provided")
    public boolean isNonEmptyPatch() {
        return showPresence != null || showSeen != null || aiSummaryConsent != null;
    }
}
