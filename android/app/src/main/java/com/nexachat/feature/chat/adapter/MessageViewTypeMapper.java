package com.nexachat.feature.chat.adapter;

import androidx.annotation.Nullable;
import com.nexachat.R;
import com.nexachat.core.network.dto.MessageDto;
import com.nexachat.data.ChatMessage;
import java.util.List;

/**
 * Maps a {@link ChatMessage} plus direction to a message row layout resource id.
 */
public final class MessageViewTypeMapper {

    public static final int VIEW_TYPE_DATE_SEPARATOR = 0;

    private MessageViewTypeMapper() {
    }

    public static boolean isSystemMessage(@Nullable ChatMessage message) {
        if (message == null) {
            return false;
        }
        String content = message.getContent();
        return content != null && content.startsWith("CALL|");
    }

    public static MessageContentKind resolveContentKind(@Nullable ChatMessage message) {
        if (message == null) {
            return MessageContentKind.TEXT;
        }
        if (isSystemMessage(message)) {
            return MessageContentKind.SYSTEM;
        }
        String type = message.getType() != null ? message.getType() : MessageDto.TYPE_TEXT;
        if (MessageDto.TYPE_IMAGE.equalsIgnoreCase(type)) {
            return MessageContentKind.IMAGE;
        }
        if (MessageDto.TYPE_VIDEO.equalsIgnoreCase(type)) {
            return MessageContentKind.VIDEO;
        }
        if (MessageDto.TYPE_AUDIO.equalsIgnoreCase(type)) {
            return MessageContentKind.AUDIO;
        }
        if (MessageDto.TYPE_FILE.equalsIgnoreCase(type)) {
            return MessageContentKind.FILE;
        }
        if (!message.getAttachments().isEmpty()) {
            String mime = message.getAttachments().get(0).getMimeType();
            if (mime != null) {
                if (mime.startsWith("image/")) {
                    return MessageContentKind.IMAGE;
                }
                if (mime.startsWith("video/")) {
                    return MessageContentKind.VIDEO;
                }
                if (mime.startsWith("audio/")) {
                    return MessageContentKind.AUDIO;
                }
            }
            return MessageContentKind.FILE;
        }
        return MessageContentKind.TEXT;
    }

    public static int layoutResForMessage(@Nullable ChatMessage message) {
        boolean outgoing = message != null && message.getMine();
        return layoutResFor(resolveContentKind(message), outgoing);
    }

    public static int layoutResFor(MessageContentKind kind, boolean outgoing) {
        switch (kind) {
            case SYSTEM:
                return R.layout.item_message_system;
            case IMAGE:
                return outgoing ? R.layout.item_message_image_outgoing : R.layout.item_message_image_incoming;
            case VIDEO:
                return outgoing ? R.layout.item_message_video_outgoing : R.layout.item_message_video_incoming;
            case AUDIO:
                return outgoing ? R.layout.item_message_audio_outgoing : R.layout.item_message_audio_incoming;
            case FILE:
                return outgoing ? R.layout.item_message_file_outgoing : R.layout.item_message_file_incoming;
            case TEXT:
            default:
                return outgoing ? R.layout.item_message_text_outgoing : R.layout.item_message_text_incoming;
        }
    }

    public static int viewTypeForMessage(@Nullable ChatMessage message) {
        boolean outgoing = message != null && message.getMine();
        return viewTypeFor(resolveContentKind(message), outgoing);
    }

    public static int viewTypeFor(MessageContentKind kind, boolean outgoing) {
        int base;
        switch (kind) {
            case SYSTEM:
                base = 10;
                break;
            case IMAGE:
                base = 20;
                break;
            case VIDEO:
                base = 30;
                break;
            case AUDIO:
                base = 40;
                break;
            case FILE:
                base = 50;
                break;
            case TEXT:
            default:
                base = 60;
                break;
        }
        return base + (outgoing ? 1 : 0);
    }

    public static int distinctMessageViewTypeCount() {
        return 12;
    }

    public static int layoutResForViewType(int viewType) {
        if (viewType == VIEW_TYPE_DATE_SEPARATOR) {
            return R.layout.item_date_separator;
        }
        boolean outgoing = (viewType & 1) == 1;
        int bucket = viewType - (outgoing ? 1 : 0);
        MessageContentKind kind;
        switch (bucket) {
            case 10:
                kind = MessageContentKind.SYSTEM;
                break;
            case 20:
                kind = MessageContentKind.IMAGE;
                break;
            case 30:
                kind = MessageContentKind.VIDEO;
                break;
            case 40:
                kind = MessageContentKind.AUDIO;
                break;
            case 50:
                kind = MessageContentKind.FILE;
                break;
            default:
                kind = MessageContentKind.TEXT;
                break;
        }
        return layoutResFor(kind, outgoing);
    }

    public static boolean sameVisualBucket(@Nullable ChatMessage left, @Nullable ChatMessage right) {
        if (left == null || right == null) {
            return false;
        }
        boolean leftMine = left.getMine();
        boolean rightMine = right.getMine();
        if (leftMine != rightMine) {
            return false;
        }
        return viewTypeForMessage(left) == viewTypeForMessage(right);
    }

    public static int indexOfMessageId(@Nullable List<ChatMessage> messages, @Nullable String messageId) {
        if (messages == null || messageId == null) {
            return -1;
        }
        for (int i = 0; i < messages.size(); i++) {
            if (messageId.equals(messages.get(i).getId())) {
                return i;
            }
        }
        return -1;
    }
}
