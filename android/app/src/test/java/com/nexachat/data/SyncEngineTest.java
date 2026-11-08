package com.nexachat.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexachat.core.database.AppDatabase;
import com.nexachat.core.database.ConversationDao;
import com.nexachat.core.database.ConversationWatermark;
import com.nexachat.core.database.MessageDao;
import com.nexachat.core.database.MessageEntity;
import com.nexachat.core.database.ReactionDao;
import com.nexachat.core.network.MessageApi;
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
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
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
    private TokenStore tokenStore;
    private Moshi moshi;
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
        when(messageDao.update(any(MessageEntity.class))).thenReturn(Completable.complete());
        when(messageDao.clearReplySnippets(anyString())).thenReturn(Completable.complete());

        syncEngine = new SyncEngine(
                appDatabase,
                messageApi,
                messageDao,
                conversationDao,
                reactionDao,
                tokenStore,
                moshi
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
        when(conversationDao.updateLastMessage(anyString(), anyLong(), anyString(), anyString(), anyLong()))
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
                anyLong()
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
                appDatabase, messageApi, messageDao, conversationDao, reactionDao, tokenStore, moshi);
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
        when(conversationDao.updateLastMessage(anyString(), anyLong(), anyString(), anyString(), anyLong()))
                .thenReturn(Completable.complete());

        TestObserver<Void> observer = syncEngine.sync().test();
        observer.awaitCount(1);
        observer.assertComplete();

        verify(conversationDao, never()).bumpPeerReadSeq(anyString(), anyLong());
        verify(conversationDao, never()).bumpPeerDeliveredSeq(anyString(), anyLong());
    }

    private static String jwt(String userId) {
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"sub\":\"" + userId + "\"}").getBytes(StandardCharsets.UTF_8));
        return "header." + payload + ".signature";
    }
}
