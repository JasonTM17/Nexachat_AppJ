package com.nexachat.feature.summary.adapter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Label formatting for summary citation chips in Android Views. */
public final class SummaryCitationLabels {

    private SummaryCitationLabels() {
    }

    @NonNull
    public static String chipLabel(@Nullable Long seq, @NonNull String messageId) {
        if (seq != null) {
            return "#" + seq;
        }
        int take = Math.min(4, messageId.length());
        return messageId.substring(0, take);
    }
}
