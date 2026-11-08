package com.nexachat.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.nexachat.core.database.AppDatabase;
import com.nexachat.core.database.ConversationDao;
import com.nexachat.core.database.ConversationWatermark;
import com.nexachat.core.database.MessageDao;
import com.nexachat.core.database.MessageEntity;
import com.nexachat.core.database.MessageReactionEntity;
import com.nexachat.core.database.ReactionDao;
import com.nexachat.core.network.ApiResult;
import com.nexachat.core.network.MessageApi;
import com.nexachat.core.network.dto.AttachmentDto;
import com.nexachat.core.network.dto.MessageDto;
import com.nexachat.core.network.dto.ReactionSummaryDto;
import com.nexachat.core.network.dto.ReactionUpdateDto;
import com.nexachat.core.network.dto.SyncRequest;
import com.nexachat.core.network.dto.SyncResponse;
import com.nexachat.core.storage.TokenStore;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Client Delta Sync Engine: aggregates all conversation watermarks,
 * requests POST /api/v1/sync, and applies the delta bundle in a single Room transaction.
 */
@Singleton
public final class SyncEngine {
    private final AppDatabase appDatabase;
    private final MessageApi messageApi;
    private final MessageDao messageDao;
    private final ConversationDao conversationDao;
    private final ReactionDao reactionDao;
    private final TokenStore tokenStore;
    private final Moshi moshi;
    private final JsonAdapter<List<AttachmentDto>> attachmentsAdapter;

    @Inject
    public SyncEngine(
            AppDatabase appDatabase,
            MessageApi messageApi,
            MessageDao messageDao,
            ConversationDao conversationDao,
            ReactionDao reactionDao,
            TokenStore tokenStore,
            Moshi moshi) {
        this.appDatabase = appDatabase;
        this.messageApi = messageApi;
        this.messageDao = messageDao;
        this.conversationDao = conversationDao;
        this.reactionDao = reactionDao;
        this.tokenStore = tokenStore;
        this.moshi = moshi;
        this.attachmentsAdapter = moshi.adapter(Types.newParameterizedType(List.class, AttachmentDto.class));
    }

    /**
     * Gathers all conversation watermarks from Room, requests delta bundle from server,
     * and atomically ingests new, edited, deleted messages and reaction updates in a Room transaction.
     */
    public Completable sync() {
        return Single.zip(
                messageDao.getAllMaxSeqs(),
                conversationDao.getAllWatermarks(),
                this::mergeWatermarks
        )
        .flatMap(watermarks -> {
            if (watermarks.isEmpty()) {
                return Single.just(new SyncResponse());
            }
            List<SyncRequest.Watermark> reqWatermarks = new ArrayList<>(watermarks.size());
            for (ConversationWatermark wm : watermarks) {
                reqWatermarks.add(new SyncRequest.Watermark(wm.getConversationId(), wm.getMaxSeq()));
            }
            SyncRequest request = new SyncRequest(reqWatermarks, 50);
            return ApiResult.safeApiCall(messageApi.syncDeltas(request)).map(apiResult -> {
                if (apiResult instanceof ApiResult.Success<?> success) {
                    return (SyncResponse) success.getBody();
                }
                return new SyncResponse();
            });
        })
        .flatMapCompletable(response -> Completable.fromAction(() -> applyDeltaBundle(response)))
        .subscribeOn(Schedulers.io());
    }

    private List<ConversationWatermark> mergeWatermarks(
            List<ConversationWatermark> msgWatermarks,
            List<ConversationWatermark> convWatermarks) {
        Map<String, Long> map = new HashMap<>();
        for (ConversationWatermark wm : msgWatermarks) {
            map.put(wm.getConversationId(), Math.max(map.getOrDefault(wm.getConversationId(), 0L), wm.getMaxSeq()));
        }
        for (ConversationWatermark wm : convWatermarks) {
            map.put(wm.getConversationId(), Math.max(map.getOrDefault(wm.getConversationId(), 0L), wm.getMaxSeq()));
        }
        List<ConversationWatermark> merged = new ArrayList<>(map.size());
        for (Map.Entry<String, Long> entry : map.entrySet()) {
            merged.add(new ConversationWatermark(entry.getKey(), entry.getValue()));
        }
        return merged;
    }

    private void applyDeltaBundle(SyncResponse response) {
        if (response == null || response.getDeltas().isEmpty()) {
            return;
        }

        com.nexachat.core.storage.Tokens tokens = tokenStore.readTokens().blockingGet();
        final String myUserId = (tokens != null && tokens.getAccessToken() != null)
                ? Optional.ofNullable(TimeAndJwt.userIdFromJwt(tokens.getAccessToken(), moshi)).orElse("")
                : "";

        appDatabase.runInTransaction(() -> {
            for (SyncResponse.ConversationDeltaDto delta : response.getDeltas()) {
                String conversationId = delta.getConversationId();
                if (conversationId == null) continue;

                // 1. Ingest new messages
                if (!delta.getNewMessages().isEmpty()) {
                    List<MessageEntity> newEntities = new ArrayList<>();
                    List<String> reactionMsgIds = new ArrayList<>();
                    List<MessageReactionEntity> reactionEntities = new ArrayList<>();

                    for (MessageDto dto : delta.getNewMessages()) {
                        MessageEntity entity = toEntity(dto, conversationId);
                        if (entity != null) {
                            newEntities.add(entity);
                            if (dto.getReactions() != null && !dto.getReactions().isEmpty()) {
                                reactionMsgIds.add(entity.getId());
                                for (ReactionSummaryDto summary : dto.getReactions()) {
                                    MessageReactionEntity rEntity = toReactionEntity(
                                            summary, entity.getId(), conversationId, myUserId);
                                    if (rEntity != null) reactionEntities.add(rEntity);
                                }
                            }
                        }
                    }

                    if (!newEntities.isEmpty()) {
                        messageDao.upsertAll(newEntities).blockingAwait();
                        MessageEntity last = newEntities.get(newEntities.size() - 1);
                        conversationDao.updateLastMessage(
                                conversationId, last.getSeq(),
                                last.getContent() != null ? last.getContent() : last.getType(),
                                last.getSenderDisplayName() != null ? last.getSenderDisplayName() : "",
                                last.getCreatedAt()
                        ).blockingAwait();
                    }
                    if (!reactionMsgIds.isEmpty()) {
                        reactionDao.replaceForMessagesInTransaction(reactionMsgIds, reactionEntities);
                    }
                }

                // 2. Ingest edited messages
                if (!delta.getEditedMessages().isEmpty()) {
                    for (MessageDto dto : delta.getEditedMessages()) {
                        MessageEntity entity = toEntity(dto, conversationId);
                        if (entity != null) {
                            messageDao.update(entity).blockingAwait();
                            conversationDao.updateLastMessageContent(
                                    conversationId, entity.getSeq(),
                                    entity.getContent() != null ? entity.getContent() : entity.getType()
                            ).blockingAwait();
                        }
                    }
                }

                // 3. Ingest deleted message IDs
                if (!delta.getDeletedMessageIds().isEmpty()) {
                    for (String deletedId : delta.getDeletedMessageIds()) {
                        MessageEntity existing = messageDao.byId(deletedId).blockingGet();
                        if (existing != null && existing.getDeletedAtEpochMs() == null) {
                            MessageEntity tombstone = new MessageEntity(
                                    existing.getId(), existing.getConversationId(), existing.getSeq(),
                                    existing.getSenderId(), existing.getSenderDisplayName(), existing.getType(),
                                    "", existing.getClientMessageId(), existing.getReplyToId(),
                                    existing.getReplyToSenderName(), existing.getReplyToSnippet(),
                                    existing.getCreatedAt(), null, existing.getEditedAtEpochMs(),
                                    System.currentTimeMillis()
                            );
                            messageDao.update(tombstone).blockingAwait();
                            reactionDao.deleteForMessageInTransaction(deletedId);
                            messageDao.clearReplySnippets(deletedId).blockingAwait();
                        }
                    }
                }

                // 4. Ingest reaction updates
                if (!delta.getReactionUpdates().isEmpty()) {
                    for (ReactionUpdateDto update : delta.getReactionUpdates()) {
                        String msgId = update.getMessageId();
                        if (msgId != null) {
                            List<MessageReactionEntity> rEntities = new ArrayList<>();
                            for (ReactionSummaryDto summary : update.getReactions()) {
                                MessageReactionEntity rEntity = toReactionEntity(
                                        summary, msgId, conversationId, myUserId);
                                if (rEntity != null) rEntities.add(rEntity);
                            }
                            reactionDao.replaceForMessageInTransaction(msgId, rEntities);
                        }
                    }
                }

                // 5. Peer receipt watermarks advance ONLY from the peer's own read/delivered
                //    frames; a delta bundle says nothing about what the peer has read.
            }
        });
    }

    @Nullable
    private MessageEntity toEntity(MessageDto dto, @Nullable String fallbackConversationId) {
        String conversationId = dto.getConversationId() != null ? dto.getConversationId() : fallbackConversationId;
        if (conversationId == null) return null;
        boolean deleted = dto.getDeletedAt() != null && !dto.getDeletedAt().isBlank();
        String attachmentsJson = null;
        if (!deleted && dto.getAttachments() != null && !dto.getAttachments().isEmpty()) {
            try {
                attachmentsJson = attachmentsAdapter.toJson(dto.getAttachments());
            } catch (RuntimeException ignored) {
                attachmentsJson = null;
            }
        }
        return new MessageEntity(
                dto.getId(),
                conversationId,
                dto.getSeq() == null ? 0L : dto.getSeq(),
                Optional.ofNullable(dto.getResolvedSenderId()).orElse(""),
                dto.getResolvedSenderName(),
                dto.getResolvedType(),
                deleted ? "" : dto.getContent(),
                dto.getClientMessageId(),
                dto.getResolvedReplyToId(),
                dto.getReplyTo() == null ? null : dto.getReplyTo().getResolvedSenderName(),
                dto.getReplyTo() == null ? null : dto.getReplyTo().getResolvedSnippet(),
                TimeAndJwt.isoToEpochMs(dto.getCreatedAt()),
                attachmentsJson,
                TimeAndJwt.isoToEpochMsOrNull(dto.getEditedAt()),
                TimeAndJwt.isoToEpochMsOrNull(dto.getDeletedAt())
        );
    }

    @Nullable
    private MessageReactionEntity toReactionEntity(
            ReactionSummaryDto summary, String messageId, String conversationId, @Nullable String myId) {
        String emoji = summary.getEmoji();
        if (emoji == null || emoji.isBlank()) return null;
        Integer count = summary.getCount();
        Boolean reactedByMe = summary.getReactedByMe();
        if (reactedByMe == null) reactedByMe = summary.getByMe();
        if (reactedByMe == null) reactedByMe = myId != null && summary.getUserIds().contains(myId);
        return new MessageReactionEntity(
                messageId,
                conversationId,
                emoji,
                Math.max(0, count == null ? summary.getUserIds().size() : count),
                Boolean.TRUE.equals(reactedByMe)
        );
    }
}
