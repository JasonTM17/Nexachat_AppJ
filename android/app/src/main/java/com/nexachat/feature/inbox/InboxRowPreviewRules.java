package com.nexachat.feature.inbox;

import androidx.annotation.Nullable;

/** Inbox conversation row preview visibility rules. */
public final class InboxRowPreviewRules {

    private InboxRowPreviewRules() {}

    public static boolean hasPreviewLine(@Nullable String previewText) {
        return previewText != null && !previewText.isBlank();
    }
}
