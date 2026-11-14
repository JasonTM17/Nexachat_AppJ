package com.nexachat.feature.summary.adapter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.Objects;

/** Row model for summary citation chips in {@link SummaryCitationAdapter}. */
public final class SummaryCitationItem {

    @NonNull
    private final String messageId;
    @NonNull
    private final String chipLabel;
    @Nullable
    private final String contentDescription;
    private final boolean jumpable;

    public SummaryCitationItem(
            @NonNull String messageId,
            @NonNull String chipLabel,
            boolean jumpable
    ) {
        this(messageId, chipLabel, null, jumpable);
    }

    public SummaryCitationItem(
            @NonNull String messageId,
            @NonNull String chipLabel,
            @Nullable String contentDescription,
            boolean jumpable
    ) {
        this.messageId = messageId;
        this.chipLabel = chipLabel;
        this.contentDescription = contentDescription;
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

    @Nullable
    public String getContentDescription() {
        return contentDescription;
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
                && Objects.equals(contentDescription, item.contentDescription)
                && jumpable == item.jumpable;
    }

    @Override
    public int hashCode() {
        return Objects.hash(messageId, chipLabel, contentDescription, jumpable);
    }
}
