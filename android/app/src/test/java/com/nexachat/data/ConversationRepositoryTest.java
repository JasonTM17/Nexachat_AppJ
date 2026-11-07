package com.nexachat.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import com.nexachat.core.common.Result;
import com.nexachat.core.database.ConversationDao;
import com.nexachat.core.database.ConversationEntity;
import com.nexachat.core.network.GroupApi;
import com.nexachat.core.network.MessageApi;
import com.nexachat.core.network.dto.ConversationDto;
import com.nexachat.core.network.dto.ConversationInboxDto;
import com.nexachat.core.network.dto.ConversationMemberDto;
import com.nexachat.core.network.stomp.ChatEvent;
import com.nexachat.core.network.stomp.RealtimeSession;
import com.nexachat.core.storage.TokenStore;
import com.nexachat.core.storage.Tokens;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.processors.BehaviorProcessor;
import io.reactivex.rxjava3.processors.PublishProcessor;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import okhttp3.Headers;
import okhttp3.MultipartBody;
import okhttp3.Request;
import okhttp3.ResponseBody;
import okio.Timeout;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/** Covers complete-inbox reconciliation, write-through, and group refresh contracts. */
public final class ConversationRepositoryTest {

    private static final String MY_ID = "me-1";

    private final MessageApi messageApi = org.mockito.Mockito.mock(MessageApi.class);
    private final GroupApi groupApi = org.mockito.Mockito.mock(GroupApi.class);
    private final RealtimeSession session = org.mockito.Mockito.mock(RealtimeSession.class);
    private final TokenStore tokenStore = org.mockito.Mockito.mock(TokenStore.class);
    private final Context context = org.mockito.Mockito.mock(Context.class);
    private final ConversationDao conversationDao = new FakeConversationDao();
    private final PresenceRepository presenceRepository =
            org.mockito.Mockito.mock(PresenceRepository.class);
    private final BehaviorProcessor<Map<String, PeerPresence>> presenceFlow =
            BehaviorProcessor.createDefault(Collections.emptyMap());
    private final Moshi moshi = new Moshi.Builder().build();
    private final PublishProcessor<ChatEvent> chatEvents = PublishProcessor.create();
    private final com.squareup.moshi.JsonAdapter<List<ConversationInboxDto>> inboxAdapter =
            moshi.adapter(Types.newParameterizedType(List.class, ConversationInboxDto.class));

    private ConversationRepository repository;

    @Before
    public void setUp() throws Exception {
        when(session.getEvents()).thenReturn(chatEvents);
        when(session.getResyncRequests()).thenReturn(Flowable.empty());
        when(presenceRepository.getPresences()).thenReturn(presenceFlow);
        when(tokenStore.readTokens()).thenReturn(
                Maybe.just(new Tokens(jwtFor(MY_ID), Long.MAX_VALUE, "refresh")));
    }

    @After
    public void tearDown() {
        if (repository != null) repository.close();
    }

    private void buildRepository() {
        repository = new ConversationRepository(
                messageApi,
                groupApi,
                conversationDao,
                presenceRepository,
                session,
                tokenStore,
                moshi,
                context);
    }

    private <T> Call<T> asCall(Response<T> response) {
        return new ImmediateCall<>(response);
    }

    private void stubInbox(List<ConversationInboxDto> items) throws Exception {
        String json = inboxAdapter.toJson(items);
        when(messageApi.getConversations(any(), any()))
                .thenReturn(asCall(Response.success(ResponseBody.create(
                        okhttp3.MediaType.parse("application/json"), json))));
    }

    @Test
    public void refreshInboxDropsRowsTheServerNoLongerReturns() throws Exception {
        buildRepository();
        dao().upsertAll(List.of(conversation("conv-a"), conversation("conv-removed"))).blockingAwait();
        stubInbox(List.of(inboxDto("conv-a")));

        repository.refreshInbox().blockingGet();

        assertEquals(java.util.Set.of("conv-a"), dao().rows.keySet());
    }

    @Test
    public void refreshInboxClearsAllRowsOnAnEmptyAuthoritativeSet() throws Exception {
        buildRepository();
        dao().upsertAll(List.of(conversation("conv-a"), conversation("conv-b"))).blockingAwait();
        stubInbox(Collections.emptyList());

        repository.refreshInbox().blockingGet();

        assertTrue(dao().rows.isEmpty());
    }

    @Test
    public void refreshInboxMapsTheCallerMemberRoleIntoTheRow() throws Exception {
        buildRepository();
        stubInbox(List.of(inboxDto(
                "conv-g", "GROUP", "Team",
                List.of(member(MY_ID, "ADMIN"), member("u-other", "MEMBER")))));

        repository.refreshInbox().blockingGet();

        assertEquals("ADMIN", dao().rows.get("conv-g").getMyRole());
    }

    @Test
    public void refreshInboxPreservesPeerReadSeqAndKnownRoleAcrossPayloadGaps() throws Exception {
        buildRepository();
        dao().upsert(withPeerReadAndRole(conversation("conv-a"), 42L, "MEMBER")).blockingAwait();
        stubInbox(List.of(inboxDto("conv-a", "GROUP", "T", Collections.emptyList())));

        repository.refreshInbox().blockingGet();

        ConversationEntity row = dao().rows.get("conv-a");
        assertEquals(42L, row.getPeerReadSeq());
        assertEquals("MEMBER", row.getMyRole());
    }

    @Test
    public void refreshInboxKeepsAGroupJustCreatedWhenThePayloadPredatesIt() throws Exception {
        buildRepository();
        when(groupApi.createGroup(any())).thenReturn(
                asCall(Response.success(new ConversationDto("conv-new", "GROUP"))));
        stubInbox(List.of(inboxDto("conv-old")));

        repository.createGroup("New group", List.of("u-1")).blockingGet();
        repository.refreshInbox().blockingGet();

        assertEquals(java.util.Set.of("conv-old", "conv-new"), dao().rows.keySet());
    }

    @Test
    public void createdGroupIsPrunedLikeAnyRowOncePayloadHasListedIt() throws Exception {
        buildRepository();
        when(groupApi.createGroup(any())).thenReturn(
                asCall(Response.success(new ConversationDto("conv-new", "GROUP"))));
        repository.createGroup("New group", List.of("u-1")).blockingGet();

        stubInbox(List.of(inboxDto("conv-old"), inboxDto("conv-new")));
        repository.refreshInbox().blockingGet();
        stubInbox(List.of(inboxDto("conv-old")));
        repository.refreshInbox().blockingGet();

        assertEquals(java.util.Set.of("conv-old"), dao().rows.keySet());
    }

    @Test
    public void refreshInboxRequestsTheCompleteInboxAndNeverAPage() throws Exception {
        buildRepository();
        stubInbox(List.of(inboxDto("conv-a")));

        repository.refreshInbox().blockingGet();

        verify(messageApi, times(1)).getConversations(isNull(), isNull());
    }

    @Test
    public void paginatedInboxPayloadIsNeverUsedToPrune() throws Exception {
        buildRepository();
        dao().upsertAll(List.of(conversation("conv-a"), conversation("conv-b"))).blockingAwait();
        when(messageApi.getConversations(any(), any())).thenReturn(asCall(Response.success(
                ResponseBody.create(okhttp3.MediaType.parse("application/json"),
                        "{\"items\":[{\"id\":\"conv-a\"}],\"hasMore\":true}"))));

        repository.refreshInbox().blockingGet();

        assertEquals(java.util.Set.of("conv-a", "conv-b"), dao().rows.keySet());
    }

    @Test
    public void groupDetailFollowsResponseCursorToLaterInboxPages() throws Exception {
        buildRepository();
        String firstPage = "[{\"id\":\"conv-first\",\"type\":\"GROUP\",\"title\":\"First\",\"members\":[]}]";
        String secondPage = "[{\"id\":\"conv-target\",\"type\":\"GROUP\",\"title\":\"Target\",\"members\":[]}]";
        Headers headers = new Headers.Builder().add("X-Next-Cursor", "cursor-page-2").build();
        when(messageApi.getConversations(eq(100), isNull())).thenReturn(asCall(Response.success(
                ResponseBody.create(okhttp3.MediaType.parse("application/json"), firstPage), headers)));
        when(messageApi.getConversations(eq(100), eq("cursor-page-2"))).thenReturn(asCall(Response.success(
                ResponseBody.create(okhttp3.MediaType.parse("application/json"), secondPage))));

        Result<GroupDetail> result = repository.groupDetail("conv-target").blockingGet();

        assertEquals("Target", ((Result.Success<GroupDetail>) result).getValue().getTitle());
        verify(messageApi, times(1)).getConversations(eq(100), isNull());
        verify(messageApi, times(1)).getConversations(eq(100), eq("cursor-page-2"));
    }

    @Test
    public void groupAvatarReadStopsAfterConfiguredByteCap() throws Exception {
        buildRepository();
        Uri uri = org.mockito.Mockito.mock(Uri.class);
        int maxBytes = 25 * 1024 * 1024;
        int[] consumed = {0};
        InputStream input = new InputStream() {
            @Override
            public int read() {
                return consumed[0]++ < maxBytes * 2 ? 0 : -1;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) {
                int remaining = maxBytes * 2 - consumed[0];
                if (remaining <= 0) return -1;
                int count = Math.min(length, remaining);
                Arrays.fill(buffer, offset, offset + count, (byte) 0);
                consumed[0] += count;
                return count;
            }
        };
        ContentResolver resolver = org.mockito.Mockito.mock(ContentResolver.class);
        when(context.getContentResolver()).thenReturn(resolver);
        when(resolver.openInputStream(uri)).thenReturn(input);

        Result<Void> result = repository.setGroupAvatar("conv-g", uri).blockingGet();

        assertTrue(result instanceof Result.Failure<?>);
        assertTrue(consumed[0] <= maxBytes + 64 * 1024);
        verify(groupApi, never()).uploadAvatar(any(), any(MultipartBody.Part.class));
    }

    @Test
    public void directRowSeedsPeerPresenceAndExposesItOnTheSummary() throws Exception {
        buildRepository();
        String json = "[{\"id\":\"conv-d\",\"type\":\"DIRECT\","
                + "\"otherUser\":{\"userId\":\"u-peer\",\"displayName\":\"Peer\"},"
                + "\"peerPresence\":{\"online\":true,\"lastSeenAt\":null}}]";
        when(messageApi.getConversations(any(), any())).thenReturn(asCall(Response.success(
                ResponseBody.create(okhttp3.MediaType.parse("application/json"), json))));

        repository.refreshInbox().blockingGet();
        presenceFlow.onNext(Map.of("u-peer", new PeerPresence(true, null)));
        ConversationSummary summary = repository.inboxFlow().blockingFirst().get(0);

        assertEquals("u-peer", summary.getOtherUserId());
        assertEquals(new PeerPresence(true, null), summary.getPeerPresence());
        verify(presenceRepository).seed(eq(Map.of("u-peer", new PeerPresence(true, null))));
    }

    @Test
    public void deletedLastMessageIsFlaggedForTheNeutralInboxSnippet() throws Exception {
        buildRepository();
        String json = "[{\"id\":\"conv-d\",\"type\":\"DIRECT\",\"lastMessageSeq\":7,"
                + "\"lastMessage\":{\"seq\":7,\"type\":\"TEXT\",\"content\":null,"
                + "\"deletedAt\":\"2026-11-03T11:00:00Z\"}}]";
        when(messageApi.getConversations(any(), any())).thenReturn(asCall(Response.success(
                ResponseBody.create(okhttp3.MediaType.parse("application/json"), json))));

        repository.refreshInbox().blockingGet();
        ConversationSummary summary = repository.inboxFlow().blockingFirst().get(0);

        assertEquals(true, summary.getLastMessageDeleted());
        assertEquals(null, summary.getLastMessageContent());
    }

    @Test
    public void directRowWithoutPeerPresenceClearsStalePresenceSeed() throws Exception {
        buildRepository();
        presenceFlow.onNext(Map.of("u-peer", new PeerPresence(true, null)));
        doAnswer(invocation -> {
            String userId = invocation.getArgument(0);
            Map<String, PeerPresence> next = new LinkedHashMap<>(presenceFlow.getValue());
            next.remove(userId);
            presenceFlow.onNext(Collections.unmodifiableMap(next));
            return null;
        }).when(presenceRepository).clear(any());
        when(messageApi.getConversations(any(), any())).thenReturn(asCall(Response.success(
                ResponseBody.create(okhttp3.MediaType.parse("application/json"),
                        "[{\"id\":\"conv-d\",\"type\":\"DIRECT\","
                                + "\"otherUser\":{\"userId\":\"u-peer\"}}]"))));

        repository.refreshInbox().blockingGet();

        assertTrue("stale peer presence remained visible", presenceFlow.getValue().isEmpty());
        verify(presenceRepository).clear("u-peer");
    }

    @Test
    public void conversationUpdatedSignalsOnlyMatchingGroupDetailListener() {
        buildRepository();
        TestSubscriber<Boolean> updates = repository.groupUpdates("conv-a").test();
        chatEvents.onNext(new ChatEvent.ConversationUpdated("conv-a"));
        updates.assertValue(Boolean.TRUE);

        chatEvents.onNext(new ChatEvent.ConversationUpdated("conv-b"));
        chatEvents.onNext(new ChatEvent.ConversationUpdated(null));
        updates.assertValueCount(1).assertNoErrors();
        updates.cancel();
    }

    private FakeConversationDao dao() {
        return (FakeConversationDao) conversationDao;
    }

    private ConversationEntity conversation(String id) {
        return new ConversationEntity(
                id, "GROUP", id, 0L, null, null, null, 0,
                null, null, null, 0L, null, null, false);
    }

    private ConversationEntity withPeerReadAndRole(
            ConversationEntity row,
            long peerReadSeq,
            String role) {
        return new ConversationEntity(
                row.getId(), row.getType(), row.getTitle(), row.getLastMessageSeq(),
                row.getLastMessageContent(), row.getLastMessageSenderName(), row.getLastMessageAt(),
                row.getUnreadCount(), row.getOtherUserName(), row.getOtherUserAvatar(),
                row.getAvatarUrl(), peerReadSeq, role, row.getOtherUserId(), row.getLastMessageDeleted());
    }

    private ConversationInboxDto inboxDto(String id) {
        return inboxDto(id, "GROUP", "T", List.of(member(MY_ID, "MEMBER")));
    }

    private ConversationInboxDto inboxDto(
            String id,
            String type,
            String title,
            List<ConversationMemberDto> members) {
        return new ConversationInboxDto(id, type, title, members);
    }

    private ConversationMemberDto member(String userId, String role) {
        return new ConversationMemberDto(userId, "n-" + userId, role);
    }

    private String jwtFor(String userId) {
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"sub\":\"" + userId + "\"}").getBytes(StandardCharsets.UTF_8));
        return "header." + payload + ".signature";
    }

    private final class FakeConversationDao implements ConversationDao {
        private final Map<String, ConversationEntity> rows = new LinkedHashMap<>();
        private final BehaviorProcessor<List<ConversationEntity>> inbox =
                BehaviorProcessor.createDefault(Collections.emptyList());

        @Override
        public Completable upsert(ConversationEntity conversation) {
            return Completable.fromAction(() -> {
                rows.put(conversation.getId(), conversation);
                publish();
            });
        }

        @Override
        public Completable upsertAll(List<ConversationEntity> conversations) {
            return Completable.fromAction(() -> {
                for (ConversationEntity row : conversations) rows.put(row.getId(), row);
                publish();
            });
        }

        @Override
        public Flowable<List<ConversationEntity>> inboxFlow() {
            return inbox;
        }

        @Override
        public Flowable<List<ConversationEntity>> observeById(String conversationId) {
            return inbox.map(ignored -> {
                        ConversationEntity row = rows.get(conversationId);
                        return row == null ? Collections.<ConversationEntity>emptyList() : List.of(row);
                    })
                    .distinctUntilChanged();
        }

        @Override
        public Maybe<ConversationEntity> byId(String conversationId) {
            return Maybe.defer(() -> {
                ConversationEntity row = rows.get(conversationId);
                return row == null ? Maybe.empty() : Maybe.just(row);
            });
        }

        @Override
        public Single<List<ConversationEntity>> byIds(List<String> ids) {
            return Single.fromCallable(() -> {
                List<ConversationEntity> found = new ArrayList<>();
                for (String id : ids) {
                    ConversationEntity row = rows.get(id);
                    if (row != null) found.add(row);
                }
                return found;
            });
        }

        @Override
        public Completable bumpPeerReadSeq(String conversationId, long seq) {
            return Completable.fromAction(() -> {
                ConversationEntity row = rows.get(conversationId);
                if (row != null) rows.put(conversationId, withPeerReadAndRole(
                        row, Math.max(row.getPeerReadSeq(), seq), row.getMyRole()));
                publish();
            });
        }

        @Override
        public Completable clearUnread(String conversationId) {
            return Completable.fromAction(() -> {
                ConversationEntity row = rows.get(conversationId);
                if (row != null) rows.put(conversationId, withUnread(row, 0));
                publish();
            });
        }

        @Override
        public Completable updateLastMessage(
                String conversationId, long seq, String content, String senderName, long at) {
            return Completable.fromAction(() -> {
                ConversationEntity row = rows.get(conversationId);
                if (row != null && seq > row.getLastMessageSeq()) {
                    rows.put(conversationId, withLastMessage(row, seq, content, senderName, at, false));
                }
                publish();
            });
        }

        @Override
        public Completable updateLastMessageContent(String conversationId, long seq, String content) {
            return Completable.fromAction(() -> {
                ConversationEntity row = rows.get(conversationId);
                if (row != null && row.getLastMessageSeq() == seq) {
                    rows.put(conversationId, withLastMessage(row, seq, content,
                            row.getLastMessageSenderName(), row.getLastMessageAt(), false));
                }
                publish();
            });
        }

        @Override
        public Completable markLastMessageDeleted(String conversationId) {
            return Completable.fromAction(() -> {
                ConversationEntity row = rows.get(conversationId);
                if (row != null) rows.put(conversationId, withLastMessage(row,
                        row.getLastMessageSeq(), null, row.getLastMessageSenderName(),
                        row.getLastMessageAt(), true));
                publish();
            });
        }

        @Override
        public Completable incrementUnread(String conversationId, int delta) {
            return Completable.fromAction(() -> {
                ConversationEntity row = rows.get(conversationId);
                if (row != null) rows.put(conversationId, withUnread(row, row.getUnreadCount() + delta));
                publish();
            });
        }

        @Override
        public Completable clear() {
            return Completable.fromAction(() -> {
                rows.clear();
                publish();
            });
        }

        @Override
        public Completable deleteAllExcept(List<String> ids) {
            return Completable.fromAction(() -> {
                rows.keySet().retainAll(ids);
                publish();
            });
        }

        private void publish() {
            inbox.onNext(Collections.unmodifiableList(new ArrayList<>(rows.values())));
        }

        private ConversationEntity withUnread(ConversationEntity row, int unread) {
            return new ConversationEntity(
                    row.getId(), row.getType(), row.getTitle(), row.getLastMessageSeq(),
                    row.getLastMessageContent(), row.getLastMessageSenderName(), row.getLastMessageAt(),
                    unread, row.getOtherUserName(), row.getOtherUserAvatar(), row.getAvatarUrl(),
                    row.getPeerReadSeq(), row.getMyRole(), row.getOtherUserId(), row.getLastMessageDeleted());
        }

        private ConversationEntity withLastMessage(
                ConversationEntity row, long seq, String content, String sender, Long at, boolean deleted) {
            return new ConversationEntity(
                    row.getId(), row.getType(), row.getTitle(), seq, content, sender, at,
                    row.getUnreadCount(), row.getOtherUserName(), row.getOtherUserAvatar(), row.getAvatarUrl(),
                    row.getPeerReadSeq(), row.getMyRole(), row.getOtherUserId(), deleted);
        }
    }

    private static final class ImmediateCall<T> implements Call<T> {
        private final Response<T> response;
        private boolean executed;
        private boolean canceled;

        private ImmediateCall(Response<T> response) {
            this.response = response;
        }

        @Override
        public Response<T> execute() {
            executed = true;
            return response;
        }

        @Override
        public void enqueue(Callback<T> callback) {
            executed = true;
            callback.onResponse(this, response);
        }

        @Override
        public boolean isExecuted() { return executed; }
        @Override
        public void cancel() { canceled = true; }
        @Override
        public boolean isCanceled() { return canceled; }
        @Override
        public Call<T> clone() { return new ImmediateCall<>(response); }
        @Override
        public Request request() { return new Request.Builder().url("https://example.invalid/").build(); }
        @Override
        public Timeout timeout() { return Timeout.NONE; }
    }
}
