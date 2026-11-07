package com.nexachat.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nexachat.core.common.ApiError;
import com.nexachat.core.common.Result;
import com.nexachat.core.common.Unit;
import com.nexachat.core.database.ConversationDao;
import com.nexachat.core.database.MessageDao;
import com.nexachat.core.database.MessageEntity;
import com.nexachat.core.database.OutboxDao;
import com.nexachat.core.database.PinDao;
import com.nexachat.core.database.ReactionDao;
import com.nexachat.core.network.MessageApi;
import com.nexachat.core.network.SessionEvents;
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
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import retrofit2.Retrofit;
import retrofit2.converter.moshi.MoshiConverterFactory;

/** Resilience path: real Retrofit requests to a closed port must roll back optimistic writes. */
public class MessageRepositoryOfflineTest {
    private static final String CONVERSATION_ID = "c-offline";
    private static final String MESSAGE_ID = "m-offline";
    private static final String MY_ID = "me-1";
    private final AtomicReference<MessageEntity> row = new AtomicReference<>();
    private MessageDao messageDao;
    private ConversationDao conversationDao;
    private OutboxDao outboxDao;
    private ReactionDao reactionDao;
    private PinDao pinDao;
    private RealtimeSession session;
    private TokenStore tokenStore;
    private SessionEvents sessionEvents;
    private MessageRepository repository;

    @Before public void setUp() {
        RxJavaPlugins.setIoSchedulerHandler(ignored -> Schedulers.trampoline());
        messageDao = mock(MessageDao.class);
        conversationDao = mock(ConversationDao.class);
        outboxDao = mock(OutboxDao.class);
        reactionDao = mock(ReactionDao.class);
        pinDao = mock(PinDao.class);
        session = mock(RealtimeSession.class);
        tokenStore = mock(TokenStore.class);
        sessionEvents = mock(SessionEvents.class);
        row.set(new MessageEntity(MESSAGE_ID, CONVERSATION_ID, 7L, MY_ID, "Me", "TEXT", "original",
                null, null, null, null, 1_700_000_000_000L, null, null, null));
        when(messageDao.byId(anyString())).thenAnswer(call -> Maybe.fromCallable(row::get));
        when(messageDao.update(any())).thenAnswer(call -> Completable.fromAction(() -> row.set(call.getArgument(0))));
        when(reactionDao.forMessage(anyString())).thenReturn(Single.just(java.util.Collections.emptyList()));
        when(reactionDao.deleteForMessage(anyString())).thenReturn(Completable.complete());
        when(reactionDao.upsertAll(any())).thenReturn(Completable.complete());
        when(pinDao.find(anyString(), anyString())).thenReturn(Maybe.empty());
        when(pinDao.delete(anyString(), anyString())).thenReturn(Completable.complete());
        when(conversationDao.byId(anyString())).thenReturn(Maybe.empty());
        when(session.getEvents()).thenReturn(Flowable.<ChatEvent>empty());
        when(session.getIsOpen()).thenReturn(io.reactivex.rxjava3.processors.BehaviorProcessor.createDefault(true));
        when(session.getResyncRequests()).thenReturn(Flowable.empty());
        when(sessionEvents.getEvents()).thenReturn(Flowable.empty());
        when(tokenStore.readTokens()).thenReturn(Maybe.just(new Tokens(jwtFor(MY_ID), Long.MAX_VALUE, "refresh")));
        doNothing().when(session).ensureConnected();
        doNothing().when(session).watchConversation(anyString());
        doNothing().when(session).unwatchConversation(anyString());
        when(session.sendMessage(anyString(), any())).thenReturn(true);

        Moshi moshi = new Moshi.Builder().build();
        repository = new MessageRepository(unreachableApi(moshi), messageDao, conversationDao, outboxDao,
                reactionDao, pinDao, session, tokenStore, sessionEvents, moshi, new TestScheduler());
    }

    @After public void tearDown() {
        if (repository != null) repository.dispose();
        RxJavaPlugins.reset();
    }

    @Test public void editAgainstUnreachableBackendRestoresRowAndReturnsNetworkFailure() {
        Result<Unit> result = await(repository.editMessage(CONVERSATION_ID, MESSAGE_ID, "edited"));
        assertTrue("expected a failure, got " + result, result instanceof Result.Failure<?>);
        assertTrue("expected ApiError.Network, got " + ((Result.Failure<?>) result).getError(),
                ((Result.Failure<?>) result).getError() instanceof ApiError.Network);
        assertEquals("original", row.get().getContent());
        assertNull("optimistic editedAt survived rollback", row.get().getEditedAtEpochMs());
    }

    @Test public void deleteAgainstUnreachableBackendRestoresTombstone() {
        Result<Unit> result = await(repository.deleteMessage(CONVERSATION_ID, MESSAGE_ID));
        assertTrue("expected a failure, got " + result, result instanceof Result.Failure<?>);
        assertTrue("expected ApiError.Network, got " + ((Result.Failure<?>) result).getError(),
                ((Result.Failure<?>) result).getError() instanceof ApiError.Network);
        assertEquals("original", row.get().getContent());
        assertNull("optimistic tombstone survived rollback", row.get().getDeletedAtEpochMs());
    }

    /** A real Retrofit stack pointed at a port nothing listens on. */
    private MessageApi unreachableApi(Moshi moshi) {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(1, TimeUnit.SECONDS)
                .readTimeout(1, TimeUnit.SECONDS)
                .build();
        return new Retrofit.Builder()
                .baseUrl("http://127.0.0.1:1/")
                .client(client)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
                .create(MessageApi.class);
    }

    private String jwtFor(String userId) {
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"sub\":\"" + userId + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return "header." + payload + ".signature";
    }

    private static <T> T await(Single<T> source) {
        TestObserver<T> observer = source.test();
        observer.awaitDone(5, TimeUnit.SECONDS).assertNoErrors().assertComplete();
        return observer.values().get(0);
    }
}
