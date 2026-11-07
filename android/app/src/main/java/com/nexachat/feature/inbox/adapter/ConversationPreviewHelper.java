package com.nexachat.feature.inbox.adapter;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.nexachat.R;
import com.nexachat.call.ui.CallLogResourceResolver;
import com.nexachat.data.ConversationSummary;
import com.nexachat.feature.inbox.CallPreviewDirection;

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
        String raw = conversation.getLastMessageContent();
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        if (raw.startsWith("CALL|")) {
            CallLogResourceResolver.ResolvedCallLog callLog = CallLogResourceResolver.fromSystemMessage(
                    raw,
                    CallPreviewDirection.isOutgoing(raw, currentUserId)
            );
            return context.getString(callLog.getResourceId(), callLog.getArguments());
        }
        return raw;
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
