package com.nexachat.feature.summary.adapter;

import androidx.annotation.NonNull;

/** Label formatting for summary citation chips in Android Views. */
public final class SummaryCitationLabels {

    private SummaryCitationLabels() {
    }

    @NonNull
    public static String messageIdPrefix(@NonNull String messageId) {
        int take = Math.min(4, messageId.length());
        return messageId.substring(0, take);
    }
}
