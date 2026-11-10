package com.nexachat.feature.chat.adapter;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;
import com.nexachat.data.ChatMessage;
import com.nexachat.data.ReactionChip;
import java.util.List;
import java.util.Objects;

/**
 * DiffUtil callback for {@link MessageListItem} rows.
 */
public final class MessageListDiffCallback extends DiffUtil.ItemCallback<MessageListItem> {

    @Override
    public boolean areItemsTheSame(@Nullable MessageListItem oldItem, @Nullable MessageListItem newItem) {
        if (oldItem == null || newItem == null) {
            return false;
        }
        return oldItem.getKind() == newItem.getKind()
                && oldItem.getStableId().equals(newItem.getStableId());
    }

    @Override
    public boolean areContentsTheSame(@Nullable MessageListItem oldItem, @Nullable MessageListItem newItem) {
        if (oldItem == null || newItem == null) {
            return false;
        }
        if (oldItem.getKind() != newItem.getKind()) {
            return false;
        }
        if (MessageListItem.Kind.DATE_SEPARATOR.equals(oldItem.getKind())) {
            return Objects.equals(oldItem.getDateSeparatorLabel(), newItem.getDateSeparatorLabel());
        }
        if (MessageListItem.Kind.UNREAD_DIVIDER.equals(oldItem.getKind())) {
            return oldItem.getUnreadDividerCount() == newItem.getUnreadDividerCount();
        }
        ChatMessage left = oldItem.getMessage();
        ChatMessage right = newItem.getMessage();
        if (left == null || right == null) {
            return false;
        }
        if (!contentEquals(left, right)) {
            return false;
        }
        return Objects.equals(oldItem.getGroupPosition(), newItem.getGroupPosition())
                && oldItem.shouldShowSenderName() == newItem.shouldShowSenderName()
                && oldItem.isSeenReceipt() == newItem.isSeenReceipt()
                && oldItem.isPulsing() == newItem.isPulsing();
    }

    private static boolean contentEquals(ChatMessage left, ChatMessage right) {
        return left.getId().equals(right.getId())
                && Objects.equals(left.getContent(), right.getContent())
                && Objects.equals(left.getType(), right.getType())
                && Objects.equals(left.getStatus(), right.getStatus())
                && left.getMine() == right.getMine()
                && left.getCreatedAtEpochMs() == right.getCreatedAtEpochMs()
                && Objects.equals(left.getEditedAtEpochMs(), right.getEditedAtEpochMs())
                && Objects.equals(left.getDeletedAtEpochMs(), right.getDeletedAtEpochMs())
                && Objects.equals(left.getReplyToId(), right.getReplyToId())
                && Objects.equals(left.getReplyToSnippet(), right.getReplyToSnippet())
                && Objects.equals(left.getReplyToSenderName(), right.getReplyToSenderName())
                && Objects.equals(left.getSenderDisplayName(), right.getSenderDisplayName())
                && reactionsEqual(left.getReactions(), right.getReactions())
                && attachmentsSizeEqual(left, right);
    }

    private static boolean attachmentsSizeEqual(ChatMessage left, ChatMessage right) {
        return left.getAttachments().size() == right.getAttachments().size();
    }

    private static boolean reactionsEqual(
            @Nullable List<ReactionChip> left,
            @Nullable List<ReactionChip> right
    ) {
        if (left == null && right == null) {
            return true;
        }
        if (left == null || right == null || left.size() != right.size()) {
            return false;
        }
        for (int i = 0; i < left.size(); i++) {
            ReactionChip a = left.get(i);
            ReactionChip b = right.get(i);
            if (!Objects.equals(a.getEmoji(), b.getEmoji())
                    || a.getCount() != b.getCount()
                    || a.getReactedByMe() != b.getReactedByMe()) {
                return false;
            }
        }
        return true;
    }
}
