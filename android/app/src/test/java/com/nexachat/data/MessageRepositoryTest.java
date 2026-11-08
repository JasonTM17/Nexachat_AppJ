package com.nexachat.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexachat.core.common.ApiError;
import com.nexachat.core.common.Result;
import com.nexachat.core.database.ConversationDao;
import com.nexachat.core.database.ConversationEntity;
import com.nexachat.core.database.MessageDao;
import com.nexachat.core.database.MessageEntity;
import com.nexachat.core.database.MessageReactionEntity;
import com.nexachat.core.database.OutboxDao;
import com.nexachat.core.database.OutboxEntity;
import com.nexachat.core.database.OutboxState;
import com.nexachat.core.database.PinDao;
import com.nexachat.core.database.PinnedMessageEntity;
import com.nexachat.core.database.ReactionDao;
import com.nexachat.core.network.MessageApi;
import com.nexachat.core.network.SessionEvents;
import com.nexachat.core.network.dto.AttachmentDto;
import com.nexachat.core.network.dto.EditMessageRequest;
import com.nexachat.core.network.dto.MessageDto;
import com.nexachat.core.network.dto.MessagePageDto;
import com.nexachat.core.network.dto.PinRequest;
import com.nexachat.core.network.dto.ReactionSummaryDto;
import com.nexachat.core.network.dto.ReplyRefDto;
import com.nexachat.core.network.dto.SendMessageRequest;
import com.nexachat.core.network.stomp.ChatEvent;
import com.nexachat.core.network.stomp.RealtimeSession;
import com.nexachat.core.storage.TokenStore;
import com.nexachat.core.storage.Tokens;
import com.squareup.moshi.Moshi;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.observers.TestObserver;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.schedulers.TestScheduler;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.ResponseBody;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import retrofit2.Call;
import retrofit2.Response;
import com.nexachat.core.common.Unit;

/** RxJava repository coverage. DAO mocks implement the Room Rx contracts in memory. */
public class MessageRepositoryTest {
    private static final String CONVERSATION_ID = "c-1";
    private final Map<String, MessageEntity> messages = new LinkedHashMap<>();
    private final Map<String, OutboxEntity> outbox = new LinkedHashMap<>();
    private final Map<String, ConversationEntity> conversations = new LinkedHashMap<>();
    private final Map<ReactionKey, MessageReactionEntity> reactions = new LinkedHashMap<>();
    private final Map<PinKey, PinnedMessageEntity> pins = new LinkedHashMap<>();
    private final List<String> unreadBumps = new ArrayList<>();
    private MessageDao messageDao;
    private OutboxDao outboxDao;
    private ConversationDao conversationDao;
    private ReactionDao reactionDao;
    private PinDao pinDao;
    private MessageApi messageApi;
    private RealtimeSession session;
    private TokenStore tokenStore;
    private SessionEvents sessionEvents;
    private io.reactivex.rxjava3.processors.BehaviorProcessor<Integer> messageChanges;
    private io.reactivex.rxjava3.processors.BehaviorProcessor<List<OutboxEntity>> outboxChanges;
    private io.reactivex.rxjava3.processors.BehaviorProcessor<Integer> reactionChanges;
    private io.reactivex.rxjava3.processors.BehaviorProcessor<Integer> pinChanges;
    private io.reactivex.rxjava3.processors.PublishProcessor<ChatEvent> events;
    private io.reactivex.rxjava3.processors.PublishProcessor<Object> resyncRequests;
    private io.reactivex.rxjava3.processors.PublishProcessor<SessionEvents.Event> logoutEvents;
    private io.reactivex.rxjava3.processors.BehaviorProcessor<Boolean> isOpen;
    private TestScheduler timer;
    private MessageRepository repository;

    @Before public void setUp() throws Exception {
        RxJavaPlugins.setIoSchedulerHandler(ignored -> Schedulers.trampoline());
        messageChanges = io.reactivex.rxjava3.processors.BehaviorProcessor.createDefault(0);
        outboxChanges = io.reactivex.rxjava3.processors.BehaviorProcessor.createDefault(List.of());
        reactionChanges = io.reactivex.rxjava3.processors.BehaviorProcessor.createDefault(0);
        pinChanges = io.reactivex.rxjava3.processors.BehaviorProcessor.createDefault(0);
        events = io.reactivex.rxjava3.processors.PublishProcessor.create();
        resyncRequests = io.reactivex.rxjava3.processors.PublishProcessor.create();
        logoutEvents = io.reactivex.rxjava3.processors.PublishProcessor.create();
        isOpen = io.reactivex.rxjava3.processors.BehaviorProcessor.createDefault(true);
        timer = new TestScheduler();

        messageDao = mock(MessageDao.class);
        outboxDao = mock(OutboxDao.class);
        conversationDao = mock(ConversationDao.class);
        reactionDao = mock(ReactionDao.class);
        pinDao = mock(PinDao.class);
        messageApi = mock(MessageApi.class);
        session = mock(RealtimeSession.class);
        tokenStore = mock(TokenStore.class);
        sessionEvents = mock(SessionEvents.class);

        when(messageDao.upsert(any())).thenAnswer(call -> {
            MessageEntity row = call.getArgument(0);
            messages.putIfAbsent(row.getId(), row);
            messageChanges.onNext(messageChanges.getValue() + 1);
            return Completable.complete();
        });
        when(messageDao.upsertAll(any())).thenAnswer(call -> {
            for (MessageEntity row : (List<MessageEntity>) call.getArgument(0)) messages.putIfAbsent(row.getId(), row);
            messageChanges.onNext(messageChanges.getValue() + 1);
            return Completable.complete();
        });
        when(messageDao.update(any())).thenAnswer(call -> {
            MessageEntity row = call.getArgument(0);
            if (messages.containsKey(row.getId())) messages.put(row.getId(), row);
            messageChanges.onNext(messageChanges.getValue() + 1);
            return Completable.complete();
        });
        when(messageDao.byId(anyString())).thenAnswer(call -> Maybe.fromCallable(() -> messages.get(call.getArgument(0))));
        when(messageDao.clearReplySnippets(anyString())).thenAnswer(call -> {
            String target = call.getArgument(0);
            for (MessageEntity row : new ArrayList<>(messages.values())) {
                if (target.equals(row.getReplyToId())) {
                    messages.put(row.getId(), copy(row, row.getContent(), row.getAttachmentsJson(),
                            row.getEditedAtEpochMs(), row.getDeletedAtEpochMs(), null));
                }
            }
            messageChanges.onNext(messageChanges.getValue() + 1);
            return Completable.complete();
        });
        when(messageDao.messagesForConversation(anyString())).thenAnswer(call -> messageChanges
                .map(ignored -> messages.values().stream()
                        .filter(row -> call.getArgument(0).equals(row.getConversationId()))
                        .sorted(java.util.Comparator.comparingLong(MessageEntity::getSeq)).toList()));
        when(messageDao.maxSeq(anyString())).thenAnswer(call -> Single.fromCallable(() -> messages.values().stream()
                .filter(row -> call.getArgument(0).equals(row.getConversationId()))
                .mapToLong(MessageEntity::getSeq).max().orElse(0L)));
        when(messageDao.minSeq(anyString())).thenAnswer(call -> Single.fromCallable(() -> messages.values().stream()
                .filter(row -> call.getArgument(0).equals(row.getConversationId()))
                .mapToLong(MessageEntity::getSeq).min().orElse(0L)));
        when(messageDao.exists(anyString())).thenAnswer(call -> Single.fromCallable(() -> messages.containsKey(call.getArgument(0))));
        when(messageDao.deleteAll()).thenAnswer(call -> {
            messages.clear(); messageChanges.onNext(messageChanges.getValue() + 1); return Completable.complete();
        });

        when(outboxDao.insert(any())).thenAnswer(call -> {
            OutboxEntity row = call.getArgument(0); outbox.put(row.getClientMessageId(), row); syncOutbox();
            return Completable.complete();
        });
        when(outboxDao.updateState(anyString(), anyString())).thenAnswer(call -> {
            String id = call.getArgument(0); OutboxEntity row = outbox.get(id);
            if (row != null) outbox.put(id, new OutboxEntity(row.getClientMessageId(), row.getConversationId(),
                    row.getType(), row.getContent(), row.getReplyToId(), call.getArgument(1),
                    row.getCreatedAt(), row.getAttachmentsJson()));
            syncOutbox(); return Completable.complete();
        });
        when(outboxDao.pendingOrdered()).thenReturn(outboxChanges);
        when(outboxDao.pendingList()).thenAnswer(call -> Single.fromCallable(() -> outbox.values().stream()
                .filter(row -> OutboxState.SENDING.equals(row.getState()))
                .sorted(java.util.Comparator.comparingLong(OutboxEntity::getCreatedAt)).toList()));
        when(outboxDao.byId(anyString())).thenAnswer(call -> Maybe.fromCallable(() -> outbox.get(call.getArgument(0))));
        when(outboxDao.delete(anyString())).thenAnswer(call -> {
            outbox.remove(call.getArgument(0)); syncOutbox(); return Completable.complete();
        });
        when(outboxDao.clear()).thenAnswer(call -> {
            outbox.clear(); syncOutbox(); return Completable.complete();
        });

        when(conversationDao.upsert(any())).thenAnswer(call -> {
            ConversationEntity row = call.getArgument(0); conversations.put(row.getId(), row); return Completable.complete();
        });
        when(conversationDao.byId(anyString())).thenAnswer(call -> Maybe.fromCallable(() -> conversations.get(call.getArgument(0))));
        when(conversationDao.bumpPeerReadSeq(anyString(), anyLong())).thenAnswer(call -> {
            String id = call.getArgument(0); ConversationEntity row = conversations.get(id);
            if (row != null) conversations.put(id, conversationCopy(row, row.getLastMessageContent(),
                    Math.max(row.getPeerReadSeq(), (Long) call.getArgument(1)), row.getUnreadCount(), row.getLastMessageDeleted()));
            return Completable.complete();
        });
        when(conversationDao.clearUnread(anyString())).thenAnswer(call -> {
            ConversationEntity row = conversations.get(call.getArgument(0));
            if (row != null) conversations.put(row.getId(), conversationCopy(row, row.getLastMessageContent(),
                    row.getPeerReadSeq(), 0, row.getLastMessageDeleted()));
            return Completable.complete();
        });
        when(conversationDao.updateLastMessage(anyString(), anyLong(), any(), any(), anyLong())).thenAnswer(call -> {
            String id = call.getArgument(0); ConversationEntity row = conversations.get(id); long seq = call.getArgument(1);
            if (row != null && seq > row.getLastMessageSeq()) {
                conversations.put(id, conversationLast(row, seq, call.getArgument(2), call.getArgument(3), call.getArgument(4), false));
            }
            return Completable.complete();
        });
        when(conversationDao.updateLastMessageContent(anyString(), anyLong(), any())).thenAnswer(call -> {
            String id = call.getArgument(0); ConversationEntity row = conversations.get(id); long seq = call.getArgument(1);
            if (row != null && seq == row.getLastMessageSeq()) {
                conversations.put(id, conversationLast(row, seq, call.getArgument(2), row.getLastMessageSenderName(),
                        row.getLastMessageAt(), false));
            }
            return Completable.complete();
        });
        when(conversationDao.markLastMessageDeleted(anyString())).thenAnswer(call -> {
            String id = call.getArgument(0); ConversationEntity row = conversations.get(id);
            if (row != null) conversations.put(id, conversationLast(row, row.getLastMessageSeq(), null,
                    row.getLastMessageSenderName(), row.getLastMessageAt(), true));
            return Completable.complete();
        });
        when(conversationDao.incrementUnread(anyString(), anyInt())).thenAnswer(call -> {
            String id = call.getArgument(0); int delta = call.getArgument(1); unreadBumps.add(id + ":" + delta);
            ConversationEntity row = conversations.get(id);
            if (row != null) conversations.put(id, conversationCopy(row, row.getLastMessageContent(),
                    row.getPeerReadSeq(), row.getUnreadCount() + delta, row.getLastMessageDeleted()));
            return Completable.complete();
        });
        when(conversationDao.clear()).thenAnswer(call -> { conversations.clear(); return Completable.complete(); });

        when(reactionDao.upsertAll(any())).thenAnswer(call -> {
            for (MessageReactionEntity row : (List<MessageReactionEntity>) call.getArgument(0)) {
                reactions.put(new ReactionKey(row.getMessageId(), row.getEmoji()), row);
            }
            reactionChanges.onNext(reactionChanges.getValue() + 1); return Completable.complete();
        });
        when(reactionDao.reactionsForConversation(anyString())).thenAnswer(call -> reactionChanges.map(ignored ->
                reactions.values().stream().filter(row -> call.getArgument(0).equals(row.getConversationId())).toList()));
        when(reactionDao.forMessage(anyString())).thenAnswer(call -> Single.fromCallable(() -> reactions.values().stream()
                .filter(row -> call.getArgument(0).equals(row.getMessageId())).toList()));
        when(reactionDao.deleteForMessage(anyString())).thenAnswer(call -> {
            reactions.keySet().removeIf(key -> key.messageId.equals(call.getArgument(0)));
            reactionChanges.onNext(reactionChanges.getValue() + 1); return Completable.complete();
        });
        when(reactionDao.deleteForMessages(any())).thenAnswer(call -> {
            List<String> ids = call.getArgument(0);
            reactions.keySet().removeIf(key -> ids.contains(key.messageId));
            reactionChanges.onNext(reactionChanges.getValue() + 1); return Completable.complete();
        });
        when(reactionDao.replaceForMessage(anyString(), any())).thenAnswer(call -> {
            String id = call.getArgument(0); reactions.keySet().removeIf(key -> key.messageId.equals(id));
            for (MessageReactionEntity row : (List<MessageReactionEntity>) call.getArgument(1)) {
                reactions.put(new ReactionKey(row.getMessageId(), row.getEmoji()), row);
            }
            reactionChanges.onNext(reactionChanges.getValue() + 1); return Completable.complete();
        });
        when(reactionDao.replaceForMessages(any(), any())).thenAnswer(call -> {
            List<String> ids = call.getArgument(0); reactions.keySet().removeIf(key -> ids.contains(key.messageId));
            for (MessageReactionEntity row : (List<MessageReactionEntity>) call.getArgument(1)) {
                reactions.put(new ReactionKey(row.getMessageId(), row.getEmoji()), row);
            }
            reactionChanges.onNext(reactionChanges.getValue() + 1); return Completable.complete();
        });
        when(reactionDao.clear()).thenAnswer(call -> {
            reactions.clear(); reactionChanges.onNext(reactionChanges.getValue() + 1); return Completable.complete();
        });

        when(pinDao.upsert(any())).thenAnswer(call -> {
            PinnedMessageEntity row = call.getArgument(0); pins.put(new PinKey(row.getConversationId(), row.getMessageId()), row);
            pinChanges.onNext(pinChanges.getValue() + 1); return Completable.complete();
        });
        when(pinDao.upsertAll(any())).thenAnswer(call -> {
            for (PinnedMessageEntity row : (List<PinnedMessageEntity>) call.getArgument(0)) {
                pins.put(new PinKey(row.getConversationId(), row.getMessageId()), row);
            }
            pinChanges.onNext(pinChanges.getValue() + 1); return Completable.complete();
        });
        when(pinDao.pinnedFor(anyString())).thenAnswer(call -> pinChanges.map(ignored -> pins.values().stream()
                .filter(row -> call.getArgument(0).equals(row.getConversationId()))
                .sorted(java.util.Comparator.comparingLong(PinnedMessageEntity::getPinnedAt).reversed()).toList()));
        when(pinDao.find(anyString(), anyString())).thenAnswer(call -> Maybe.fromCallable(() ->
                pins.get(new PinKey(call.getArgument(0), call.getArgument(1)))));
        when(pinDao.delete(anyString(), anyString())).thenAnswer(call -> {
            pins.remove(new PinKey(call.getArgument(0), call.getArgument(1)));
            pinChanges.onNext(pinChanges.getValue() + 1); return Completable.complete();
        });
        when(pinDao.deleteForConversation(anyString())).thenAnswer(call -> {
            String id = call.getArgument(0); pins.keySet().removeIf(key -> key.conversationId.equals(id));
            pinChanges.onNext(pinChanges.getValue() + 1); return Completable.complete();
        });
        when(pinDao.replaceForConversation(anyString(), any())).thenAnswer(call -> {
            String id = call.getArgument(0); pins.keySet().removeIf(key -> key.conversationId.equals(id));
            for (PinnedMessageEntity row : (List<PinnedMessageEntity>) call.getArgument(1)) {
                pins.put(new PinKey(row.getConversationId(), row.getMessageId()), row);
            }
            pinChanges.onNext(pinChanges.getValue() + 1); return Completable.complete();
        });
        when(pinDao.clear()).thenAnswer(call -> {
            pins.clear(); pinChanges.onNext(pinChanges.getValue() + 1); return Completable.complete();
        });

        when(session.getEvents()).thenReturn(events);
        when(session.getIsOpen()).thenReturn(isOpen);
        when(session.getResyncRequests()).thenReturn(resyncRequests);
        when(session.sendMessage(anyString(), any())).thenReturn(true);
        when(session.sendTyping(anyString())).thenReturn(true);
        doNothing().when(session).ensureConnected();
        doNothing().when(session).watchConversation(anyString());
        doNothing().when(session).unwatchConversation(anyString());
        when(sessionEvents.getEvents()).thenReturn(logoutEvents);
        when(tokenStore.readTokens()).thenReturn(Maybe.just(new Tokens(jwtFor("me-1"), Long.MAX_VALUE, "refresh")));
        when(messageApi.getMessages(anyString(), nullable(Long.class), nullable(Long.class), anyInt()))
                .thenReturn(responseCall(Response.success(new MessagePageDto())));
        when(messageApi.markRead(anyString(), any())).thenReturn(responseCall(Response.success(204, null)));

        repository = new MessageRepository(messageApi, messageDao, conversationDao, outboxDao,
                reactionDao, pinDao, session, tokenStore, sessionEvents, new Moshi.Builder().build(), timer);
    }

    @After public void tearDown() {
        if (repository != null) repository.dispose();
        RxJavaPlugins.reset();
    }

    @Test public void sendEnqueuesSendingAndDispatchesOverStomp() {
        await(repository.send(CONVERSATION_ID, "hello"));
        OutboxEntity row = outbox.values().iterator().next();
        assertEquals(OutboxState.SENDING, row.getState());
        assertEquals("hello", row.getContent());
        verify(session, times(1)).sendMessage(org.mockito.ArgumentMatchers.eq(CONVERSATION_ID),
                org.mockito.ArgumentMatchers.argThat(request -> row.getClientMessageId().equals(request.getClientMessageId())));
    }

    @Test public void newSendsFollowPersistedOutboxAfterClockSetback() {
        long persisted = System.currentTimeMillis() + 60_000L;
        await(outboxDao.insert(new OutboxEntity("cm-persisted", CONVERSATION_ID, "TEXT", "earlier", null,
                OutboxState.FAILED, persisted, "")));
        await(repository.send(CONVERSATION_ID, "later"));
        assertTrue(outbox.get("cm-persisted").getCreatedAt() < outbox.values().stream()
                .filter(row -> "later".equals(row.getContent())).findFirst().orElseThrow().getCreatedAt());
    }

    @Test public void messageCreatedWithClientIdCompletesSentDedup() {
        await(repository.send(CONVERSATION_ID, "hello"));
        String clientId = outbox.keySet().iterator().next();
        events.onNext(new ChatEvent.MessageCreated(dto("m-1", clientId, "me-1", 1L), CONVERSATION_ID, null));
        assertTrue(outbox.isEmpty());
        assertEquals(List.of("m-1"), new ArrayList<>(messages.keySet()));
        events.onNext(new ChatEvent.MessageCreated(dto("m-1", clientId, "me-1", 1L), CONVERSATION_ID, null));
        assertEquals(1, messages.size());
    }

    @Test public void sendRearmsConnectionLoopWhenSocketLooksDead() {
        isOpen.onNext(false);
        when(session.sendMessage(anyString(), any())).thenReturn(false);
        when(messageApi.sendMessage(anyString(), any())).thenReturn(responseCall(Response.error(500, body(""))));
        await(repository.send(CONVERSATION_ID, "wake the loop"));
        verify(session).ensureConnected();
    }

    @Test public void sendWhileWebSocketDownFallsBackToRest() {
        isOpen.onNext(false);
        when(session.sendMessage(anyString(), any())).thenReturn(false);
        when(messageApi.sendMessage(anyString(), any())).thenAnswer(call -> {
            SendMessageRequest request = call.getArgument(1);
            return responseCall(Response.success(dto("m-2", request.getClientMessageId(), "me-1", 2L)));
        });
        await(repository.send(CONVERSATION_ID, "offline hi"));
        assertTrue(outbox.isEmpty());
        assertEquals("m-2", messages.keySet().iterator().next());
        verify(messageApi, times(1)).sendMessage(org.mockito.ArgumentMatchers.eq(CONVERSATION_ID), any());
    }

    @Test public void sendWithoutConnectivityStaysSending() {
        isOpen.onNext(false);
        when(session.sendMessage(anyString(), any())).thenReturn(false);
        when(messageApi.sendMessage(anyString(), any())).thenReturn(failedCall(new IOException("offline")));
        await(repository.send(CONVERSATION_ID, "queued"));
        assertEquals(OutboxState.SENDING, outbox.values().iterator().next().getState());
    }

    @Test public void permanentServerRejectionMarksOutboxFailed() {
        isOpen.onNext(false);
        when(session.sendMessage(anyString(), any())).thenReturn(false);
        when(messageApi.sendMessage(anyString(), any())).thenReturn(responseCall(Response.error(403, body(""))));
        await(repository.send(CONVERSATION_ID, "rejected"));
        assertEquals(OutboxState.FAILED, outbox.values().iterator().next().getState());
    }

    @Test public void restRateLimitRetriesAfterWindow() {
        isOpen.onNext(false);
        when(session.sendMessage(anyString(), any())).thenReturn(false);
        AtomicInteger calls = new AtomicInteger();
        when(messageApi.sendMessage(anyString(), any())).thenAnswer(call -> {
            if (calls.incrementAndGet() == 1) return responseCall(Response.error(429, body("")));
            SendMessageRequest request = call.getArgument(1);
            return responseCall(Response.success(dto("m-rate-limited", request.getClientMessageId(), "me-1", 1L)));
        });
        await(repository.send(CONVERSATION_ID, "queued after rate limit"));
        assertEquals(OutboxState.SENDING, outbox.values().iterator().next().getState());
        timer.advanceTimeBy(9_999, TimeUnit.MILLISECONDS);
        timer.triggerActions();
        assertEquals("retry ran before the 10s server window", 1, calls.get());
        timer.advanceTimeBy(1, TimeUnit.MILLISECONDS);
        timer.triggerActions();
        assertTrue(outbox.isEmpty());
        assertEquals(2, calls.get());
        assertEquals(List.of("m-rate-limited"), new ArrayList<>(messages.keySet()));
    }

    @Test public void errorQueueEventMarksMatchingRowFailed() {
        await(repository.send(CONVERSATION_ID, "hello"));
        String clientId = outbox.keySet().iterator().next();
        events.onNext(new ChatEvent.SendRejected("NOT_MEMBER", clientId));
        assertEquals(OutboxState.FAILED, outbox.get(clientId).getState());
    }

    @Test public void errorQueueRateLimitSchedulesAutomaticRetry() {
        await(repository.send(CONVERSATION_ID, "hello"));
        String clientId = outbox.keySet().iterator().next();
        events.onNext(new ChatEvent.SendRejected("RATE_LIMITED", clientId));
        assertEquals(OutboxState.SENDING, outbox.get(clientId).getState());
        timer.advanceTimeBy(10_000, TimeUnit.MILLISECONDS);
        timer.triggerActions();
        verify(session, times(2)).sendMessage(org.mockito.ArgumentMatchers.eq(CONVERSATION_ID), any());
        assertEquals(OutboxState.SENDING, outbox.get(clientId).getState());
    }

    @Test public void retryRequeuesFailedRowAndDispatchesAgain() {
        await(repository.send(CONVERSATION_ID, "hello"));
        String clientId = outbox.keySet().iterator().next();
        await(outboxDao.updateState(clientId, OutboxState.FAILED));
        await(repository.retry(clientId));
        assertEquals(OutboxState.SENDING, outbox.values().iterator().next().getState());
        verify(session, times(2)).sendMessage(org.mockito.ArgumentMatchers.eq(CONVERSATION_ID), any());
    }

    @Test public void manualRetryJoinsTailOfSendingQueue() {
        await(outboxDao.insert(outbox("cm-failed", "retry me", OutboxState.FAILED, 1L)));
        await(outboxDao.insert(outbox("cm-waiting", "older pending", OutboxState.SENDING, 2L)));
        List<String> sent = new ArrayList<>();
        when(session.sendMessage(anyString(), any())).thenAnswer(call -> {
            sent.add(((SendMessageRequest) call.getArgument(1)).getContent()); return true;
        });
        await(repository.retry("cm-failed"));
        assertEquals(List.of("older pending", "retry me"), sent);
        assertTrue(outbox.get("cm-failed").getCreatedAt() > outbox.get("cm-waiting").getCreatedAt());
    }

    @Test public void incomingPeerMessageBumpsUnreadWhenChatIsClosed() {
        await(conversationDao.upsert(conversation(0, null)));
        events.onNext(new ChatEvent.MessageCreated(dto("m-7", null, "u-other", 7L), CONVERSATION_ID, null));
        assertEquals(CONVERSATION_ID + ":1", unreadBumps.get(0));
        assertEquals(1, conversations.get(CONVERSATION_ID).getUnreadCount());
        assertEquals(7L, conversations.get(CONVERSATION_ID).getLastMessageSeq());
    }

    @Test public void peerReadWatermarkAdvancesButOwnEchoDoesNot() {
        await(conversationDao.upsert(conversation(0, null)));
        events.onNext(new ChatEvent.MessageRead(CONVERSATION_ID, "me-1", 99L));
        assertEquals(0L, conversations.get(CONVERSATION_ID).getPeerReadSeq());
        events.onNext(new ChatEvent.MessageRead(CONVERSATION_ID, "u-other", 42L));
        assertEquals(42L, conversations.get(CONVERSATION_ID).getPeerReadSeq());
        events.onNext(new ChatEvent.MessageRead(CONVERSATION_ID, "u-other", 10L));
        assertEquals(42L, conversations.get(CONVERSATION_ID).getPeerReadSeq());
    }

    @Test public void messagesFlowMergesSentAndPendingRows() {
        await(messageDao.upsert(stored("m-1", 1L)));
        TestSubscriber<List<ChatMessage>> subscriber = repository.messagesFlow(CONVERSATION_ID).test();
        assertEquals(1, subscriber.values().get(0).size());
        await(repository.send(CONVERSATION_ID, "queued"));
        List<ChatMessage> merged = subscriber.values().get(subscriber.values().size() - 1);
        assertEquals(2, merged.size());
        assertEquals(DeliveryStatus.SENT, merged.get(0).getStatus());
        assertEquals(DeliveryStatus.SENDING, merged.get(1).getStatus());
        assertTrue(merged.get(1).getMine());
        subscriber.cancel();
    }

    @Test public void currentUserIdDecodesJwtSubject() {
        assertEquals("me-1", awaitMaybe(repository.currentUserId()));
        assertTrue(await(repository.isMine("me-1")));
        assertTrue(!await(repository.isMine("u-other")));
    }

    @Test public void flushOutboxDispatchesSendingRowsOldestFirst() {
        await(outboxDao.insert(outbox("cm-a", "first", OutboxState.SENDING, 1L)));
        await(outboxDao.insert(outbox("cm-b", "second", OutboxState.SENDING, 2L)));
        await(outboxDao.insert(outbox("cm-c", "failed", OutboxState.FAILED, 3L)));
        List<String> sent = new ArrayList<>();
        when(session.sendMessage(anyString(), any())).thenAnswer(call -> {
            sent.add(((SendMessageRequest) call.getArgument(1)).getContent()); return true;
        });
        await(repository.flushOutbox());
        assertEquals(List.of("first", "second"), sent);
    }

    @Test public void sendWithAttachmentsPersistsAndDispatchesAttachmentIds() {
        AttachmentDto attachment = new AttachmentDto("att-1", "pic.png", "image/png");
        List<SendMessageRequest> captured = new ArrayList<>();
        when(session.sendMessage(anyString(), any())).thenAnswer(call -> {
            captured.add(call.getArgument(1)); return true;
        });
        await(repository.send(CONVERSATION_ID, "pic.png", null, "IMAGE", List.of(attachment)));
        OutboxEntity row = outbox.values().iterator().next();
        assertEquals("IMAGE", row.getType());
        assertTrue(!row.getAttachmentsJson().isBlank());
        assertEquals(List.of("att-1"), captured.get(0).getAttachmentIds());
        TestSubscriber<List<ChatMessage>> subscriber = repository.messagesFlow(CONVERSATION_ID).test();
        ChatMessage pending = subscriber.values().get(0).get(0);
        assertEquals(DeliveryStatus.SENDING, pending.getStatus());
        assertEquals(List.of(attachment), pending.getAttachments());
        subscriber.cancel();
    }

    @Test public void textSendsCarryNoAttachmentIds() {
        List<SendMessageRequest> captured = new ArrayList<>();
        when(session.sendMessage(anyString(), any())).thenAnswer(call -> {
            captured.add(call.getArgument(1)); return true;
        });
        await(repository.send(CONVERSATION_ID, "plain"));
        assertEquals(List.of(), captured.get(0).getAttachmentIds());
        assertEquals("", outbox.values().iterator().next().getAttachmentsJson());
    }

    @Test public void resyncForwardPagesUntilContiguous() {
        for (int seq = 1; seq <= 100; seq++) await(messageDao.upsert(stored("m-" + seq, seq)));
        when(messageApi.getMessages(CONVERSATION_ID, null, 100L, MessageApi.DEFAULT_PAGE_SIZE))
                .thenReturn(responseCall(Response.success(new MessagePageDto(List.of(dto("m-101", null, "u-other", 101),
                        dto("m-102", null, "u-other", 102), dto("m-103", null, "u-other", 103)), true))));
        when(messageApi.getMessages(CONVERSATION_ID, null, 103L, MessageApi.DEFAULT_PAGE_SIZE))
                .thenReturn(responseCall(Response.success(new MessagePageDto(List.of(dto("m-104", null, "u-other", 104),
                        dto("m-105", null, "u-other", 105)), false))));
        await(repository.resyncForward(CONVERSATION_ID));
        assertEquals(105, messages.size());
        verify(messageApi).getMessages(CONVERSATION_ID, null, 103L, MessageApi.DEFAULT_PAGE_SIZE);
    }

    @Test public void openingConversationForwardFillsMissedMessages() {
        for (int seq = 1; seq <= 3; seq++) await(messageDao.upsert(stored("m-" + seq, seq)));
        when(messageApi.getMessages(CONVERSATION_ID, null, 3L, MessageApi.DEFAULT_PAGE_SIZE))
                .thenReturn(responseCall(Response.success(new MessagePageDto(List.of(
                        dto("m-4", null, "u-other", 4), dto("m-5", null, "u-other", 5)), false))));
        when(messageApi.getPins(CONVERSATION_ID)).thenReturn(responseCall(Response.success(body("[]"))));
        await(repository.openConversation(CONVERSATION_ID));
        assertEquals(5, messages.size());
        verify(messageApi).getMessages(CONVERSATION_ID, null, 3L, MessageApi.DEFAULT_PAGE_SIZE);
        verify(messageApi).markRead(org.mockito.ArgumentMatchers.eq(CONVERSATION_ID), any());
    }

    @Test public void sendReadClampsToConversationLastMessageSeq() {
        await(conversationDao.upsert(conversation(4, "hi")));
        await(repository.sendRead(CONVERSATION_ID, 999L));
        verify(messageApi).markRead(eqConversation(), org.mockito.ArgumentMatchers.argThat(
                request -> request.getUpToSeq() == 4L));
        verify(session).sendReadAck(CONVERSATION_ID, 4L);
    }

    @Test public void sendReadClampsToStoredMessageMaxWhenConversationRowIsMissing() {
        for (int seq = 1; seq <= 6; seq++) await(messageDao.upsert(stored("m-" + seq, seq)));
        await(repository.sendRead(CONVERSATION_ID, 500L));
        verify(messageApi).markRead(eqConversation(), org.mockito.ArgumentMatchers.argThat(
                request -> request.getUpToSeq() == 6L));
    }

    @Test public void sendReadUsesConversationWatermarkAheadOfStoredMessages() {
        await(conversationDao.upsert(conversation(10, "hi")));
        await(messageDao.upsert(stored("m-1", 3L)));
        await(repository.sendRead(CONVERSATION_ID, 999L));
        verify(messageApi).markRead(eqConversation(), org.mockito.ArgumentMatchers.argThat(
                request -> request.getUpToSeq() == 10L));
    }

    @Test public void sendReadSkipsWhenClampedValueDoesNotAdvance() {
        await(conversationDao.upsert(conversation(5, "hi")));
        await(repository.sendRead(CONVERSATION_ID, 999L));
        await(repository.sendRead(CONVERSATION_ID, 999L));
        verify(messageApi, times(1)).markRead(eqConversation(), org.mockito.ArgumentMatchers.argThat(
                request -> request.getUpToSeq() == 5L));
    }

    @Test public void sendReadNeverRegressesBelowAcknowledgedSequence() {
        await(conversationDao.upsert(conversation(9, "hi")));
        await(repository.sendRead(CONVERSATION_ID, 9L));
        await(repository.sendRead(CONVERSATION_ID, 4L));
        verify(messageApi, times(1)).markRead(eqConversation(), org.mockito.ArgumentMatchers.argThat(
                request -> request.getUpToSeq() == 9L));
    }

    @Test public void sendReadSkipsWhenConversationHasNoMessages() {
        await(conversationDao.upsert(conversation(0, null)));
        await(repository.sendRead(CONVERSATION_ID, 12L));
        verify(messageApi, org.mockito.Mockito.never()).markRead(anyString(), any());
    }

    @Test public void sendReadIgnoresNonPositiveSequence() {
        await(conversationDao.upsert(conversation(5, "hi")));
        await(repository.sendRead(CONVERSATION_ID, 0L));
        await(repository.sendRead(CONVERSATION_ID, -3L));
        verify(messageApi, org.mockito.Mockito.never()).markRead(anyString(), any());
    }

    @Test public void reactionUpdatedReplacesStoredSummary() {
        events.onNext(new ChatEvent.ReactionUpdated(CONVERSATION_ID, "m-1", List.of(
                new ReactionSummaryDto("👍", 2, List.of("me-1", "u-2")),
                new ReactionSummaryDto("❤️", 1, List.of("u-3")))));
        MessageReactionEntity like = reactions.get(new ReactionKey("m-1", "👍"));
        assertEquals(2, like.getCount());
        assertEquals(true, like.getReactedByMe());
        MessageReactionEntity heart = reactions.get(new ReactionKey("m-1", "❤️"));
        assertEquals(1, heart.getCount());
        assertEquals(false, heart.getReactedByMe());
        events.onNext(new ChatEvent.ReactionUpdated(CONVERSATION_ID, "m-1",
                List.of(new ReactionSummaryDto("🔥", 1))));
        assertEquals(Set.of("🔥"), reactions.keySet().stream().filter(key -> key.messageId.equals("m-1"))
                .map(key -> key.emoji).collect(java.util.stream.Collectors.toSet()));
    }

    @Test public void pinUpdatedPinsThenUnpinsInPinsFlow() {
        await(messageDao.upsert(stored("m-9", 9L)));
        TestSubscriber<List<ChatMessage>> subscriber = repository.pinsFlow(CONVERSATION_ID).test();
        assertTrue(subscriber.values().get(0).isEmpty());
        events.onNext(new ChatEvent.PinUpdated(CONVERSATION_ID, "m-9", true));
        List<ChatMessage> pinned = subscriber.values().get(subscriber.values().size() - 1);
        assertEquals(List.of("m-9"), pinned.stream().map(ChatMessage::getId).toList());
        assertEquals("m-9", pinned.get(0).getId());
        events.onNext(new ChatEvent.PinUpdated(CONVERSATION_ID, "m-9", false));
        assertTrue(subscriber.values().get(subscriber.values().size() - 1).isEmpty());
        subscriber.cancel();
    }

    @Test public void historyRefreshStoresEmbeddedReactionsAndReplyPreview() {
        MessageDto reply = new MessageDto("m-2", 2L, CONVERSATION_ID, "u-other", "TEXT", "answer",
                new ReplyRefDto("m-1", "Lan", "question"), List.of(new ReactionSummaryDto("👍", 2, false)),
                "2026-11-03T09:12:04Z");
        when(messageApi.getMessages(eqConversation(), nullable(Long.class), nullable(Long.class), anyInt()))
                .thenReturn(responseCall(Response.success(new MessagePageDto(List.of(reply), false))));
        await(repository.refreshHistory(CONVERSATION_ID));
        assertEquals("Lan", messages.get("m-2").getReplyToSenderName());
        assertEquals("question", messages.get("m-2").getReplyToSnippet());
        MessageReactionEntity chip = reactions.get(new ReactionKey("m-2", "👍"));
        assertEquals(2, chip.getCount());
        assertEquals(false, chip.getReactedByMe());
    }

    @Test public void historyDistinguishesAbsentFromEmptyReactions() {
        await(messageDao.upsert(stored("m-1", 1L)));
        MessageReactionEntity known = new MessageReactionEntity("m-1", CONVERSATION_ID, "👍", 1, true);
        await(reactionDao.upsertAll(List.of(known)));
        when(messageApi.getMessages(anyString(), nullable(Long.class), nullable(Long.class), anyInt()))
                .thenReturn(responseCall(Response.success(new MessagePageDto(List.of(dto("m-1", null, "u-other", 1)), false))))
                .thenReturn(responseCall(Response.success(new MessagePageDto(List.of(
                        dto("m-1", null, "u-other", 1).withReactions(List.of())), false))));
        await(repository.refreshHistory(CONVERSATION_ID));
        assertEquals(List.of(known), new ArrayList<>(reactions.values()));
        await(repository.refreshHistory(CONVERSATION_ID));
        assertTrue(reactions.isEmpty());
    }

    @Test public void refreshPinsReplacesConversationPinSet() {
        await(pinDao.upsert(new PinnedMessageEntity(CONVERSATION_ID, "stale", 1L)));
        when(messageApi.getPins(CONVERSATION_ID)).thenReturn(responseCall(Response.success(body("[\"m-1\",\"m-2\"]"))));
        await(repository.refreshPins(CONVERSATION_ID));
        assertEquals(Set.of("m-1", "m-2"), pins.keySet().stream()
                .filter(key -> CONVERSATION_ID.equals(key.conversationId)).map(key -> key.messageId)
                .collect(java.util.stream.Collectors.toSet()));
    }

    @Test public void editOptimisticallyAppliesAuthoritativeServerRow() {
        await(messageDao.upsert(own("m-1", 1L, "old")));
        List<EditMessageRequest> captured = new ArrayList<>();
        when(messageApi.editMessage(org.mockito.ArgumentMatchers.eq(CONVERSATION_ID),
                org.mockito.ArgumentMatchers.eq("m-1"), any())).thenAnswer(call -> {
            captured.add(call.getArgument(2));
            return responseCall(Response.success(dto("m-1", null, "me-1", 1L).withContent(
                    "new", "2026-11-03T10:00:00Z")));
        });
        Result<Unit> result = await(repository.editMessage(CONVERSATION_ID, "m-1", "  new  "));
        assertTrue(result instanceof Result.Success<?>);
        assertEquals("new", captured.get(0).getContent());
        assertEquals("new", messages.get("m-1").getContent());
        assertEquals(TimeAndJwt.isoToEpochMsOrNull("2026-11-03T10:00:00Z"), messages.get("m-1").getEditedAtEpochMs());
    }

    @Test public void failedEditRestoresPriorContentAndTimestamp() {
        await(messageDao.upsert(own("m-1", 1L, "old")));
        when(messageApi.editMessage(anyString(), anyString(), any())).thenReturn(responseCall(Response.error(403, body(""))));
        Result<Unit> result = await(repository.editMessage(CONVERSATION_ID, "m-1", "new"));
        assertTrue(result instanceof Result.Failure<?>);
        assertEquals("old", messages.get("m-1").getContent());
        assertNull(messages.get("m-1").getEditedAtEpochMs());
    }

    @Test public void editResponseCannotRestorePreviewAfterDeleteEvent() {
        await(messageDao.upsert(own("m-1", 1L, "old")));
        await(conversationDao.upsert(conversation(1L, "old")));
        when(messageApi.editMessage(org.mockito.ArgumentMatchers.eq(CONVERSATION_ID),
                org.mockito.ArgumentMatchers.eq("m-1"), any())).thenAnswer(call -> {
            events.onNext(new ChatEvent.MessageDeleted(CONVERSATION_ID, "m-1", "2026-11-03T11:00:00Z"));
            return responseCall(Response.success(dto("m-1", null, "me-1", 1L).withContent(
                    "edited", "2026-11-03T10:00:00Z")));
        });
        await(repository.editMessage(CONVERSATION_ID, "m-1", "edited"));
        assertTrue(messages.get("m-1").getDeletedAtEpochMs() != null);
        assertNull(conversations.get(CONVERSATION_ID).getLastMessageContent());
        assertEquals(true, conversations.get(CONVERSATION_ID).getLastMessageDeleted());
    }

    @Test public void disposeDuringEditRollsBackOptimisticRow() {
        await(messageDao.upsert(own("m-1", 1L, "old")));
        RxJavaPlugins.setIoSchedulerHandler(null);
        CountDownLatch networkStarted = new CountDownLatch(1);
        CountDownLatch releaseNetwork = new CountDownLatch(1);
        when(messageApi.editMessage(anyString(), anyString(), any())).thenReturn(blockingCall(networkStarted, releaseNetwork));
        TestObserver<Result<Unit>> observer = repository.editMessage(CONVERSATION_ID, "m-1", "edited").test();
        awaitLatch(networkStarted);
        observer.dispose();
        releaseNetwork.countDown();
        awaitCondition(() -> "old".equals(messages.get("m-1").getContent()));
        assertEquals("old", messages.get("m-1").getContent());
        assertNull(messages.get("m-1").getEditedAtEpochMs());
    }

    @Test public void editIgnoresBlankIdenticalAndNonOwnedContent() {
        await(messageDao.upsert(own("m-1", 1L, "same")));
        await(messageDao.upsert(stored("m-2", 2L)));
        assertTrue(await(repository.editMessage(CONVERSATION_ID, "m-1", "   ")) instanceof Result.Failure<?>);
        assertTrue(await(repository.editMessage(CONVERSATION_ID, "m-1", "same")) instanceof Result.Success<?>);
        assertTrue(await(repository.editMessage(CONVERSATION_ID, "m-2", "mine now")) instanceof Result.Failure<?>);
        verify(messageApi, times(0)).editMessage(anyString(), anyString(), any());
    }

    @Test public void messageUpdatedAppliesOnceAndDuplicateBroadcastIsIdempotent() {
        await(messageDao.upsert(own("m-1", 1L, "old")));
        ChatEvent.MessageUpdated event = new ChatEvent.MessageUpdated(dto("m-1", null, "me-1", 1L)
                .withContent("edited", "2026-11-03T10:00:00Z"), CONVERSATION_ID, null);
        events.onNext(event); events.onNext(event);
        assertEquals("edited", messages.get("m-1").getContent());
        assertEquals(TimeAndJwt.isoToEpochMsOrNull("2026-11-03T10:00:00Z"), messages.get("m-1").getEditedAtEpochMs());
    }

    @Test public void messageDeletedTombstonesAndDropsReactionsAndPin() {
        await(messageDao.upsert(own("m-1", 1L, "bye")));
        await(reactionDao.upsertAll(List.of(new MessageReactionEntity("m-1", CONVERSATION_ID, "👍", 1, true))));
        await(pinDao.upsert(new PinnedMessageEntity(CONVERSATION_ID, "m-1", 1L)));
        events.onNext(new ChatEvent.MessageDeleted(CONVERSATION_ID, "m-1", "2026-11-03T11:00:00Z"));
        MessageEntity row = messages.get("m-1");
        assertEquals("", row.getContent()); assertNull(row.getAttachmentsJson());
        assertEquals(TimeAndJwt.isoToEpochMsOrNull("2026-11-03T11:00:00Z"), row.getDeletedAtEpochMs());
        assertTrue(reactions.isEmpty()); assertTrue(pins.isEmpty());
        events.onNext(new ChatEvent.MessageDeleted(CONVERSATION_ID, "m-1", "2026-11-04T11:00:00Z"));
        assertEquals(TimeAndJwt.isoToEpochMsOrNull("2026-11-03T11:00:00Z"), messages.get("m-1").getDeletedAtEpochMs());
    }

    @Test public void historyTombstoneClearsRelatedStateAndInboxPreview() {
        await(messageDao.upsert(own("m-1", 1L, "bye")));
        await(messageDao.upsert(copy(stored("m-2", 2L), "m-1", null, null, null, "bye")));
        await(reactionDao.upsertAll(List.of(new MessageReactionEntity("m-1", CONVERSATION_ID, "👍", 1, true))));
        await(pinDao.upsert(new PinnedMessageEntity(CONVERSATION_ID, "m-1", 1L)));
        await(conversationDao.upsert(conversation(1L, "bye")));
        when(messageApi.getMessages(anyString(), nullable(Long.class), nullable(Long.class), anyInt()))
                .thenReturn(responseCall(Response.success(new MessagePageDto(List.of(dto("m-1", null, "me-1", 1L)
                        .withDeletedAt("2026-11-03T11:00:00Z", List.of(new ReactionSummaryDto("👍", 1, true)))), false))));
        await(repository.refreshHistory(CONVERSATION_ID));
        MessageEntity row = messages.get("m-1");
        assertEquals("", row.getContent()); assertNull(row.getAttachmentsJson()); assertTrue(row.getDeletedAtEpochMs() != null);
        assertTrue(reactions.isEmpty()); assertTrue(pins.isEmpty());
        assertNull(messages.get("m-2").getReplyToSnippet());
        assertNull(conversations.get(CONVERSATION_ID).getLastMessageContent());
        assertEquals(true, conversations.get(CONVERSATION_ID).getLastMessageDeleted());
    }

    @Test public void deleteOptimisticallyTombstonesAndClearsRelatedRows() {
        await(messageDao.upsert(own("m-1", 1L, "bye")));
        await(messageDao.upsert(copy(stored("m-2", 2L), "m-1", null, null, null, "bye")));
        await(reactionDao.upsertAll(List.of(new MessageReactionEntity("m-1", CONVERSATION_ID, "👍", 1, true))));
        await(pinDao.upsert(new PinnedMessageEntity(CONVERSATION_ID, "m-1", 1L)));
        when(messageApi.deleteMessage(CONVERSATION_ID, "m-1")).thenReturn(responseCall(Response.success(204, null)));
        assertTrue(await(repository.deleteMessage(CONVERSATION_ID, "m-1")) instanceof Result.Success<?>);
        MessageEntity row = messages.get("m-1");
        assertEquals("", row.getContent()); assertTrue(row.getDeletedAtEpochMs() != null);
        assertTrue(reactions.isEmpty()); assertTrue(pins.isEmpty());
        assertNull(messages.get("m-2").getReplyToSnippet());
    }

    @Test public void failedDeleteRestoresRowReactionsAndPin() {
        await(messageDao.upsert(own("m-1", 1L, "bye")));
        await(reactionDao.upsertAll(List.of(new MessageReactionEntity("m-1", CONVERSATION_ID, "👍", 1, true))));
        await(pinDao.upsert(new PinnedMessageEntity(CONVERSATION_ID, "m-1", 1L)));
        when(messageApi.deleteMessage(anyString(), anyString())).thenReturn(responseCall(Response.error(403, body(""))));
        assertTrue(await(repository.deleteMessage(CONVERSATION_ID, "m-1")) instanceof Result.Failure<?>);
        assertEquals("bye", messages.get("m-1").getContent()); assertNull(messages.get("m-1").getDeletedAtEpochMs());
        assertEquals(1, reactions.size()); assertEquals(1, pins.size());
    }

    @Test public void concurrentDeleteEventWinsOverFailedDeleteRollback() {
        await(messageDao.upsert(own("m-1", 1L, "bye")));
        await(reactionDao.upsertAll(List.of(new MessageReactionEntity("m-1", CONVERSATION_ID, "👍", 1, true))));
        await(pinDao.upsert(new PinnedMessageEntity(CONVERSATION_ID, "m-1", 1L)));
        await(conversationDao.upsert(conversation(1L, "bye")));
        when(messageApi.deleteMessage(CONVERSATION_ID, "m-1")).thenAnswer(call -> {
            events.onNext(new ChatEvent.MessageDeleted(CONVERSATION_ID, "m-1", "2026-11-03T11:00:00Z"));
            return responseCall(Response.error(500, body("")));
        });
        assertTrue(await(repository.deleteMessage(CONVERSATION_ID, "m-1")) instanceof Result.Failure<?>);
        assertTrue(messages.get("m-1").getDeletedAtEpochMs() != null);
        assertTrue(reactions.isEmpty()); assertTrue(pins.isEmpty());
        assertNull(conversations.get(CONVERSATION_ID).getLastMessageContent());
        assertEquals(true, conversations.get(CONVERSATION_ID).getLastMessageDeleted());
    }

    @Test public void disposeDuringDeleteRestoresOptimisticState() {
        await(messageDao.upsert(own("m-1", 1L, "bye")));
        await(reactionDao.upsertAll(List.of(new MessageReactionEntity("m-1", CONVERSATION_ID, "👍", 1, true))));
        await(pinDao.upsert(new PinnedMessageEntity(CONVERSATION_ID, "m-1", 1L)));
        await(conversationDao.upsert(conversation(1L, "bye")));
        RxJavaPlugins.setIoSchedulerHandler(null);
        CountDownLatch networkStarted = new CountDownLatch(1);
        CountDownLatch releaseNetwork = new CountDownLatch(1);
        Call<Void> pending = blockingCall(networkStarted, releaseNetwork);
        when(messageApi.deleteMessage(anyString(), anyString())).thenReturn(pending);
        TestObserver<Result<Unit>> observer = repository.deleteMessage(CONVERSATION_ID, "m-1").test();
        awaitLatch(networkStarted);
        observer.dispose();
        releaseNetwork.countDown();
        awaitCondition(() -> "bye".equals(messages.get("m-1").getContent())
                && reactions.size() == 1
                && pins.size() == 1
                && !conversations.get(CONVERSATION_ID).getLastMessageDeleted());
        assertEquals("bye", messages.get("m-1").getContent()); assertNull(messages.get("m-1").getDeletedAtEpochMs());
        assertEquals(1, reactions.size()); assertEquals(1, pins.size());
        assertEquals("bye", conversations.get(CONVERSATION_ID).getLastMessageContent());
        assertEquals(false, conversations.get(CONVERSATION_ID).getLastMessageDeleted());
    }

    @Test public void deletingInboxLastMessageSetsNeutralSnippetFlag() {
        await(messageDao.upsert(own("m-1", 1L, "bye")));
        await(conversationDao.upsert(conversation(1L, "bye")));
        when(messageApi.deleteMessage(anyString(), anyString())).thenReturn(responseCall(Response.success(204, null)));
        await(repository.deleteMessage(CONVERSATION_ID, "m-1"));
        ConversationEntity row = conversations.get(CONVERSATION_ID);
        assertEquals(true, row.getLastMessageDeleted()); assertNull(row.getLastMessageContent());
    }

    @Test public void deletingAlreadyDeletedMessageIsIdempotent() {
        await(messageDao.upsert(copy(own("m-1", 1L, ""), null, null, null, 5L, null)));
        assertTrue(await(repository.deleteMessage(CONVERSATION_ID, "m-1")) instanceof Result.Success<?>);
        verify(messageApi, times(0)).deleteMessage(anyString(), anyString());
    }

    @Test public void searchWritesThroughAndHydratesPage() {
        when(messageApi.searchMessages(CONVERSATION_ID, "hi", MessageApi.DEFAULT_SEARCH_LIMIT, null))
                .thenReturn(responseCall(Response.success(new MessagePageDto(
                        List.of(dto("m-9", null, "u-other", 9L).withContent("hi there")), 9L, true))));
        Result<MessageSearchResult> result = await(repository.searchMessages(CONVERSATION_ID, "  hi  "));
        MessageSearchResult page = ((Result.Success<MessageSearchResult>) result).getValue();
        assertEquals(List.of("m-9"), page.getItems().stream().map(ChatMessage::getId).toList());
        assertEquals("hi there", page.getItems().get(0).getContent()); assertTrue(page.getHasMore());
        assertEquals(Long.valueOf(9L), page.getNextBeforeSeq()); assertEquals("hi there", messages.get("m-9").getContent());
    }

    @Test public void shortSearchDoesNotCallApi() {
        Result<MessageSearchResult> result = await(repository.searchMessages(CONVERSATION_ID, " h "));
        assertTrue(result instanceof Result.Success<?>);
        assertTrue(((Result.Success<MessageSearchResult>) result).getValue().getItems().isEmpty());
        verify(messageApi, times(0)).searchMessages(anyString(), anyString(), anyInt(), nullable(Long.class));
    }

    @Test public void timestampConversionHandlesFractionalOffsetsAndInvalidInput() {
        long expected = Instant.parse("2026-11-03T10:00:00.123456789Z").toEpochMilli();
        assertEquals(Long.valueOf(expected), TimeAndJwt.isoToEpochMsOrNull("2026-11-03T12:00:00.123456789+02:00"));
        assertNull(TimeAndJwt.isoToEpochMsOrNull("not-a-timestamp"));
    }

    @Test public void loadUntilLoadedReturnsImmediatelyWhenPresent() {
        await(messageDao.upsert(stored("m-9", 9L)));
        assertEquals(MessageJump.Found, await(repository.loadUntilLoaded(CONVERSATION_ID, "m-9")));
        verify(messageApi, times(0)).getMessages(anyString(), nullable(Long.class), nullable(Long.class), anyInt());
    }

    @Test public void loadUntilLoadedPagesBackwardUntilTargetIsStored() {
        await(messageDao.upsert(stored("m-90", 90L)));
        when(messageApi.getMessages(anyString(), nullable(Long.class), nullable(Long.class), anyInt()))
                .thenReturn(responseCall(Response.success(page(true, storedDto("m-45", 45L)))))
                .thenReturn(responseCall(Response.success(page(true, storedDto("m-10", 10L)))))
                .thenReturn(responseCall(Response.success(page(true, storedDto("m-2", 2L)))));
        assertEquals(MessageJump.Found, await(repository.loadUntilLoaded(CONVERSATION_ID, "m-2")));
        assertTrue(messages.containsKey("m-2"));
        verify(messageApi, times(3)).getMessages(anyString(), nullable(Long.class), nullable(Long.class), anyInt());
        verify(messageApi).getMessages(CONVERSATION_ID, 45L, null, MessageApi.DEFAULT_PAGE_SIZE);
        verify(messageApi).getMessages(CONVERSATION_ID, 10L, null, MessageApi.DEFAULT_PAGE_SIZE);
    }

    @Test public void loadUntilLoadedStopsAtPageBudget() {
        await(messageDao.upsert(stored("m-90", 90L)));
        when(messageApi.getMessages(anyString(), nullable(Long.class), nullable(Long.class), anyInt()))
                .thenReturn(responseCall(Response.success(page(true, storedDto("m-x", 1L)))));
        assertEquals(MessageJump.NotFound, await(repository.loadUntilLoaded(CONVERSATION_ID, "m-gone", 3)));
        verify(messageApi, times(3)).getMessages(anyString(), nullable(Long.class), nullable(Long.class), anyInt());
    }

    @Test public void loadUntilLoadedStopsWhenServerHasNoOlderHistory() {
        await(messageDao.upsert(stored("m-5", 5L)));
        when(messageApi.getMessages(anyString(), nullable(Long.class), nullable(Long.class), anyInt()))
                .thenReturn(responseCall(Response.success(page(false))));
        assertEquals(MessageJump.NotFound, await(repository.loadUntilLoaded(CONVERSATION_ID, "m-old")));
        verify(messageApi, times(1)).getMessages(anyString(), nullable(Long.class), nullable(Long.class), anyInt());
    }

    @Test public void loadUntilLoadedSurfacesPageFailure() {
        await(messageDao.upsert(stored("m-5", 5L)));
        when(messageApi.getMessages(anyString(), nullable(Long.class), nullable(Long.class), anyInt()))
                .thenReturn(failedCall(new IOException("down")));
        MessageJump jump = await(repository.loadUntilLoaded(CONVERSATION_ID, "m-old"));
        assertTrue(jump instanceof MessageJump.Failed);
        assertTrue(((MessageJump.Failed) jump).getError() instanceof ApiError.Network);
    }

    private void syncOutbox() {
        outboxChanges.onNext(outbox.values().stream()
                .sorted(java.util.Comparator.comparingLong(OutboxEntity::getCreatedAt)).toList());
    }

    private static <T> Call<T> responseCall(Response<T> response) { return responseCall(response, () -> {}); }
    private static <T> Call<T> responseCall(Response<T> response, Runnable beforeExecute) {
        return new StubCall<>(() -> {
            beforeExecute.run();
            return response;
        });
    }
    private static <T> Call<T> failedCall(Throwable error) {
        return new StubCall<>(() -> {
            if (error instanceof IOException io) throw io;
            if (error instanceof RuntimeException runtime) throw runtime;
            throw new RuntimeException(error);
        });
    }
    private static <T> Call<T> blockingCall(CountDownLatch started, CountDownLatch release) {
        return new StubCall<>(() -> {
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            throw new IOException("disposed request");
        });
    }

    @FunctionalInterface
    private interface CallAction<T> {
        Response<T> execute() throws IOException;
    }

    private static final class StubCall<T> implements Call<T> {
        private final CallAction<T> action;
        private volatile boolean executed;
        private volatile boolean canceled;

        StubCall(CallAction<T> action) {
            this.action = action;
        }

        @Override public Response<T> execute() throws IOException {
            executed = true;
            return action.execute();
        }
        @Override public void enqueue(retrofit2.Callback<T> callback) { throw new UnsupportedOperationException(); }
        @Override public boolean isExecuted() { return executed; }
        @Override public void cancel() { canceled = true; }
        @Override public boolean isCanceled() { return canceled; }
        @Override public Call<T> clone() { return new StubCall<>(action); }
        @Override public okhttp3.Request request() {
            return new okhttp3.Request.Builder().url("http://localhost/").build();
        }
        @Override public okio.Timeout timeout() { return okio.Timeout.NONE; }
    }
    private static void awaitLatch(CountDownLatch latch) {
        try { assertTrue("timed out waiting for request", latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }
    private static void awaitCondition(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            try { Thread.sleep(10L); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
        }
        assertTrue("timed out waiting for rollback", condition.getAsBoolean());
    }
    private static ResponseBody body(String value) { return ResponseBody.create(null, value); }
    private static String jwtFor(String userId) {
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"sub\":\"" + userId + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return "header." + payload + ".signature";
    }
    private static OutboxEntity outbox(String id, String content, String state, long createdAt) {
        return new OutboxEntity(id, CONVERSATION_ID, "TEXT", content, null, state, createdAt, "");
    }
    private static MessageDto dto(String id, String clientId, String senderId, long seq) {
        return new MessageDto(id, seq, CONVERSATION_ID, senderId, clientId, "TEXT", "hi", "2026-11-03T09:12:04Z");
    }
    private static MessageDto storedDto(String id, long seq) {
        return new MessageDto(id, seq, CONVERSATION_ID, "u-other", "TEXT", id, "2026-11-03T09:12:04Z");
    }
    private static MessagePageDto page(boolean hasMore, MessageDto... rows) {
        return new MessagePageDto(List.of(rows), hasMore);
    }
    private static MessageEntity stored(String id, long seq) {
        return new MessageEntity(id, CONVERSATION_ID, seq, "u-other", "Lan", "TEXT", id,
                null, null, null, null, seq, null, null, null);
    }
    private static MessageEntity own(String id, long seq, String content) {
        return new MessageEntity(id, CONVERSATION_ID, seq, "me-1", "Me", "TEXT", content,
                null, null, null, null, seq, null, null, null);
    }
    private static MessageEntity copy(MessageEntity source, String replyToId, String attachmentsJson,
            Long editedAt, Long deletedAt, String replySnippet) {
        return new MessageEntity(source.getId(), source.getConversationId(), source.getSeq(), source.getSenderId(),
                source.getSenderDisplayName(), source.getType(), source.getContent(), source.getClientMessageId(),
                replyToId != null ? replyToId : source.getReplyToId(), source.getReplyToSenderName(), replySnippet,
                source.getCreatedAt(), attachmentsJson, editedAt, deletedAt);
    }
    private static ConversationEntity conversation(long seq, String content) {
        return new ConversationEntity(CONVERSATION_ID, "DIRECT", null, seq, content, "Me", seq, 0,
                "Lan", null, null, 0L, null, null, false);
    }
    private static ConversationEntity conversationCopy(ConversationEntity row, String content,
            long peerRead, int unread, boolean deleted) {
        return new ConversationEntity(row.getId(), row.getType(), row.getTitle(), row.getLastMessageSeq(),
                content, row.getLastMessageSenderName(), row.getLastMessageAt(), unread, row.getOtherUserName(),
                row.getOtherUserAvatar(), row.getAvatarUrl(), peerRead, row.getMyRole(), row.getOtherUserId(), deleted);
    }
    private static ConversationEntity conversationLast(ConversationEntity row, long seq, String content,
            String sender, Long at, boolean deleted) {
        return new ConversationEntity(row.getId(), row.getType(), row.getTitle(), seq, content, sender, at,
                row.getUnreadCount(), row.getOtherUserName(), row.getOtherUserAvatar(), row.getAvatarUrl(),
                row.getPeerReadSeq(), row.getMyRole(), row.getOtherUserId(), deleted);
    }
    private static String eqConversation() { return org.mockito.ArgumentMatchers.eq(CONVERSATION_ID); }

    private static <T> T await(Single<T> source) {
        TestObserver<T> observer = source.test();
        observer.awaitDone(5, TimeUnit.SECONDS).assertNoErrors().assertComplete();
        return observer.values().get(0);
    }
    private static void await(Completable source) {
        TestObserver<Void> observer = source.test();
        observer.awaitDone(5, TimeUnit.SECONDS).assertNoErrors().assertComplete();
    }
    private static <T> T awaitMaybe(Maybe<T> source) {
        TestObserver<T> observer = source.test();
        observer.awaitDone(5, TimeUnit.SECONDS).assertNoErrors().assertComplete();
        return observer.values().isEmpty() ? null : observer.values().get(0);
    }

    private record ReactionKey(String messageId, String emoji) {}
    private record PinKey(String conversationId, String messageId) {}
}
