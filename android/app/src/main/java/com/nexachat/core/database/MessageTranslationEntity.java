package com.nexachat.core.database;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import java.util.Objects;

/**
 * One cached translation of a message for one target language. The cache key
 * includes the message's sync-v2 {@code changeVersion}, so edits and recalls
 * make prior rows unreachable without a cleanup sweep. {@code fetchedAt}
 * bounds staleness for TTL eviction.
 */
@Entity(
        tableName = "message_translations",
        primaryKeys = {"messageId", "changeVersion", "targetLang"})
public final class MessageTranslationEntity {

    @NonNull private final String messageId;
    @NonNull private final String conversationId;
    private final long changeVersion;
    @NonNull private final String targetLang;
    @NonNull private final String sourceLang;
    @NonNull private final String translatedText;
    private final long fetchedAt;

    public MessageTranslationEntity(
            @NonNull String messageId,
            @NonNull String conversationId,
            long changeVersion,
            @NonNull String targetLang,
            @NonNull String sourceLang,
            @NonNull String translatedText,
            long fetchedAt) {
        this.messageId = messageId;
        this.conversationId = conversationId;
        this.changeVersion = changeVersion;
        this.targetLang = targetLang;
        this.sourceLang = sourceLang;
        this.translatedText = translatedText;
        this.fetchedAt = fetchedAt;
    }

    @NonNull public String getMessageId() { return messageId; }
    @NonNull public String getConversationId() { return conversationId; }
    public long getChangeVersion() { return changeVersion; }
    @NonNull public String getTargetLang() { return targetLang; }
    @NonNull public String getSourceLang() { return sourceLang; }
    @NonNull public String getTranslatedText() { return translatedText; }
    public long getFetchedAt() { return fetchedAt; }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof MessageTranslationEntity)) return false;
        MessageTranslationEntity that = (MessageTranslationEntity) other;
        return changeVersion == that.changeVersion && fetchedAt == that.fetchedAt
                && Objects.equals(messageId, that.messageId)
                && Objects.equals(conversationId, that.conversationId)
                && Objects.equals(targetLang, that.targetLang)
                && Objects.equals(sourceLang, that.sourceLang)
                && Objects.equals(translatedText, that.translatedText);
    }

    @Override public int hashCode() {
        return Objects.hash(messageId, conversationId, changeVersion, targetLang,
                sourceLang, translatedText, fetchedAt);
    }

    @NonNull @Override public String toString() {
        return "MessageTranslationEntity(messageId=" + messageId
                + ", conversationId=" + conversationId
                + ", changeVersion=" + changeVersion
                + ", targetLang=" + targetLang
                + ", sourceLang=" + sourceLang
                + ", fetchedAt=" + fetchedAt + ')';
    }
}
