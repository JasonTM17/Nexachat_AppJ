package com.nexachat.data;

import androidx.annotation.Nullable;
import com.nexachat.core.database.*;
import com.nexachat.core.network.dto.*;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Narrow synchronous server-state apply seam. Caller owns the Room transaction and session fence. */
final class MessageStateApplier {
    private final AppDatabase db;
    private final JsonAdapter<List<AttachmentDto>> attachments;
    @Nullable private final com.nexachat.core.crypto.E2eeSession e2eeSession;

    MessageStateApplier(AppDatabase db, Moshi moshi) {
        this(db, moshi, null);
    }

    MessageStateApplier(AppDatabase db, Moshi moshi,
            @Nullable com.nexachat.core.crypto.E2eeSession e2eeSession) {
        this.db = db;
        this.e2eeSession = e2eeSession;
        attachments = moshi.adapter(Types.newParameterizedType(List.class, AttachmentDto.class));
    }

    /** No generation may replace another without the explicit reset transaction. */
    static boolean accepts(@Nullable MessageEntity old, MessageEntity incoming) {
        if (old == null) return true;
        if (!old.getConversationId().equals(incoming.getConversationId())) return false;
        if (old.getDeletedAtEpochMs() != null && incoming.getDeletedAtEpochMs() == null
                && (old.getGeneration() == 0 || incoming.getGeneration() == 0)) return false;
        if (old.getGeneration() > 0) {
            return old.getGeneration() == incoming.getGeneration()
                    && (incoming.getChangeVersion() > old.getChangeVersion()
                    || (old.getSeq() == 0 && incoming.getSeq() > 0
                    && incoming.getChangeVersion() == old.getChangeVersion()));
        }
        if (incoming.getGeneration() > 0) return true;
        return incoming.getDeletedAtEpochMs() != null || (incoming.getEditedAtEpochMs() != null
                && (old.getEditedAtEpochMs() == null
                || incoming.getEditedAtEpochMs() > old.getEditedAtEpochMs()));
    }

    boolean apply(MessageDto dto, String conversation, long generation, String owner,
            boolean reconcileReactions) {
        MessageEntity row = toEntity(dto, conversation, generation);
        if (row.getReplyToId() != null) {
            MessageEntity parent = db.messageDao().byId(row.getReplyToId()).blockingGet();
            if (parent != null && parent.getDeletedAtEpochMs() != null) row = row.withReplyToSnippet(null);
        }
        MessageEntity old = db.messageDao().byId(row.getId()).blockingGet();
        boolean accepted = accepts(old, row);
        if (!accepted) {
            // A reaction rebuild also replaces empty aggregates for an unchanged message revision.
            if (reconcileReactions && old != null && old.getGeneration() == generation
                    && old.getChangeVersion() == row.getChangeVersion()
                    && old.getDeletedAtEpochMs() == null) {
                replaceReactions(dto, row, owner);
            }
            return false;
        }
        if (old == null) db.messageDao().upsert(row).blockingAwait();
        else db.messageDao().update(row).blockingAwait();
        db.conversationDao().updateLastMessage(conversation, row.getSeq(),
                Objects.requireNonNullElse(row.getContent(), row.getType()),
                Objects.requireNonNullElse(row.getSenderDisplayName(), ""), row.getCreatedAt(),
                row.isEncrypted()).blockingAwait();
        if (row.getDeletedAtEpochMs() != null) {
            cleanupDelete(row);
        } else {
            db.conversationDao().updateLastMessageContent(conversation, row.getSeq(),
                    Objects.requireNonNullElse(row.getContent(), row.getType())).blockingAwait();
            // A message revision does not order reactions. Only the full reconciliation owns snapshots.
            if (reconcileReactions) replaceReactions(dto, row, owner);
        }
        return true;
    }

    void delete(String id, String conversation, long version, long generation, Long deletedAt) {
        MessageEntity old = db.messageDao().byId(id).blockingGet();
        MessageEntity row = old == null
                ? new MessageEntity(id, conversation, 0, "", null, "TEXT", "", null,
                        null, null, null, 0, null, null, deletedAt)
                : old.asDeleted(deletedAt);
        row = row.withSyncVersion(version, generation);
        if (!accepts(old, row)) return;
        if (old == null) db.messageDao().upsert(row).blockingAwait();
        else db.messageDao().update(row).blockingAwait();
        cleanupDelete(row);
    }

    private void cleanupDelete(MessageEntity row) {
        db.reactionDao().deleteForMessageInTransaction(row.getId());
        db.pinDao().delete(row.getConversationId(), row.getId()).blockingAwait();
        db.messageDao().clearReplySnippets(row.getId()).blockingAwait();
        ConversationEntity conversation = db.conversationDao().byId(row.getConversationId()).blockingGet();
        if (conversation != null && conversation.getLastMessageSeq() == row.getSeq()) {
            db.conversationDao().markLastMessageDeleted(row.getConversationId()).blockingAwait();
        }
    }

    private void replaceReactions(MessageDto dto, MessageEntity row, String owner) {
        if (dto.getReactions() == null) return;
        db.reactionDao().replaceForMessageInTransaction(row.getId(),
                reactions(dto.getReactions(), row.getId(), row.getConversationId(), owner));
    }

    static List<MessageReactionEntity> reactions(List<ReactionSummaryDto> summaries,
            String message, String conversation, String owner) {
        List<MessageReactionEntity> rows = new ArrayList<>();
        for (ReactionSummaryDto summary : summaries) {
            if (summary.getEmoji() == null || summary.getEmoji().isBlank()) continue;
            Boolean mine = summary.getReactedByMe();
            if (mine == null) mine = summary.getByMe();
            if (mine == null) mine = summary.getUserIds().contains(owner);
            rows.add(new MessageReactionEntity(message, conversation, summary.getEmoji(),
                    Math.max(0, summary.getCount() == null ? summary.getUserIds().size() : summary.getCount()),
                    Boolean.TRUE.equals(mine)));
        }
        return rows;
    }

    private MessageEntity toEntity(MessageDto dto, String conversation, long generation) {
        boolean deleted = dto.getDeletedAt() != null && !dto.getDeletedAt().isBlank();
        com.nexachat.core.crypto.E2eeMessageMapping.Resolved resolved = deleted
                ? new com.nexachat.core.crypto.E2eeMessageMapping.Resolved("", false, false)
                : com.nexachat.core.crypto.E2eeMessageMapping.resolve(dto, conversation, null,
                        e2eeSession);
        boolean replyToEncrypted = com.nexachat.core.crypto.E2eeMessageMapping
                .replyTargetEncrypted(dto.getReplyTo());
        return new MessageEntity(dto.getId(), conversation, dto.getSeq() == null ? 0 : dto.getSeq(),
                Optional.ofNullable(dto.getResolvedSenderId()).orElse(""), dto.getResolvedSenderName(),
                dto.getResolvedType(), deleted ? "" : resolved.content(), dto.getClientMessageId(),
                dto.getResolvedReplyToId(), dto.getReplyTo() == null ? null : dto.getReplyTo().getResolvedSenderName(),
                dto.getReplyTo() == null || replyToEncrypted ? null : dto.getReplyTo().getResolvedSnippet(),
                TimeAndJwt.isoToEpochMs(dto.getCreatedAt()),
                deleted || dto.getAttachments().isEmpty() ? null : attachments.toJson(dto.getAttachments()),
                TimeAndJwt.isoToEpochMsOrNull(dto.getEditedAt()), TimeAndJwt.isoToEpochMsOrNull(dto.getDeletedAt()),
                deleted ? "[]" : MessageMentionCodec.encode(dto.getMentions()), !deleted && dto.isMentionAll(),
                dto.getChangeVersion() == null ? 0 : dto.getChangeVersion(), generation,
                resolved.encrypted(), resolved.decryptFailed(), replyToEncrypted);
    }
}
