package com.nexachat.feature.chat.adapter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.nexachat.core.ui.components.MessageGroupPosition;
import com.nexachat.data.ChatMessage;
import java.util.Objects;

/**
 * RecyclerView row model for {@link MessageListAdapter} (message bubble or date pill).
 */
public final class MessageListItem {

    public enum Kind {
        DATE_SEPARATOR,
        MESSAGE
    }

    @NonNull
    private final Kind kind;
    @NonNull
    private final String stableId;
    @Nullable
    private final String dateSeparatorLabel;
    @Nullable
    private final ChatMessage message;
    @Nullable
    private final MessageGroupPosition groupPosition;
    private final boolean showSenderName;
    private final boolean seenReceipt;
    private final boolean deliveredReceipt;
    private final boolean pulsing;

    private MessageListItem(
            @NonNull Kind kind,
            @NonNull String stableId,
            @Nullable String dateSeparatorLabel,
            @Nullable ChatMessage message,
            @Nullable MessageGroupPosition groupPosition,
            boolean showSenderName,
            boolean seenReceipt,
            boolean deliveredReceipt,
            boolean pulsing
    ) {
        this.kind = kind;
        this.stableId = stableId;
        this.dateSeparatorLabel = dateSeparatorLabel;
        this.message = message;
        this.groupPosition = groupPosition;
        this.showSenderName = showSenderName;
        this.seenReceipt = seenReceipt;
        this.deliveredReceipt = deliveredReceipt;
        this.pulsing = pulsing;
    }

    public static MessageListItem dateSeparator(@NonNull String label, long epochMs) {
        String id = "date-" + epochMs;
        return new MessageListItem(
                Kind.DATE_SEPARATOR,
                id,
                label,
                null,
                null,
                false,
                false,
                false,
                false
        );
    }

    public static MessageListItem message(
            @NonNull ChatMessage message,
            @NonNull MessageGroupPosition groupPosition,
            boolean showSenderName,
            boolean seenReceipt,
            boolean deliveredReceipt,
            boolean pulsing
    ) {
        return new MessageListItem(
                Kind.MESSAGE,
                message.getId(),
                null,
                message,
                groupPosition,
                showSenderName,
                seenReceipt,
                deliveredReceipt,
                pulsing
        );
    }

    public static MessageListItem message(
            @NonNull ChatMessage message,
            @NonNull MessageGroupPosition groupPosition,
            boolean showSenderName,
            boolean seenReceipt,
            boolean pulsing
    ) {
        return message(message, groupPosition, showSenderName, seenReceipt, false, pulsing);
    }

    @NonNull
    public Kind getKind() {
        return kind;
    }

    @NonNull
    public String getStableId() {
        return stableId;
    }

    @Nullable
    public String getDateSeparatorLabel() {
        return dateSeparatorLabel;
    }

    @Nullable
    public ChatMessage getMessage() {
        return message;
    }

    @Nullable
    public MessageGroupPosition getGroupPosition() {
        return groupPosition;
    }

    public boolean shouldShowSenderName() {
        return showSenderName;
    }

    public boolean isSeenReceipt() {
        return seenReceipt;
    }

    public boolean isDeliveredReceipt() {
        return deliveredReceipt;
    }

    public boolean isPulsing() {
        return pulsing;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof MessageListItem item)) {
            return false;
        }
        return kind == item.kind
                && stableId.equals(item.stableId)
                && Objects.equals(dateSeparatorLabel, item.dateSeparatorLabel)
                && Objects.equals(message, item.message)
                && groupPosition == item.groupPosition
                && showSenderName == item.showSenderName
                && seenReceipt == item.seenReceipt
                && deliveredReceipt == item.deliveredReceipt
                && pulsing == item.pulsing;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                kind,
                stableId,
                dateSeparatorLabel,
                message,
                groupPosition,
                showSenderName,
                seenReceipt,
                deliveredReceipt,
                pulsing
        );
    }
}
