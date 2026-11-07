package com.nexachat.feature.summary.adapter;

import androidx.annotation.NonNull;
import java.util.Objects;

/** Row model for summary citation chips in {@link SummaryCitationAdapter}. */
public final class SummaryCitationItem {

    @NonNull
    private final String messageId;
    @NonNull
    private final String chipLabel;
    private final boolean jumpable;

    public SummaryCitationItem(
            @NonNull String messageId,
            @NonNull String chipLabel,
            boolean jumpable
    ) {
        this.messageId = messageId;
        this.chipLabel = chipLabel;
        this.jumpable = jumpable;
    }

    @NonNull
    public String getMessageId() {
        return messageId;
    }

    @NonNull
    public String getChipLabel() {
        return chipLabel;
    }

    public boolean isJumpable() {
        return jumpable;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof SummaryCitationItem item)) {
            return false;
        }
        return messageId.equals(item.messageId)
                && chipLabel.equals(item.chipLabel)
                && jumpable == item.jumpable;
    }

    @Override
    public int hashCode() {
        return Objects.hash(messageId, chipLabel, jumpable);
    }
}
