package com.nexachat.user.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;

public record UpdateUserSettingsRequest(Boolean showPresence, Boolean showSeen,
                                        Boolean aiSummaryConsent,
                                        @Size(max = 16) String translateTargetLang) {

    @AssertTrue(message = "At least one setting must be provided")
    public boolean isNonEmptyPatch() {
        return showPresence != null || showSeen != null || aiSummaryConsent != null
                || translateTargetLang != null;
    }

    public UpdateUserSettingsRequest(Boolean showPresence, Boolean showSeen,
                                     Boolean aiSummaryConsent) {
        this(showPresence, showSeen, aiSummaryConsent, null);
    }
}
