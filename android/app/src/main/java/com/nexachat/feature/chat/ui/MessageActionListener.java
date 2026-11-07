package com.nexachat.feature.chat.ui;

import androidx.annotation.NonNull;
import com.nexachat.core.network.dto.AttachmentDto;

/**
 * Tap and long-press callbacks from {@link com.nexachat.feature.chat.adapter.MessageListAdapter}.
 */
public interface MessageActionListener {

    void onMessageTap(@NonNull String messageId);

    void onMessageLongPress(@NonNull String messageId);

    void onRetryTap(@NonNull String messageId);

    void onReplyQuoteTap(@NonNull String replyToId);

    void onOpenMedia(@NonNull String messageId, @NonNull AttachmentDto attachment);

    void onOpenFile(@NonNull String messageId, @NonNull AttachmentDto attachment);

    void onReactionTap(@NonNull String messageId, @NonNull String emoji);
}
