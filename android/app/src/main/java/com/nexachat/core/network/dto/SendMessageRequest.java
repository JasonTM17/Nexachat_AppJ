package com.nexachat.core.network.dto;

import androidx.annotation.Nullable;
import androidx.annotation.NonNull;
import java.util.List;
import java.util.Objects;

/** Reflective Moshi bean. Absent JSON keeps the no-arg defaults. */
public final class SendMessageRequest {
    private final String clientMessageId;
    private final String type;
    @Nullable private final String content;
    @Nullable private final String replyToId;
    @Nullable private final List<String> attachmentIds;
    @Nullable private final List<MessageMention> mentions;
    private final boolean mentionAll;
    @Nullable private final String encryption;
    @Nullable private final List<E2eeEnvelopeDto> e2eeEnvelopes;

    public SendMessageRequest() {
        this(null, MessageDto.TYPE_TEXT, null, null, List.of(), List.of(), false);
    }

    public SendMessageRequest(@NonNull String clientMessageId, @NonNull String type, @Nullable String content, @Nullable String replyToId, @Nullable List<String> attachmentIds) {
        this(clientMessageId, type, content, replyToId, attachmentIds, List.of(), false);
    }

    public SendMessageRequest(@NonNull String clientMessageId, @NonNull String type,
            @Nullable String content, @Nullable String replyToId,
            @Nullable List<String> attachmentIds, @Nullable List<MessageMention> mentions,
            boolean mentionAll) {
        this(clientMessageId, type, content, replyToId, attachmentIds, mentions, mentionAll,
                null, null);
    }

    public SendMessageRequest(@NonNull String clientMessageId, @NonNull String type,
            @Nullable String content, @Nullable String replyToId,
            @Nullable List<String> attachmentIds, @Nullable List<MessageMention> mentions,
            boolean mentionAll, @Nullable String encryption,
            @Nullable List<E2eeEnvelopeDto> e2eeEnvelopes) {
        this.clientMessageId = clientMessageId;
        this.type = type;
        this.content = content;
        this.replyToId = replyToId;
        this.attachmentIds = attachmentIds == null ? List.of() : attachmentIds;
        this.mentions = mentions == null ? List.of() : List.copyOf(mentions);
        this.mentionAll = mentionAll;
        this.encryption = encryption;
        this.e2eeEnvelopes = e2eeEnvelopes == null ? null : List.copyOf(e2eeEnvelopes);
    }

    @NonNull public String getClientMessageId() {
        return clientMessageId;
    }

    @NonNull public String getType() {
        return type;
    }

    @Nullable public String getContent() {
        return content;
    }

    @Nullable public String getReplyToId() {
        return replyToId;
    }

    @NonNull public List<String> getAttachmentIds() {
        return attachmentIds;
    }

    @NonNull public List<MessageMention> getMentions() {
        return mentions == null ? List.of() : mentions;
    }

    public boolean isMentionAll() { return mentionAll; }
    public boolean getMentionAll() { return mentionAll; }

    @Nullable public String getEncryption() { return encryption; }

    @Nullable public List<E2eeEnvelopeDto> getE2eeEnvelopes() { return e2eeEnvelopes; }

    /** Copy with E2EE wire fields attached; used by the encrypted send path. */
    @NonNull public SendMessageRequest withEncryption(@Nullable String nextEncryption,
            @Nullable List<E2eeEnvelopeDto> nextEnvelopes) {
        return new SendMessageRequest(clientMessageId, type, content, replyToId,
                attachmentIds, mentions, mentionAll, nextEncryption, nextEnvelopes);
    }

    /** Copy with alternate wire content (e.g. ciphertext), preserving e2ee fields. */
    @NonNull public SendMessageRequest withContent(@Nullable String nextContent) {
        return new SendMessageRequest(clientMessageId, type, nextContent, replyToId,
                attachmentIds, mentions, mentionAll, encryption, e2eeEnvelopes);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof SendMessageRequest that)) {
            return false;
        }
        return Objects.equals(clientMessageId, that.clientMessageId)
                && Objects.equals(type, that.type)
                && Objects.equals(content, that.content)
                && Objects.equals(replyToId, that.replyToId)
                && Objects.equals(attachmentIds, that.attachmentIds)
                && Objects.equals(mentions, that.mentions)
                && mentionAll == that.mentionAll;
    }

    @Override
    public int hashCode() {
        return Objects.hash(clientMessageId, type, content, replyToId, attachmentIds,
                mentions, mentionAll);
    }
}
