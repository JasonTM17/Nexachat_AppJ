package com.nexachat.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexachat.core.database.AppDatabase;
import com.nexachat.core.database.ConversationDao;
import com.nexachat.core.database.ConversationWatermark;
import com.nexachat.core.database.MessageDao;
import com.nexachat.core.database.MessageEntity;
import com.nexachat.core.database.OutboxDao;
import com.nexachat.core.database.PinDao;
import com.nexachat.core.database.ReactionDao;
import com.nexachat.core.network.MessageApi;
import com.nexachat.core.network.SessionEvents;
import com.nexachat.core.network.TokenRefresher;
import com.nexachat.core.network.dto.MessageDto;
import com.nexachat.core.network.dto.ReactionSummaryDto;
import com.nexachat.core.network.dto.ReactionUpdateDto;
import com.nexachat.core.network.dto.SyncRequest;
import com.nexachat.core.network.dto.SyncResponse;
import com.nexachat.core.storage.TokenStore;
import com.nexachat.core.storage.Tokens;
import com.squareup.moshi.Moshi;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.observers.TestObserver;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.MediaType;
import okhttp3.ResponseBody;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import retrofit2.Call;
import retrofit2.Response;

public final class SyncEngineTest {

    private AppDatabase appDatabase;
    private MessageApi messageApi;
    private MessageDao messageDao;
    private ConversationDao conversationDao;
    private ReactionDao reactionDao;
    private PinDao pinDao;
    private OutboxDao outboxDao;
    private TokenStore tokenStore;
    private Moshi moshi;
    private TokenRefresher tokenRefresher;
    private SessionOperationTracker operationTracker;
    private SyncEngine syncEngine;

    @Before
    public void setUp() {
        appDatabase = mock(AppDatabase.class);
        messageApi = mock(MessageApi.class);
        messageDao = mock(MessageDao.class);
        conversationDao = mock(ConversationDao.class);
        reactionDao = mock(ReactionDao.class);
        tokenStore = mock(TokenStore.class);
        moshi = new Moshi.Builder().build();
        tokenRefresher = new TokenRefresher(tokenStore, new SessionEvents(), () -> null);
        tokenRefresher.onSessionEstablished(1L);
        operationTracker = new SessionOperationTracker();

        // Make runInTransaction execute the runnable immediately
        doAnswer(invocation -> {
            Runnable runnable = invocation.getArgument(0);
            runnable.run();
            return null;
        }).when(appDatabase).runInTransaction(any(Runnable.class));

        when(tokenStore.readTokens()).thenReturn(Maybe.just(new Tokens("dummy-token", 1800000000000L, "dummy-refresh")));
        when(tokenStore.readSyncServerTime(anyString())).thenReturn(Maybe.empty());
        when(tokenStore.saveSyncServerTime(anyString(), anyString())).thenReturn(Completable.complete());
        when(conversationDao.bumpPeerReadSeq(anyString(), anyLong())).thenReturn(Completable.complete());
        when(conversationDao.bumpPeerDeliveredSeq(anyString(), anyLong())).thenReturn(Completable.complete());
        when(messageDao.byId(anyString())).thenReturn(Maybe.empty());
        when(messageDao.update(any(MessageEntity.class))).thenReturn(Completable.complete());
        when(messageDao.upsertAll(anyList())).thenReturn(Completable.complete());
        when(messageDao.clearReplySnippets(anyString())).thenReturn(Completable.complete());
        when(conversationDao.incrementUnread(anyString(), anyInt())).thenReturn(Completable.complete());
        when(conversationDao.updateLastMessageContent(anyString(), anyLong(), anyString()))
                .thenReturn(Completable.complete());
        when(conversationDao.updateLastMessage(anyString(), anyLong(), anyString(), anyString(), anyLong(), anyBoolean(),
                any()))
                .thenReturn(Completable.complete());
        when(conversationDao.deleteById(anyString())).thenReturn(Completable.complete());
        when(conversationDao.updatePin(anyString(), anyBoolean(), any())).thenReturn(Completable.complete());
        when(conversationDao.updateMute(anyString(), anyBoolean(), any())).thenReturn(Completable.complete());
        when(conversationDao.updateArchived(anyString(), anyBoolean())).thenReturn(Completable.complete());
        when(messageDao.deleteForConversation(anyString())).thenReturn(Completable.complete());
        when(reactionDao.deleteForConversation(anyString())).thenReturn(Completable.complete());
        pinDao = mock(PinDao.class);
        outboxDao = mock(OutboxDao.class);
        when(pinDao.deleteForConversation(anyString())).thenReturn(Completable.complete());
        when(outboxDao.deleteForConversation(anyString(), anyString())).thenReturn(Completable.complete());
        when(outboxDao.delete(anyString(), anyString())).thenReturn(Completable.complete());
        when(appDatabase.pinDao()).thenReturn(pinDao);
        when(appDatabase.outboxDao()).thenReturn(outboxDao);
        when(appDatabase.messageDao()).thenReturn(messageDao);
        when(appDatabase.reactionDao()).thenReturn(reactionDao);
        when(appDatabase.conversationDao()).thenReturn(conversationDao);

        syncEngine = new SyncEngine(
                appDatabase,
                messageApi,
                messageDao,
                conversationDao,
                reactionDao,
                tokenStore,
                moshi,
                tokenRefresher,
                operationTracker
        );
    }

    @Test
    public void syncEmptyWatermarksDoesNotCallServer() {
        when(messageDao.getAllMaxSeqs()).thenReturn(Single.just(Collections.emptyList()));
        when(conversationDao.getAllWatermarks()).thenReturn(Single.just(Collections.emptyList()));

        TestObserver<Void> observer = syncEngine.sync().test();
        observer.awaitCount(1);
        observer.assertComplete();

        verify(messageApi, never()).syncDeltas(any());
    }

    @Test
    public void syncBatchesWatermarksAndAppliesEveryResponseWithOneStableSince() {
        when(tokenStore.readTokens()).thenReturn(
                Maybe.just(new Tokens(jwt("user-1"), 1800000000000L, "refresh")));
        when(tokenStore.readSyncServerTime("user-1")).thenReturn(Maybe.just("cursor-start"));
        when(messageDao.upsertAll(anyList())).thenReturn(Completable.complete());
        when(conversationDao.updateLastMessage(anyString(), anyLong(), anyString(), anyString(), anyLong(), anyBoolean(),
                any()))
                .thenReturn(Completable.complete());

        int[] counts = {100, 101, 250};
        int[] expectedBatches = {1, 2, 3};
        for (int scenario = 0; scenario < counts.length; scenario++) {
            int watermarkCount = counts[scenario];
            int batchCount = expectedBatches[scenario];
            clearInvocations(messageApi, messageDao, appDatabase, tokenStore);
            when(messageDao.getAllMaxSeqs()).thenReturn(Single.just(watermarks(watermarkCount)));
            when(conversationDao.getAllWatermarks()).thenReturn(Single.just(Collections.emptyList()));
            AtomicInteger responseNumber = new AtomicInteger();
            when(messageApi.syncDeltas(any(SyncRequest.class))).thenAnswer(invocation -> {
                SyncRequest request = invocation.getArgument(0);
                String conversationId = request.getWatermarks().get(0).getConversationId();
                int responseIndex = responseNumber.incrementAndGet();
                MessageDto message = new MessageDto(
                        "m-batch-" + responseIndex, (long) responseIndex,
                        conversationId, "user-2", "Bob",
                        null, null, "TEXT", "batch", null, null, List.of(), List.of(),
                        "2026-11-08T10:00:00Z", null, null);
                SyncResponse.ConversationDeltaDto delta = new SyncResponse.ConversationDeltaDto(
                        conversationId, List.of(message), List.of(), List.of(), List.of(),
                        (long) responseIndex, false);
                return responseCall(new SyncResponse(
                        List.of(delta), "cursor-next-" + responseIndex));
            });

            syncEngine.sync().blockingAwait();

            ArgumentCaptor<SyncRequest> requestCaptor = ArgumentCaptor.forClass(SyncRequest.class);
            verify(messageApi, times(batchCount)).syncDeltas(requestCaptor.capture());
            int totalWatermarks = 0;
            for (SyncRequest request : requestCaptor.getAllValues()) {
                assertTrue("each request must respect the server cap",
                        request.getWatermarks().size() <= 100);
                assertEquals("all batches must use the same since cursor",
                        "cursor-start", request.getSince());
                assertEquals(Integer.valueOf(50), request.getLimitPerConv());
                totalWatermarks += request.getWatermarks().size();
            }
            assertEquals(watermarkCount, totalWatermarks);
            verify(messageDao, times(batchCount)).upsertAll(anyList());
            verify(appDatabase, times(batchCount)).runInTransaction(any(Runnable.class));
            verify(tokenStore).saveSyncServerTime("user-1", "cursor-next-" + batchCount);
        }
    }

    @Test
    public void syncDoesNotAdvanceCursorWhenMiddleWatermarkBatchFails() {
        when(tokenStore.readTokens()).thenReturn(
                Maybe.just(new Tokens(jwt("user-1"), 1800000000000L, "refresh")));
        when(tokenStore.readSyncServerTime("user-1")).thenReturn(Maybe.just("cursor-start"));
        when(messageDao.getAllMaxSeqs()).thenReturn(Single.just(watermarks(250)));
        when(conversationDao.getAllWatermarks()).thenReturn(Single.just(Collections.emptyList()));
        when(messageDao.upsertAll(anyList())).thenReturn(Completable.complete());
        when(conversationDao.updateLastMessage(anyString(), anyLong(), anyString(), anyString(), anyLong(), anyBoolean(),
                any()))
                .thenReturn(Completable.complete());

        MessageDto message = new MessageDto(
                "m-first-batch", 1L, "conversation-0", "user-2", "Bob", null, null,
                "TEXT", "batch", null, null, List.of(), List.of(),
                "2026-11-08T10:00:00Z", null, null);
        SyncResponse.ConversationDeltaDto delta = new SyncResponse.ConversationDeltaDto(
                "conversation-0", List.of(message), List.of(), List.of(), List.of(), 1L, false);
        Call<SyncResponse> successfulFirstBatch = responseCall(
                new SyncResponse(List.of(delta), "cursor-first-batch"));
        @SuppressWarnings("unchecked")
        Call<SyncResponse> failedSecondBatch = mock(Call.class);
        when(failedSecondBatch.clone()).thenReturn(failedSecondBatch);
        try {
            when(failedSecondBatch.execute()).thenReturn(Response.error(
                    503, ResponseBody.create(MediaType.parse("application/json"), "{}")));
        } catch (java.io.IOException ignored) {
            throw new AssertionError(ignored);
        }
        when(messageApi.syncDeltas(any(SyncRequest.class)))
                .thenReturn(successfulFirstBatch, failedSecondBatch);

        TestObserver<Void> observer = syncEngine.sync().test();
        observer.awaitDone(5, java.util.concurrent.TimeUnit.SECONDS);
        observer.assertError(java.io.IOException.class);

        verify(messageApi, times(2)).syncDeltas(any(SyncRequest.class));
        verify(messageDao).upsertAll(anyList());
        verify(tokenStore, never()).saveSyncServerTime(anyString(), anyString());
    }

    @Test
    public void syncAppliesCallerMuteAndArchiveFlagsFromEveryDelta() {
        when(messageDao.getAllMaxSeqs()).thenReturn(
                Single.just(List.of(new ConversationWatermark("c1", 10L))));
        when(conversationDao.getAllWatermarks()).thenReturn(Single.just(Collections.emptyList()));
        when(conversationDao.updateMute(anyString(),
                org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(Completable.complete());
        when(conversationDao.updateArchived(anyString(),
                org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(Completable.complete());

        SyncResponse.ConversationDeltaDto delta = new SyncResponse.ConversationDeltaDto(
                "c1", List.of(), List.of(), List.of(), List.of(), 10L, false,
                true, "2026-11-10T01:00:00Z", true);
        SyncResponse response = new SyncResponse(List.of(delta), "2026-11-08T10:00:00Z");
        @SuppressWarnings("unchecked")
        Call<SyncResponse> mockCall = mock(Call.class);
        when(mockCall.clone()).thenReturn(mockCall);
        try {
            when(mockCall.execute()).thenReturn(Response.success(response));
        } catch (java.io.IOException ignored) {
        }
        when(messageApi.syncDeltas(any(SyncRequest.class))).thenReturn(mockCall);

        syncEngine.sync().blockingAwait();

        verify(conversationDao).updateMute(
                org.mockito.ArgumentMatchers.eq("c1"),
                org.mockito.ArgumentMatchers.eq(true),
                org.mockito.ArgumentMatchers.eq(
                        java.time.Instant.parse("2026-11-10T01:00:00Z").toEpochMilli()));
        verify(conversationDao).updateArchived(
                org.mockito.ArgumentMatchers.eq("c1"),
                org.mockito.ArgumentMatchers.eq(true));
    }

    @Test
    public void syncLeavesPreferenceColumnsAloneWhenDeltaOmitsThem() {
        when(messageDao.getAllMaxSeqs()).thenReturn(
                Single.just(List.of(new ConversationWatermark("c1", 10L))));
        when(conversationDao.getAllWatermarks()).thenReturn(Single.just(Collections.emptyList()));

        SyncResponse.ConversationDeltaDto delta = new SyncResponse.ConversationDeltaDto(
                "c1", List.of(), List.of(), List.of(), List.of(), 10L, false);
        SyncResponse response = new SyncResponse(List.of(delta), "2026-11-08T10:00:00Z");
        @SuppressWarnings("unchecked")
        Call<SyncResponse> mockCall = mock(Call.class);
        when(mockCall.clone()).thenReturn(mockCall);
        try {
            when(mockCall.execute()).thenReturn(Response.success(response));
        } catch (java.io.IOException ignored) {
        }
        when(messageApi.syncDeltas(any(SyncRequest.class))).thenReturn(mockCall);

        syncEngine.sync().blockingAwait();

        verify(conversationDao, never()).updateMute(anyString(),
                org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.any());
        verify(conversationDao, never()).updateArchived(anyString(),
                org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    public void syncDrainsEveryPageAndAppliesPreferenceFlagsPerPage() {
        when(tokenStore.readTokens()).thenReturn(
                Maybe.just(new Tokens(jwt("user-1"), 1800000000000L, "refresh")));
        when(tokenStore.readSyncServerTime("user-1")).thenReturn(Maybe.just("cursor-start"));
        when(messageDao.getAllMaxSeqs()).thenReturn(
                Single.just(List.of(new ConversationWatermark("c1", 10L))));
        when(conversationDao.getAllWatermarks()).thenReturn(Single.just(Collections.emptyList()));
        when(conversationDao.updateMute(anyString(),
                org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(Completable.complete());
        when(conversationDao.updateArchived(anyString(),
                org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(Completable.complete());
        when(messageDao.upsertAll(anyList())).thenReturn(Completable.complete());
        when(conversationDao.updateLastMessage(
                anyString(), anyLong(), anyString(), anyString(), anyLong(), anyBoolean(),
                any()))
                .thenReturn(Completable.complete());

        SyncResponse firstPage = new SyncResponse(List.of(
                new SyncResponse.ConversationDeltaDto(
                        "c1", List.of(message("m-11", 11L, "c1", "first")),
                        List.of(), List.of(), List.of(),
                        11L, true, 41L, false,
                        true, "2026-11-10T01:00:00Z", true)), "cursor-end");
        SyncResponse lastPage = new SyncResponse(List.of(
                new SyncResponse.ConversationDeltaDto(
                        "c1", List.of(), List.of(), List.of(), List.of(),
                        11L, false, null, false,
                        false, null, false)), "cursor-end");
        Call<SyncResponse> firstCall = responseCall(firstPage);
        Call<SyncResponse> secondCall = responseCall(lastPage);
        when(messageApi.syncDeltas(any(SyncRequest.class)))
                .thenReturn(firstCall, secondCall);

        syncEngine.sync().blockingAwait();

        verify(messageApi, times(2)).syncDeltas(any(SyncRequest.class));
        verify(conversationDao).updateMute(
                eq("c1"), eq(true),
                eq(java.time.Instant.parse("2026-11-10T01:00:00Z").toEpochMilli()));
        verify(conversationDao).updateMute(eq("c1"), eq(false),
                org.mockito.ArgumentMatchers.isNull());
        verify(conversationDao).updateArchived("c1", true);
        verify(conversationDao).updateArchived("c1", false);
        verify(tokenStore).saveSyncServerTime("user-1", "cursor-end");
    }

    @Test
    public void syncAggregatesWatermarksAndAppliesDeltaBundle() {
        List<ConversationWatermark> msgWms = List.of(
                new ConversationWatermark("c1", 10L),
                new ConversationWatermark("c2", 5L)
        );
        List<ConversationWatermark> convWms = List.of(
                new ConversationWatermark("c2", 8L),
                new ConversationWatermark("c3", 1L)
        );

        when(messageDao.getAllMaxSeqs()).thenReturn(Single.just(msgWms));
        when(conversationDao.getAllWatermarks()).thenReturn(Single.just(convWms));

        // c1: 10, c2: max(5, 8) = 8, c3: 1
        MessageDto newMsg = new MessageDto(
                "m-new", 15L, "c1", "user-2", "Bob", null, null, "TEXT", "Hello World",
                null, null, List.of(), List.of(), "2026-11-08T10:00:00Z", null, null
        );
        SyncResponse.ConversationDeltaDto delta1 = new SyncResponse.ConversationDeltaDto(
                "c1", List.of(newMsg), List.of(), List.of(), List.of(), 15L, false
        );
        SyncResponse response = new SyncResponse(List.of(delta1), "2026-11-08T10:00:00Z");

        @SuppressWarnings("unchecked")
        Call<SyncResponse> mockCall = mock(Call.class);
        when(mockCall.clone()).thenReturn(mockCall);
        when(messageApi.syncDeltas(any(SyncRequest.class))).thenReturn(mockCall);

        try {
            when(mockCall.execute()).thenReturn(Response.success(response));
        } catch (java.io.IOException ignored) {
        }

        when(messageDao.upsertAll(anyList())).thenReturn(Completable.complete());
        when(conversationDao.updateLastMessage(anyString(), anyLong(), anyString(), anyString(), anyLong(), anyBoolean(),
                any()))
                .thenReturn(Completable.complete());
        when(conversationDao.bumpPeerReadSeq(anyString(), anyLong())).thenReturn(Completable.complete());

        TestObserver<Void> observer = syncEngine.sync().test();
        observer.awaitCount(1);
        observer.assertComplete();

        ArgumentCaptor<SyncRequest> requestCaptor = ArgumentCaptor.forClass(SyncRequest.class);
        verify(messageApi).syncDeltas(requestCaptor.capture());
        SyncRequest captured = requestCaptor.getValue();
        assertEquals(3, captured.getWatermarks().size());

        verify(appDatabase).runInTransaction(any(Runnable.class));
        verify(messageDao).upsertAll(anyList());
        verify(conversationDao).updateLastMessage(
                org.mockito.ArgumentMatchers.eq("c1"),
                org.mockito.ArgumentMatchers.eq(15L),
                org.mockito.ArgumentMatchers.eq("Hello World"),
                org.mockito.ArgumentMatchers.eq("Bob"),
                anyLong(),
                anyBoolean(),
                any()
        );
        verify(conversationDao, never()).bumpPeerDeliveredSeq(anyString(), anyLong());
        verify(conversationDao, never()).bumpPeerReadSeq(anyString(), anyLong());
    }

    @Test
    public void syncHandlesDeletedMessageIdTombstone() {
        List<ConversationWatermark> msgWms = List.of(new ConversationWatermark("c1", 10L));
        when(messageDao.getAllMaxSeqs()).thenReturn(Single.just(msgWms));
        when(conversationDao.getAllWatermarks()).thenReturn(Single.just(Collections.emptyList()));

        SyncResponse.ConversationDeltaDto delta1 = new SyncResponse.ConversationDeltaDto(
                "c1", List.of(), List.of(), List.of("m-del"), List.of(), 10L, false
        );
        SyncResponse response = new SyncResponse(List.of(delta1), "2026-11-08T10:00:00Z");

        @SuppressWarnings("unchecked")
        Call<SyncResponse> mockCall = mock(Call.class);
        when(mockCall.clone()).thenReturn(mockCall);
        when(messageApi.syncDeltas(any(SyncRequest.class))).thenReturn(mockCall);

        try {
            when(mockCall.execute()).thenReturn(Response.success(response));
        } catch (java.io.IOException ignored) {
        }

        MessageEntity existing = new MessageEntity(
                "m-del", "c1", 5L, "user-1", "Alice", "TEXT", "Secret", "client-1",
                null, null, null, 1700000000L, null, null, null
        );
        when(messageDao.byId("m-del")).thenReturn(Maybe.just(existing));
        when(messageDao.update(any(MessageEntity.class))).thenReturn(Completable.complete());
        when(messageDao.clearReplySnippets("m-del")).thenReturn(Completable.complete());
        when(conversationDao.byId("c1")).thenReturn(Maybe.empty());

        TestObserver<Void> observer = syncEngine.sync().test();
        observer.awaitCount(1);
        observer.assertComplete();

        ArgumentCaptor<MessageEntity> tombstoneCaptor = ArgumentCaptor.forClass(MessageEntity.class);
        verify(messageDao).update(tombstoneCaptor.capture());
        MessageEntity tombstone = tombstoneCaptor.getValue();
        assertEquals("m-del", tombstone.getId());
        assertEquals("", tombstone.getContent());
        assertTrue(tombstone.getDeletedAtEpochMs() != null);
        verify(reactionDao).deleteForMessageInTransaction("m-del");
        verify(messageDao).clearReplySnippets("m-del");
    }

    @Test
    public void syncPersistsAndReloadsServerTimeAcrossEngineInstances() {
        List<ConversationWatermark> watermarks = List.of(new ConversationWatermark("c1", 10L));
        when(messageDao.getAllMaxSeqs()).thenReturn(Single.just(watermarks));
        when(conversationDao.getAllWatermarks()).thenReturn(Single.just(Collections.emptyList()));
        when(tokenStore.readTokens()).thenReturn(Maybe.just(
                new Tokens(jwt("user-1"), 1800000000000L, "dummy-refresh")));

        AtomicReference<String> persistedCursor = new AtomicReference<>();
        when(tokenStore.readSyncServerTime("user-1")).thenAnswer(invocation -> {
            String value = persistedCursor.get();
            return value == null ? Maybe.empty() : Maybe.just(value);
        });
        when(tokenStore.saveSyncServerTime(
                org.mockito.ArgumentMatchers.eq("user-1"), anyString()))
                .thenAnswer(invocation -> {
                    persistedCursor.set(invocation.getArgument(1));
                    return Completable.complete();
                });

        SyncResponse firstResponse = new SyncResponse(List.of(), "2026-11-08T10:00:00Z");
        SyncResponse secondResponse = new SyncResponse(List.of(), "2026-11-08T10:05:00Z");

        @SuppressWarnings("unchecked")
        Call<SyncResponse> firstCall = mock(Call.class);
        when(firstCall.clone()).thenReturn(firstCall);
        @SuppressWarnings("unchecked")
        Call<SyncResponse> secondCall = mock(Call.class);
        when(secondCall.clone()).thenReturn(secondCall);
        try {
            when(firstCall.execute()).thenReturn(Response.success(firstResponse));
            when(secondCall.execute()).thenReturn(Response.success(secondResponse));
        } catch (java.io.IOException ignored) {
        }
        when(messageApi.syncDeltas(any(SyncRequest.class)))
                .thenReturn(firstCall)
                .thenReturn(secondCall);

        TestObserver<Void> firstSync = syncEngine.sync().test();
        firstSync.awaitCount(1);
        firstSync.assertComplete();

        syncEngine = new SyncEngine(
                appDatabase, messageApi, messageDao, conversationDao, reactionDao, tokenStore,
                moshi, tokenRefresher, operationTracker);
        TestObserver<Void> secondSync = syncEngine.sync().test();
        secondSync.awaitCount(1);
        secondSync.assertComplete();

        ArgumentCaptor<SyncRequest> requestCaptor = ArgumentCaptor.forClass(SyncRequest.class);
        verify(messageApi, org.mockito.Mockito.times(2)).syncDeltas(requestCaptor.capture());
        List<SyncRequest> requests = requestCaptor.getAllValues();
        org.junit.Assert.assertNull("first sync has no server time yet", requests.get(0).getSince());
        org.junit.Assert.assertEquals("2026-11-08T10:00:00Z", requests.get(1).getSince());
        verify(tokenStore).saveSyncServerTime("user-1", "2026-11-08T10:00:00Z");
        verify(tokenStore).saveSyncServerTime("user-1", "2026-11-08T10:05:00Z");
    }

    @Test
    public void syncDoesNotFabricatePeerReceiptWatermarks() {
        List<ConversationWatermark> watermarks = List.of(new ConversationWatermark("c1", 10L));
        when(messageDao.getAllMaxSeqs()).thenReturn(Single.just(watermarks));
        when(conversationDao.getAllWatermarks()).thenReturn(Single.just(Collections.emptyList()));

        MessageDto newMsg = new MessageDto(
                "m-new", 15L, "c1", "user-2", "Bob", null, null, "TEXT", "Hello World",
                null, null, List.of(), List.of(), "2026-11-08T10:00:00Z", null, null
        );
        SyncResponse.ConversationDeltaDto delta = new SyncResponse.ConversationDeltaDto(
                "c1", List.of(newMsg), List.of(), List.of(), List.of(), 15L, false
        );
        SyncResponse response = new SyncResponse(List.of(delta), "2026-11-08T10:00:00Z");

        @SuppressWarnings("unchecked")
        Call<SyncResponse> mockCall = mock(Call.class);
        when(mockCall.clone()).thenReturn(mockCall);
        when(messageApi.syncDeltas(any(SyncRequest.class))).thenReturn(mockCall);
        try {
            when(mockCall.execute()).thenReturn(Response.success(response));
        } catch (java.io.IOException ignored) {
        }

        when(messageDao.upsertAll(anyList())).thenReturn(Completable.complete());
        when(conversationDao.updateLastMessage(anyString(), anyLong(), anyString(), anyString(), anyLong(), anyBoolean(),
                any()))
                .thenReturn(Completable.complete());

        TestObserver<Void> observer = syncEngine.sync().test();
        observer.awaitCount(1);
        observer.assertComplete();

        verify(conversationDao, never()).bumpPeerReadSeq(anyString(), anyLong());
        verify(conversationDao, never()).bumpPeerDeliveredSeq(anyString(), anyLong());
    }

    @Test
    public void syncKeepsServerTimeCursorScopedToTheAuthenticatedUser() {
        when(messageDao.getAllMaxSeqs()).thenReturn(
                Single.just(List.of(new ConversationWatermark("c1", 10L))));
        when(conversationDao.getAllWatermarks()).thenReturn(Single.just(Collections.emptyList()));
        when(tokenStore.readTokens()).thenReturn(
                Maybe.just(new Tokens(jwt("user-a"), 1800000000000L, "refresh-a")),
                Maybe.just(new Tokens(jwt("user-b"), 1800000000000L, "refresh-b")),
                Maybe.just(new Tokens(jwt("user-a"), 1800000000000L, "refresh-a")));

        Map<String, String> cursors = new HashMap<>();
        when(tokenStore.readSyncServerTime(anyString())).thenAnswer(invocation -> {
            String value = cursors.get(invocation.getArgument(0));
            return value == null ? Maybe.empty() : Maybe.just(value);
        });
        when(tokenStore.saveSyncServerTime(anyString(), anyString())).thenAnswer(invocation -> {
            cursors.put(invocation.getArgument(0), invocation.getArgument(1));
            return Completable.complete();
        });

        @SuppressWarnings("unchecked")
        Call<SyncResponse> call = mock(Call.class);
        when(call.clone()).thenReturn(call);
        try {
            when(call.execute()).thenReturn(
                    Response.success(new SyncResponse(List.of(), "cursor-a-1")),
                    Response.success(new SyncResponse(List.of(), "cursor-b-1")),
                    Response.success(new SyncResponse(List.of(), "cursor-a-2")));
        } catch (java.io.IOException ignored) {
        }
        when(messageApi.syncDeltas(any(SyncRequest.class))).thenReturn(call);

        syncEngine.sync().blockingAwait();
        syncEngine.sync().blockingAwait();
        syncEngine.sync().blockingAwait();

        ArgumentCaptor<SyncRequest> requestCaptor = ArgumentCaptor.forClass(SyncRequest.class);
        verify(messageApi, org.mockito.Mockito.times(3)).syncDeltas(requestCaptor.capture());
        List<SyncRequest> requests = requestCaptor.getAllValues();
        org.junit.Assert.assertNull(requests.get(0).getSince());
        org.junit.Assert.assertNull("another user's cursor must not leak into this request",
                requests.get(1).getSince());
        assertEquals("cursor-a-1", requests.get(2).getSince());
        assertEquals("cursor-a-2", cursors.get("user-a"));
        assertEquals("cursor-b-1", cursors.get("user-b"));
    }

    @Test
    public void syncPagesEveryHasMoreDeltaAndEchoesTheReactionCursor() {
        when(tokenStore.readTokens()).thenReturn(
                Maybe.just(new Tokens(jwt("user-1"), 1800000000000L, "refresh")));
        when(tokenStore.readSyncServerTime("user-1")).thenReturn(Maybe.just("cursor-start"));
        when(messageDao.getAllMaxSeqs()).thenReturn(
                Single.just(List.of(new ConversationWatermark("c1", 10L))));
        when(conversationDao.getAllWatermarks()).thenReturn(Single.just(Collections.emptyList()));
        when(messageDao.upsertAll(anyList())).thenReturn(Completable.complete());
        when(messageDao.update(any(MessageEntity.class))).thenReturn(Completable.complete());
        when(conversationDao.updateLastMessage(anyString(), anyLong(), anyString(), anyString(), anyLong(), anyBoolean(),
                any()))
                .thenReturn(Completable.complete());

        MessageDto created = message("m-11", 11L, "c1", "hello");
        MessageDto edited = message("m-5", 5L, "c1", "edited");
        ReactionUpdateDto reaction = new ReactionUpdateDto(
                "m-10", List.of(new ReactionSummaryDto("\ud83d\udc4d", 1, true)));
        SyncResponse firstPage = new SyncResponse(List.of(new SyncResponse.ConversationDeltaDto(
                "c1", List.of(created), List.of(), List.of(), List.of(reaction),
                11L, true, 41L, false)), "cursor-end");
        SyncResponse lastPage = new SyncResponse(List.of(new SyncResponse.ConversationDeltaDto(
                "c1", List.of(), List.of(edited), List.of(), List.of(),
                11L, false, null, false)), "cursor-end");
        Call<SyncResponse> firstCall = responseCall(firstPage);
        Call<SyncResponse> secondCall = responseCall(lastPage);
        when(messageApi.syncDeltas(any(SyncRequest.class))).thenReturn(firstCall, secondCall);

        syncEngine.sync().blockingAwait();

        ArgumentCaptor<SyncRequest> requestCaptor = ArgumentCaptor.forClass(SyncRequest.class);
        verify(messageApi, times(2)).syncDeltas(requestCaptor.capture());
        List<SyncRequest> requests = requestCaptor.getAllValues();
        assertEquals("cursor-start", requests.get(0).getSince());
        assertEquals("all pages share one stable since cursor",
                "cursor-start", requests.get(1).getSince());
        assertEquals(10L, requests.get(0).getWatermarks().get(0).getLastSeq());
        assertEquals(11L, requests.get(1).getWatermarks().get(0).getLastSeq());
        assertEquals(Long.valueOf(41L),
                requests.get(1).getWatermarks().get(0).getReactionCursor());
        verify(messageDao).upsertAll(anyList());
        verify(messageDao).update(any(MessageEntity.class));
        verify(reactionDao).replaceForMessageInTransaction(
                org.mockito.ArgumentMatchers.eq("m-10"), anyList());
        verify(tokenStore).saveSyncServerTime("user-1", "cursor-end");
    }

    @Test
    public void syncPagesEditOnlyDeltasUntilHasMoreIsFalse() {
        when(tokenStore.readTokens()).thenReturn(
                Maybe.just(new Tokens(jwt("user-1"), 1800000000000L, "refresh")));
        when(tokenStore.readSyncServerTime("user-1")).thenReturn(Maybe.just("cursor-start"));
        when(messageDao.getAllMaxSeqs()).thenReturn(
                Single.just(List.of(new ConversationWatermark("c1", 10L))));
        when(conversationDao.getAllWatermarks()).thenReturn(Single.just(Collections.emptyList()));
        when(messageDao.update(any(MessageEntity.class))).thenReturn(Completable.complete());
        when(conversationDao.updateLastMessageContent(anyString(), anyLong(), anyString()))
                .thenReturn(Completable.complete());

        SyncResponse firstPage = new SyncResponse(List.of(new SyncResponse.ConversationDeltaDto(
                "c1", List.of(), List.of(message("m-5", 5L, "c1", "first edit")),
                List.of(), List.of(), 20L, true, null, false)), "cursor-end");
        SyncResponse lastPage = new SyncResponse(List.of(new SyncResponse.ConversationDeltaDto(
                "c1", List.of(), List.of(message("m-6", 6L, "c1", "second edit")),
                List.of(), List.of(), 20L, false, null, false)), "cursor-end");
        Call<SyncResponse> firstCall = responseCall(firstPage);
        Call<SyncResponse> secondCall = responseCall(lastPage);
        when(messageApi.syncDeltas(any(SyncRequest.class))).thenReturn(firstCall, secondCall);

        syncEngine.sync().blockingAwait();

        ArgumentCaptor<SyncRequest> requestCaptor = ArgumentCaptor.forClass(SyncRequest.class);
        verify(messageApi, times(2)).syncDeltas(requestCaptor.capture());
        List<SyncRequest> requests = requestCaptor.getAllValues();
        assertEquals(20L, requests.get(1).getWatermarks().get(0).getLastSeq());
        assertEquals("cursor-start", requests.get(1).getSince());
        verify(messageDao, times(2)).update(any(MessageEntity.class));
        verify(tokenStore).saveSyncServerTime("user-1", "cursor-end");
    }

    @Test
    public void legacySyncPaginatesEditRecallOverflowByEarliestTimestampAcrossBatches() {
        when(tokenStore.readTokens()).thenReturn(
                Maybe.just(new Tokens(jwt("user-1"), 1800000000000L, "refresh")));
        Map<String, String> cursors = new HashMap<>();
        cursors.put("user-1", "2026-11-08T10:00:00Z");
        when(tokenStore.readSyncServerTime("user-1")).thenAnswer(invocation -> {
            String cursor = cursors.get("user-1");
            return cursor == null ? Maybe.empty() : Maybe.just(cursor);
        });
        when(tokenStore.saveSyncServerTime(anyString(), anyString())).thenAnswer(invocation -> {
            cursors.put(invocation.getArgument(0), invocation.getArgument(1));
            return Completable.complete();
        });
        when(messageDao.getAllMaxSeqs()).thenReturn(Single.just(watermarks(101)));
        when(conversationDao.getAllWatermarks()).thenReturn(Single.just(Collections.emptyList()));
        when(messageDao.byId(anyString())).thenReturn(Maybe.empty());

        AtomicInteger callIndex = new AtomicInteger();
        String[] responseTimes = {
                "2026-11-08T10:00:05Z", "2026-11-08T10:00:10Z",
                "2026-11-08T10:00:20Z", "2026-11-08T10:00:25Z"
        };
        when(messageApi.syncDeltas(any(SyncRequest.class))).thenAnswer(invocation -> {
            int index = callIndex.getAndIncrement();
            SyncRequest request = invocation.getArgument(0);
            boolean firstOverflowPage = index == 0;
            boolean finalTimestampPage = index == 2;
            List<SyncResponse.ConversationDeltaDto> deltas = request.getWatermarks().stream()
                    .map(watermark -> {
                        boolean target = "conversation-0".equals(watermark.getConversationId());
                        if (target && (firstOverflowPage || finalTimestampPage)) {
                            String suffix = firstOverflowPage ? "first" : "last";
                            return new SyncResponse.ConversationDeltaDto(
                                    watermark.getConversationId(), List.of(),
                                    List.of(message("edit-" + suffix, 0L, watermark.getConversationId(), suffix)),
                                    List.of("deleted-" + suffix), List.of(), watermark.getLastSeq(),
                                    firstOverflowPage);
                        }
                        return new SyncResponse.ConversationDeltaDto(
                                watermark.getConversationId(), List.of(), List.of(), List.of(), List.of(),
                                watermark.getLastSeq(), false);
                    })
                    .toList();
            return responseCall(new SyncResponse(deltas, responseTimes[index]));
        });

        syncEngine.sync().blockingAwait();

        ArgumentCaptor<SyncRequest> requestCaptor = ArgumentCaptor.forClass(SyncRequest.class);
        verify(messageApi, times(4)).syncDeltas(requestCaptor.capture());
        List<SyncRequest> requests = requestCaptor.getAllValues();
        assertEquals(100, requests.get(0).getWatermarks().size());
        assertEquals(1, requests.get(1).getWatermarks().size());
        assertEquals("2026-11-08T10:00:00Z", requests.get(0).getSince());
        assertEquals("2026-11-08T10:00:00Z", requests.get(1).getSince());
        assertEquals("2026-11-08T10:00:05Z", requests.get(2).getSince());
        assertEquals("2026-11-08T10:00:05Z", requests.get(3).getSince());
        ArgumentCaptor<String> savedCursors = ArgumentCaptor.forClass(String.class);
        verify(tokenStore, times(2)).saveSyncServerTime(eq("user-1"), savedCursors.capture());
        assertEquals(List.of("2026-11-08T10:00:05Z", "2026-11-08T10:00:20Z"),
                savedCursors.getAllValues());
    }

    @Test
    public void syncStopsWhenHasMoreMakesNoProgressAndDoesNotAdvanceTheCursor() {
        when(tokenStore.readTokens()).thenReturn(
                Maybe.just(new Tokens(jwt("user-1"), 1800000000000L, "refresh")));
        when(tokenStore.readSyncServerTime("user-1")).thenReturn(Maybe.just("cursor-start"));
        when(messageDao.getAllMaxSeqs()).thenReturn(
                Single.just(List.of(new ConversationWatermark("c1", 10L))));
        when(conversationDao.getAllWatermarks()).thenReturn(Single.just(Collections.emptyList()));

        SyncResponse stuck = new SyncResponse(List.of(new SyncResponse.ConversationDeltaDto(
                "c1", List.of(), List.of(), List.of(), List.of(),
                20L, true, null, false)), "cursor-end");
        Call<SyncResponse> stuckCall = responseCall(stuck);
        when(messageApi.syncDeltas(any(SyncRequest.class))).thenReturn(stuckCall);

        TestObserver<Void> observer = syncEngine.sync().test();
        observer.awaitDone(5, java.util.concurrent.TimeUnit.SECONDS);
        observer.assertError(java.io.IOException.class);

        verify(messageApi, times(2)).syncDeltas(any(SyncRequest.class));
        verify(tokenStore, never()).saveSyncServerTime(anyString(), anyString());
    }

    @Test
    public void syncStopsAtThePageLimitWhenTheServerKeepsClaimingMore() {
        when(tokenStore.readTokens()).thenReturn(
                Maybe.just(new Tokens(jwt("user-1"), 1800000000000L, "refresh")));
        when(tokenStore.readSyncServerTime("user-1")).thenReturn(Maybe.just("cursor-start"));
        when(messageDao.getAllMaxSeqs()).thenReturn(
                Single.just(List.of(new ConversationWatermark("c1", 10L))));
        when(conversationDao.getAllWatermarks()).thenReturn(Single.just(Collections.emptyList()));

        AtomicInteger page = new AtomicInteger(10);
        when(messageApi.syncDeltas(any(SyncRequest.class))).thenAnswer(invocation -> {
            long maxSeq = page.incrementAndGet();
            SyncResponse response = new SyncResponse(List.of(
                    new SyncResponse.ConversationDeltaDto(
                            "c1", List.of(), List.of(), List.of(), List.of(),
                            maxSeq, true, null, false)), "cursor-end");
            return responseCall(response);
        });

        TestObserver<Void> observer = syncEngine.sync().test();
        observer.awaitDone(5, java.util.concurrent.TimeUnit.SECONDS);
        observer.assertComplete();

        int expectedCalls = SyncEngine.MAX_DRAIN_PAGES * (1 + SyncEngine.MAX_BOUNDED_RETRIES);
        verify(messageApi, times(expectedCalls))
                .syncDeltas(any(SyncRequest.class));
        verify(tokenStore, atLeastOnce()).saveSyncServerTime(eq("user-1"), anyString());
        assertTrue(syncEngine.hasPendingContinuation());
    }

    @Test
    public void syncWhenPageLimitExceededPersistsDrainedProgressAndResumesOnNextRun() {
        when(tokenStore.readTokens()).thenReturn(
                Maybe.just(new Tokens(jwt("user-1"), 1800000000000L, "refresh")));
        when(tokenStore.readSyncServerTime("user-1")).thenReturn(Maybe.just("cursor-start"));
        when(messageDao.getAllMaxSeqs()).thenReturn(
                Single.just(List.of(new ConversationWatermark("c1", 10L))));
        when(conversationDao.getAllWatermarks()).thenReturn(Single.just(Collections.emptyList()));
        when(messageDao.upsertAll(anyList())).thenReturn(Completable.complete());
        when(conversationDao.updateLastMessage(anyString(), anyLong(), anyString(), anyString(), anyLong(), anyBoolean(),
                any()))
                .thenReturn(Completable.complete());

        AtomicInteger pageCounter = new AtomicInteger();
        when(messageApi.syncDeltas(any(SyncRequest.class))).thenAnswer(invocation -> {
            int p = pageCounter.incrementAndGet();
            long maxSeq = 10L + (p * 5L);
            boolean hasMore = p < 25;
            MessageDto msg = message("msg-" + p, maxSeq, "c1", "content-" + p);
            SyncResponse response = new SyncResponse(List.of(
                    new SyncResponse.ConversationDeltaDto(
                            "c1", List.of(msg), List.of(), List.of(), List.of(),
                            maxSeq, hasMore, null, false)), "cursor-p" + p);
            return responseCall(response);
        });

        TestObserver<Void> observer = syncEngine.sync().test();
        observer.awaitDone(5, java.util.concurrent.TimeUnit.SECONDS);
        observer.assertComplete();

        // Must persist server time and not stall the cursor
        verify(tokenStore, atLeastOnce()).saveSyncServerTime(eq("user-1"), anyString());
        org.junit.Assert.assertFalse(syncEngine.hasPendingContinuation());
    }

    @Test
    public void syncRemovesConversationOmittedFromASuccessfulDeltaResponse() {
        when(tokenStore.readTokens()).thenReturn(
                Maybe.just(new Tokens(jwt("user-1"), 1800000000000L, "refresh")));
        when(tokenStore.readSyncServerTime("user-1")).thenReturn(Maybe.just("cursor-start"));
        when(messageDao.getAllMaxSeqs()).thenReturn(Single.just(List.of(
                new ConversationWatermark("c1", 10L),
                new ConversationWatermark("c2", 20L))));
        when(conversationDao.getAllWatermarks()).thenReturn(Single.just(Collections.emptyList()));
        when(conversationDao.deleteById(anyString())).thenReturn(Completable.complete());
        when(conversationDao.updatePin(anyString(), anyBoolean(), any())).thenReturn(Completable.complete());
        when(conversationDao.updateMute(anyString(), anyBoolean(), any())).thenReturn(Completable.complete());
        when(conversationDao.updateArchived(anyString(), anyBoolean())).thenReturn(Completable.complete());
        when(messageDao.deleteForConversation(anyString())).thenReturn(Completable.complete());
        when(reactionDao.deleteForConversation(anyString())).thenReturn(Completable.complete());
        SyncResponse response = new SyncResponse(List.of(new SyncResponse.ConversationDeltaDto(
                "c1", List.of(), List.of(), List.of(), List.of(), 10L, false)), "cursor-end");
        Call<SyncResponse> serverCall = responseCall(response);
        when(messageApi.syncDeltas(any(SyncRequest.class))).thenReturn(serverCall);

        syncEngine.sync().blockingAwait();

        verify(conversationDao).deleteById("c2");
        verify(messageDao).deleteForConversation("c2");
        verify(reactionDao).deleteForConversation("c2");
        verify(pinDao).deleteForConversation("c2");
        verify(outboxDao).deleteForConversation("c2", "user-1");
        verify(conversationDao, never()).deleteById("c1");
        verify(tokenStore).saveSyncServerTime("user-1", "cursor-end");
    }

    @Test
    public void syncDoesNotPersistMissingServerTime() {
        when(messageDao.getAllMaxSeqs()).thenReturn(
                Single.just(List.of(new ConversationWatermark("c1", 10L))));
        when(conversationDao.getAllWatermarks()).thenReturn(Single.just(Collections.emptyList()));
        when(tokenStore.readTokens()).thenReturn(Maybe.just(
                new Tokens(jwt("user-1"), 1800000000000L, "refresh")));

        @SuppressWarnings("unchecked")
        Call<SyncResponse> call = mock(Call.class);
        when(call.clone()).thenReturn(call);
        try {
            when(call.execute()).thenReturn(Response.success(new SyncResponse(List.of(), null)));
        } catch (java.io.IOException ignored) {
        }
        when(messageApi.syncDeltas(any(SyncRequest.class))).thenReturn(call);

        syncEngine.sync().blockingAwait();

        verify(tokenStore, never()).saveSyncServerTime(anyString(), anyString());
    }

    @Test
    public void syncDiscardsDelayedAccountAResponseAfterSwitchToB() throws Exception {
        assertDelayedResponseDiscardedAfterSessionChange("user-b");
    }

    @Test
    public void syncDiscardsDelayedAccountAResponseAfterSameAccountRelogin() throws Exception {
        assertDelayedResponseDiscardedAfterSessionChange("user-a");
    }

    @Test
    public void syncRegistersDelayedRequestForSessionOperationCancellation() throws Exception {
        when(messageDao.getAllMaxSeqs()).thenReturn(
                Single.just(List.of(new ConversationWatermark("conversation-a", 0L))));
        when(conversationDao.getAllWatermarks()).thenReturn(Single.just(Collections.emptyList()));
        when(tokenStore.readTokens()).thenReturn(Maybe.just(
                new Tokens(jwt("user-a"), 1800000000000L, "refresh-a")));

        CountDownLatch requestStarted = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        CountDownLatch responseReturned = new CountDownLatch(1);
        @SuppressWarnings("unchecked")
        Call<SyncResponse> call = mock(Call.class);
        when(call.clone()).thenReturn(call);
        when(call.execute()).thenAnswer(invocation -> {
            requestStarted.countDown();
            try {
                if (!releaseResponse.await(5, TimeUnit.SECONDS)) {
                    throw new java.io.IOException("test response was not released");
                }
                return Response.success(new SyncResponse(Collections.emptyList(), "cursor-a"));
            } finally {
                responseReturned.countDown();
            }
        });
        org.mockito.Mockito.doAnswer(invocation -> {
            releaseResponse.countDown();
            return null;
        }).when(call).cancel();
        when(messageApi.syncDeltas(any(SyncRequest.class))).thenReturn(call);

        TestObserver<Void> observer = syncEngine.sync().test();
        assertTrue("sync request should reach the delayed response", requestStarted.await(5, TimeUnit.SECONDS));
        long newGeneration = operationTracker.invalidate();

        verify(call).cancel();
        assertTrue("cancelled request should leave execute", responseReturned.await(5, TimeUnit.SECONDS));
        operationTracker.activate(newGeneration);
        observer.dispose();
        verify(messageDao, never()).upsertAll(anyList());
        verify(tokenStore, never()).saveSyncServerTime("user-a", "cursor-a");
    }

    @Test
    public void syncRechecksSessionAtCursorWriteAfterRoomBoundary() {
        TokenRefresher sessionGate = mock(TokenRefresher.class);
        when(sessionGate.sessionEpoch()).thenReturn(1L);
        when(sessionGate.isSessionCurrent(1L)).thenReturn(true);
        AtomicInteger fenceCalls = new AtomicInteger();
        when(sessionGate.runIfSessionCurrent(eq(1L), any(Runnable.class)))
                .thenAnswer(invocation -> {
                    if (fenceCalls.getAndIncrement() != 0) return false;
                    ((Runnable) invocation.getArgument(1)).run();
                    return true;
                });
        when(messageDao.getAllMaxSeqs()).thenReturn(
                Single.just(List.of(new ConversationWatermark("conversation-a", 0L))));
        when(conversationDao.getAllWatermarks()).thenReturn(Single.just(Collections.emptyList()));
        when(tokenStore.readTokens()).thenReturn(Maybe.just(
                new Tokens(jwt("user-a"), 1800000000000L, "refresh-a")));
        SyncResponse response = new SyncResponse(List.of(new SyncResponse.ConversationDeltaDto(
                "conversation-a", List.of(), List.of(), List.of(), List.of(), 0L, false)),
                "cursor-a");
        Call<SyncResponse> call = responseCall(response);
        when(messageApi.syncDeltas(any(SyncRequest.class))).thenReturn(call);
        SyncEngine gatedSyncEngine = new SyncEngine(
                appDatabase, messageApi, messageDao, conversationDao, reactionDao,
                tokenStore, moshi, sessionGate, operationTracker);

        TestObserver<Void> observer = gatedSyncEngine.sync().test();
        observer.awaitDone(5, TimeUnit.SECONDS).assertComplete();

        verify(appDatabase).runInTransaction(any(Runnable.class));
        verify(tokenStore, never()).saveSyncServerTime("user-a", "cursor-a");
        org.junit.Assert.assertEquals("Room and cursor each need an epoch fence", 2, fenceCalls.get());
    }

    private void assertDelayedResponseDiscardedAfterSessionChange(String nextUserId) throws Exception {
        when(messageDao.getAllMaxSeqs()).thenReturn(
                Single.just(List.of(new ConversationWatermark("conversation-a", 0L))));
        when(conversationDao.getAllWatermarks()).thenReturn(Single.just(Collections.emptyList()));
        when(tokenStore.readTokens()).thenReturn(Maybe.just(
                new Tokens(jwt("user-a"), 1800000000000L, "refresh-a")));

        CountDownLatch requestStarted = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        SyncResponse response = new SyncResponse(List.of(new SyncResponse.ConversationDeltaDto(
                "conversation-a",
                List.of(message("message-a", 1L, "conversation-a", "private A data")),
                List.of(), List.of(), List.of(), 1L, false)), "cursor-a");
        @SuppressWarnings("unchecked")
        Call<SyncResponse> call = mock(Call.class);
        when(call.clone()).thenReturn(call);
        try {
            when(call.execute()).thenAnswer(invocation -> {
                requestStarted.countDown();
                if (!releaseResponse.await(5, TimeUnit.SECONDS)) {
                    throw new java.io.IOException("test response was not released");
                }
                return Response.success(response);
            });
        } catch (java.io.IOException ignored) {
            throw new AssertionError(ignored);
        }
        when(messageApi.syncDeltas(any(SyncRequest.class))).thenReturn(call);

        TestObserver<Void> observer = syncEngine.sync().test();
        assertTrue("sync request should reach the delayed response", requestStarted.await(5, TimeUnit.SECONDS));

        // Simulate logout followed by either a different account or a fresh
        // login for the same account while the already-started response is held.
        when(tokenStore.readTokens()).thenReturn(Maybe.just(
                new Tokens(jwt(nextUserId), 1800000000000L, "refresh-next")));
        tokenRefresher.onSessionEstablished(2L);
        releaseResponse.countDown();

        observer.awaitDone(5, TimeUnit.SECONDS).assertComplete();
        verify(messageDao, never()).upsertAll(anyList());
        verify(tokenStore, never()).saveSyncServerTime("user-a", "cursor-a");
    }

    @Test
    public void syncAppliesDeltaPinFlagsAlongsideMutedAndArchived() {
        when(messageDao.getAllMaxSeqs()).thenReturn(
                Single.just(List.of(new ConversationWatermark("c1", 10L))));
        when(conversationDao.getAllWatermarks()).thenReturn(Single.just(Collections.emptyList()));
        when(tokenStore.readTokens()).thenReturn(Maybe.just(
                new Tokens(jwt("user-1"), 1800000000000L, "refresh")));

        SyncResponse.ConversationDeltaDto delta = new SyncResponse.ConversationDeltaDto(
                "c1",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                10L,
                false,
                null,
                false,
                true,
                "2026-11-09T15:00:00Z",
                true,
                true,
                "2026-11-09T14:00:00Z"
        );
        SyncResponse response = new SyncResponse(List.of(delta), "cursor-end");
        Call<SyncResponse> serverCall = responseCall(response);
        when(messageApi.syncDeltas(any(SyncRequest.class))).thenReturn(serverCall);

        syncEngine.sync().blockingAwait();

        verify(conversationDao).updateMute(eq("c1"), eq(true), eq(TimeAndJwt.isoToEpochMsOrNull("2026-11-09T15:00:00Z")));
        verify(conversationDao).updateArchived(eq("c1"), eq(true));
        verify(conversationDao).updatePin(eq("c1"), eq(true), eq(TimeAndJwt.isoToEpochMsOrNull("2026-11-09T14:00:00Z")));
    }

    private static MessageDto message(String id, long seq, String conversationId, String content) {
        return new MessageDto(
                id, seq, conversationId, "user-2", "Bob", null, null, "TEXT", content,
                null, null, List.of(), List.of(), "2026-11-08T10:00:00Z", null, null);
    }

    private static List<ConversationWatermark> watermarks(int count) {
        java.util.ArrayList<ConversationWatermark> result = new java.util.ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            result.add(new ConversationWatermark("conversation-" + index, index));
        }
        return result;
    }

    private static Call<SyncResponse> responseCall(SyncResponse response) {
        @SuppressWarnings("unchecked")
        Call<SyncResponse> call = mock(Call.class);
        when(call.clone()).thenReturn(call);
        try {
            when(call.execute()).thenReturn(Response.success(response));
        } catch (java.io.IOException ignored) {
            throw new AssertionError(ignored);
        }
        return call;
    }

    private static String jwt(String userId) {
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"sub\":\"" + userId + "\"}").getBytes(StandardCharsets.UTF_8));
        return "header." + payload + ".signature";
    }
}
