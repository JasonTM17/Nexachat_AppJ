package com.nexachat.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.nexachat.core.common.Result;
import com.nexachat.core.database.ConversationDao;
import com.nexachat.core.database.MessageDao;
import com.nexachat.core.database.OutboxDao;
import com.nexachat.core.database.PinDao;
import com.nexachat.core.database.ReactionDao;
import com.nexachat.core.network.MessageApi;
import com.nexachat.core.network.SessionEvents;
import com.nexachat.core.network.dto.AttachmentDto;
import com.nexachat.core.network.dto.MessageDto;
import com.nexachat.core.network.dto.MessagePageDto;
import com.nexachat.core.network.stomp.RealtimeSession;
import com.nexachat.core.storage.TokenStore;
import com.squareup.moshi.Moshi;
import java.util.Collections;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.flow.Flow;
import kotlinx.coroutines.flow.SharedFlow;

/**
 * Message transport and Room write-through. The suspend pipeline stays in
 * {@link MessageRepositoryCore}; this type is the injected Java boundary.
 */
@Singleton
public final class MessageRepository implements MessageRepositoryApi {

    private final MessageRepositoryCore core;

    @Inject
    public MessageRepository(
            @NonNull MessageApi messageApi,
            @NonNull MessageDao messageDao,
            @NonNull ConversationDao conversationDao,
            @NonNull OutboxDao outboxDao,
            @NonNull ReactionDao reactionDao,
            @NonNull PinDao pinDao,
            @NonNull RealtimeSession session,
            @NonNull TokenStore tokenStore,
            @NonNull SessionEvents sessionEvents,
            @NonNull Moshi moshi,
            @NonNull CoroutineScope scope) {
        this.core = new MessageRepositoryCore(
                messageApi,
                messageDao,
                conversationDao,
                outboxDao,
                reactionDao,
                pinDao,
                session,
                tokenStore,
                sessionEvents,
                moshi,
                scope);
    }

    @NonNull
    public SharedFlow<TypingEvent> getTypingEvents() {
        return core.getTypingEvents();
    }

    @NonNull
    public SharedFlow<ReadEvent> getReadEvents() {
        return core.getReadEvents();
    }

    @NonNull
    public SharedFlow<com.nexachat.core.common.ApiError> getInteractionEvents() {
        return core.getInteractionEvents();
    }

    @NonNull
    public Flow<List<ChatMessage>> messagesFlow(@NonNull String conversationId) {
        return core.messagesFlow(conversationId);
    }

    @NonNull
    public Flow<List<ChatMessage>> pinsFlow(@NonNull String conversationId) {
        return core.pinsFlow(conversationId);
    }

    public Object openConversation(
            @NonNull String conversationId, @NonNull Continuation<? super Unit> completion) {
        return core.openConversation(conversationId, completion);
    }

    public void closeConversation(@NonNull String conversationId) {
        core.closeConversation(conversationId);
    }

    public Object refreshHistory(
            @NonNull String conversationId,
            @NonNull Continuation<? super Result<MessagePageDto>> completion) {
        return core.refreshHistory(conversationId, completion);
    }

    public Object refreshHistory(
            @NonNull String conversationId,
            @Nullable Long beforeSeq,
            @NonNull Continuation<? super Result<MessagePageDto>> completion) {
        return core.refreshHistory(conversationId, beforeSeq, completion);
    }

    public Object loadUntilLoaded(
            @NonNull String conversationId,
            @NonNull String messageId,
            @NonNull Continuation<? super MessageJump> completion) {
        return core.loadUntilLoaded(conversationId, messageId, completion);
    }

    public Object loadUntilLoaded(
            @NonNull String conversationId,
            @NonNull String messageId,
            int maxPages,
            @NonNull Continuation<? super MessageJump> completion) {
        return core.loadUntilLoaded(conversationId, messageId, maxPages, completion);
    }

    public Object editMessage(
            @NonNull String conversationId,
            @NonNull String messageId,
            @NonNull String newContent,
            @NonNull Continuation<? super Result<Unit>> completion) {
        return core.editMessage(conversationId, messageId, newContent, completion);
    }

    public Object deleteMessage(
            @NonNull String conversationId,
            @NonNull String messageId,
            @NonNull Continuation<? super Result<Unit>> completion) {
        return core.deleteMessage(conversationId, messageId, completion);
    }

    public Object searchMessages(
            @NonNull String conversationId,
            @NonNull String query,
            @NonNull Continuation<? super Result<MessageSearchResult>> completion) {
        return core.searchMessages(conversationId, query, completion);
    }

    public Object searchMessages(
            @NonNull String conversationId,
            @NonNull String query,
            @Nullable Long beforeSeq,
            @NonNull Continuation<? super Result<MessageSearchResult>> completion) {
        return core.searchMessages(conversationId, query, beforeSeq, completion);
    }

    public Object searchMessages(
            @NonNull String conversationId,
            @NonNull String query,
            @Nullable Long beforeSeq,
            int limit,
            @NonNull Continuation<? super Result<MessageSearchResult>> completion) {
        return core.searchMessages(conversationId, query, beforeSeq, limit, completion);
    }

    public Object send(
            @NonNull String conversationId,
            @NonNull String content,
            @NonNull Continuation<? super Unit> completion) {
        return core.send(conversationId, content, completion);
    }

    public Object send(
            @NonNull String conversationId,
            @NonNull String content,
            @Nullable String replyToId,
            @NonNull Continuation<? super Unit> completion) {
        return core.send(conversationId, content, replyToId, completion);
    }

    public Object send(
            @NonNull String conversationId,
            @NonNull String content,
            @Nullable String replyToId,
            @NonNull String type,
            @NonNull List<AttachmentDto> attachments,
            @NonNull Continuation<? super Unit> completion) {
        return core.send(conversationId, content, replyToId, type, attachments, completion);
    }

    public Object retry(
            @NonNull String clientMessageId, @NonNull Continuation<? super Unit> completion) {
        return core.retry(clientMessageId, completion);
    }

    public Object flushOutbox(@NonNull Continuation<? super Unit> completion) {
        return core.flushOutbox(completion);
    }

    public Object sendRead(
            @NonNull String conversationId,
            long upToSeq,
            @NonNull Continuation<? super Unit> completion) {
        return core.sendRead(conversationId, upToSeq, completion);
    }

    public Object toggleReaction(
            @NonNull String messageId,
            @NonNull String conversationId,
            @NonNull String emoji,
            @NonNull Continuation<? super Unit> completion) {
        return core.toggleReaction(messageId, conversationId, emoji, completion);
    }

    public Object refreshPins(
            @NonNull String conversationId, @NonNull Continuation<? super Result<Unit>> completion) {
        return core.refreshPins(conversationId, completion);
    }

    public Object pinMessage(
            @NonNull String conversationId,
            @NonNull String messageId,
            @NonNull Continuation<? super Unit> completion) {
        return core.pinMessage(conversationId, messageId, completion);
    }

    public Object unpinMessage(
            @NonNull String conversationId,
            @NonNull String messageId,
            @NonNull Continuation<? super Unit> completion) {
        return core.unpinMessage(conversationId, messageId, completion);
    }

    public Object resyncForward(
            @NonNull String conversationId, @NonNull Continuation<? super Unit> completion) {
        return core.resyncForward(conversationId, completion);
    }

    @Nullable
    public Object currentUserId(@NonNull Continuation<? super String> completion) {
        return core.currentUserId(completion);
    }

    public Object isMine(
            @Nullable String senderId, @NonNull Continuation<? super Boolean> completion) {
        return core.isMine(senderId, completion);
    }

    @NonNull
    static List<AttachmentDto> emptyAttachments() {
        return Collections.emptyList();
    }
}
