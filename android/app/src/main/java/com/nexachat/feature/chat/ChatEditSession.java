package com.nexachat.feature.chat;

import androidx.annotation.Nullable;
import java.util.Objects;

/** One in-progress edit. The ordinary composer draft is stored separately. */
public final class ChatEditSession {
    static final String KEY_MESSAGE_ID = "chat_edit_message_id";
    static final String KEY_ORIGINAL = "chat_edit_original";
    static final String KEY_DRAFT = "chat_edit_draft";
    static final String KEY_MENTIONS = "chat_edit_mentions";
    static final String KEY_MENTION_ALL_OFFSET = "chat_edit_mention_all_offset";
    static final String KEY_MENTION_ALL_LENGTH = "chat_edit_mention_all_length";

    private final String messageId;
    private final String originalContent;
    private final MentionDraft draft;

    public ChatEditSession(String messageId, @Nullable String originalContent, @Nullable MentionDraft draft) {
        this.messageId = Objects.requireNonNull(messageId, "messageId");
        this.originalContent = originalContent == null ? "" : originalContent;
        this.draft = draft == null ? MentionDraft.empty("") : draft;
    }

    public String getMessageId() {
        return messageId;
    }

    public String getOriginalContent() {
        return originalContent;
    }

    public MentionDraft getDraft() {
        return draft;
    }

    public ChatEditSession withDraft(MentionDraft next) {
        return new ChatEditSession(messageId, originalContent, next);
    }
}
