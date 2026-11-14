package com.nexachat.feature.inbox.adapter;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.nexachat.R;
import com.nexachat.call.ui.CallLogResourceResolver;
import com.nexachat.data.ConversationSummary;
import com.nexachat.feature.inbox.CallPreviewDirection;
import com.nexachat.feature.inbox.InboxPreviewKind;

/**
 * Resolves preview text for Android inbox rows.
 */
public final class ConversationPreviewHelper {

    private ConversationPreviewHelper() {
    }

    @NonNull
    public static String previewText(
            @NonNull Context context,
            @NonNull ConversationSummary conversation,
            @Nullable String currentUserId
    ) {
        if (conversation.getLastMessageDeleted()) {
            return context.getString(R.string.message_deleted);
        }
        if (conversation.isLastMessageEncrypted()) {
            return context.getString(R.string.e2ee_message_placeholder);
        }
        String raw = conversation.getLastMessageContent();
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        InboxPreviewKind kind = InboxPreviewKind.fromContent(raw);
        if (kind == InboxPreviewKind.CALL) {
            CallLogResourceResolver.ResolvedCallLog callLog = CallLogResourceResolver.fromSystemMessage(
                    raw,
                    CallPreviewDirection.isOutgoing(raw, currentUserId)
            );
            return withGroupSender(
                    conversation,
                    context.getString(callLog.getResourceId(), callLog.getArguments()));
        }
        String preview;
        if (kind == InboxPreviewKind.PHOTO) {
            preview = context.getString(R.string.inbox_preview_photo);
        } else if (kind == InboxPreviewKind.VOICE) {
            preview = context.getString(R.string.inbox_preview_voice);
        } else if (kind == InboxPreviewKind.FILE) {
            preview = context.getString(R.string.inbox_preview_file);
        } else {
            preview = raw;
        }
        return withGroupSender(conversation, preview);
    }

    @NonNull
    public static InboxPreviewKind previewKind(@NonNull ConversationSummary conversation) {
        if (conversation.getLastMessageDeleted()) {
            return InboxPreviewKind.TEXT;
        }
        return InboxPreviewKind.fromContent(conversation.getLastMessageContent());
    }

    @NonNull
    private static String withGroupSender(
            @NonNull ConversationSummary conversation,
            @NonNull String preview) {
        String sender = conversation.getLastMessageSenderName();
        if (!conversation.isGroup() || sender == null || sender.isBlank() || preview.isBlank()) {
            return preview;
        }
        return sender + ": " + preview;
    }

    @NonNull
    public static String displayTitle(@NonNull Context context, @NonNull ConversationSummary conversation) {
        String name = conversation.getDisplayName();
        if (name == null || name.isBlank()) {
            return context.getString(R.string.chat_title);
        }
        return name;
    }
}
