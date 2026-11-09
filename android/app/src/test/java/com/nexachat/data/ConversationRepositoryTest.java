package com.nexachat.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import com.nexachat.core.common.Result;
import com.nexachat.core.database.AppDatabase;
import com.nexachat.core.database.ConversationDao;
import com.nexachat.core.database.ConversationEntity;
import com.nexachat.core.database.ConversationWatermark;
import com.nexachat.core.database.MessageDao;
import com.nexachat.core.database.OutboxDao;
import com.nexachat.core.database.PinDao;
import com.nexachat.core.database.ReactionDao;
import com.nexachat.core.network.GroupApi;
import com.nexachat.core.network.MessageApi;
import com.nexachat.core.network.dto.ConversationDto;
import com.nexachat.core.network.dto.ConversationAiOptOutDto;
import com.nexachat.core.network.dto.ConversationInboxDto;
import com.nexachat.core.network.dto.InboxLastMessageDto;
import com.nexachat.core.network.dto.ConversationMemberDto;
import com.nexachat.core.network.dto.MuteRequest;
import com.nexachat.core.network.dto.SyncRequest;
import com.nexachat.core.network.dto.SyncResponse;
import com.nexachat.core.network.stomp.ChatEvent;
import com.nexachat.core.network.stomp.RealtimeSession;
import com.nexachat.core.storage.TokenStore;
import com.nexachat.core.storage.Tokens;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.processors.BehaviorProcessor;
import io.reactivex.rxjava3.processors.PublishProcessor;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
import java.io.InputStream;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
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
import org.mockito.ArgumentCaptor;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/** Covers complete-inbox reconciliation, write-through, and group refresh contracts. */
public final class ConversationRepositoryTest {

    private static final String MY_ID = "me-1";

    private final MessageApi messageApi = org.mockito.Mockito.mock(MessageApi.class);
    private final GroupApi groupApi = org.mockito.Mockito.mock(GroupApi.class);
    private final com.nexachat.core.network.ConversationSettingsApi settingsApi =
            org.mockito.Mockito.mock(com.nexachat.core.network.ConversationSettingsApi.class);
    private final RealtimeSession session = org.mockito.Mockito.mock(RealtimeSession.class);
    private final TokenStore tokenStore = org.mockito.Mockito.mock(TokenStore.class);
    private final Context context = org.mockito.Mockito.mock(Context.class);
    private final ConversationDao conversationDao = new FakeConversationDao();
    private final AppDatabase appDatabase = org.mockito.Mockito.mock(AppDatabase.class);
    private final MessageDao messageDao = org.mockito.Mockito.mock(MessageDao.class);
    private final ReactionDao reactionDao = org.mockito.Mockito.mock(ReactionDao.class);
    private final PinDao pinDao = org.mockito.Mockito.mock(PinDao.class);
    private final OutboxDao outboxDao = org.mockito.Mockito.mock(OutboxDao.class);
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
        RxJavaPlugins.setIoSchedulerHandler(ignored -> Schedulers.trampoline());
        RxJavaPlugins.setComputationSchedulerHandler(ignored -> Schedulers.trampoline());
        RxAndroidPlugins.setInitMainThreadSchedulerHandler(ignored -> Schedulers.trampoline());
        reset(messageApi, groupApi, settingsApi, session, tokenStore, context, appDatabase,
                messageDao, reactionDao, pinDao, outboxDao, presenceRepository);
        conversationDao.clear().blockingAwait();
        when(session.getEvents()).thenReturn(chatEvents);
        when(session.getResyncRequests()).thenReturn(Flowable.empty());
        when(presenceRepository.getPresences()).thenReturn(presenceFlow);
        when(tokenStore.readTokens()).thenReturn(
                Maybe.just(new Tokens(jwtFor(MY_ID), Long.MAX_VALUE, "refresh")));
        doAnswer(invocation -> {
            Runnable runnable = invocation.getArgument(0);
            runnable.run();
            return null;
        }).when(appDatabase).runInTransaction(any(Runnable.class));
        when(appDatabase.messageDao()).thenReturn(messageDao);
        when(appDatabase.reactionDao()).thenReturn(reactionDao);
        when(appDatabase.pinDao()).thenReturn(pinDao);
        when(appDatabase.outboxDao()).thenReturn(outboxDao);
        when(appDatabase.conversationDao()).thenReturn(conversationDao);
        when(messageDao.deleteForConversation(any())).thenReturn(Completable.complete());
        when(reactionDao.deleteForConversation(any())).thenReturn(Completable.complete());
        when(pinDao.deleteForConversation(any())).thenReturn(Completable.complete());
        when(outboxDao.deleteForConversation(any(), any())).thenReturn(Completable.complete());
    }

    @After
    public void tearDown() {
        if (repository != null) repository.close();
        RxJavaPlugins.reset();
        RxAndroidPlugins.reset();
    }

    private void buildRepository() {
        repository = new ConversationRepository(
                messageApi,
                groupApi,
                settingsApi,
                conversationDao,
                presenceRepository,
                session,
                tokenStore,
                moshi,
                context,
                appDatabase);
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

    private void stubInboxPage(String requestCursor, String nextCursor, String body) {
        Call<ResponseBody> call = asCall(inboxResponse(body, nextCursor));
        if (requestCursor == null) {
            when(messageApi.getConversations(eq(30), isNull())).thenReturn(call);
        } else {
            when(messageApi.getConversations(eq(30), eq(requestCursor))).thenReturn(call);
        }
    }

    private void stubArchivedInbox(String body) {
        when(messageApi.getArchivedConversations(eq(30), isNull())).thenReturn(
                asCall(Response.success(ResponseBody.create(
                        okhttp3.MediaType.parse("application/json"), body))));
    }

    private void stubArchivedInboxPage(String requestCursor, String nextCursor, String body) {
        Call<ResponseBody> call = asCall(inboxResponse(body, nextCursor));
        if (requestCursor == null) {
            when(messageApi.getArchivedConversations(eq(30), isNull())).thenReturn(call);
        } else {
            when(messageApi.getArchivedConversations(eq(30), eq(requestCursor))).thenReturn(call);
        }
    }

    private Response<ResponseBody> inboxResponse(String body, String nextCursor) {
        Headers.Builder headers = new Headers.Builder();
        if (nextCursor != null) headers.add("X-Next-Cursor", nextCursor);
        return Response.success(
                ResponseBody.create(okhttp3.MediaType.parse("application/json"), body),
                headers.build());
    }

    private String inboxJson(Object... idAndSequences) {
        return inboxJson(false, idAndSequences);
    }

    private String inboxJsonArchived(Object... idAndSequences) {
        return inboxJson(true, idAndSequences);
    }

    private String inboxJson(boolean archived, Object... idAndSequences) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i + 1 < idAndSequences.length; i += 2) {
            if (i > 0) json.append(',');
            String id = String.valueOf(idAndSequences[i]);
            long seq = ((Number) idAndSequences[i + 1]).longValue();
            String createdAt = Instant.ofEpochSecond(seq).toString();
            json.append("{\"id\":\"").append(id)
                    .append("\",\"type\":\"GROUP\",\"title\":\"").append(id)
                    .append("\",\"members\":[]");
            if (archived) json.append(",\"archived\":true");
            json.append(",\"lastMessage\":{\"seq\":")
                    .append(seq)
                    .append(",\"type\":\"TEXT\",\"contentSnippet\":\"message-")
                    .append(seq)
                    .append("\",\"senderId\":\"sender\",\"createdAt\":\"")
                    .append(createdAt)
                    .append("\"}}");
        }
        return json.append(']').toString();
    }

    private String inboxJsonWithCount(int count) {
        Object[] idAndSequences = new Object[count * 2];
        for (int i = 0; i < count; i++) {
            idAndSequences[i * 2] = "page-" + i;
            idAndSequences[i * 2 + 1] = count - i + 10L;
        }
        return inboxJson(idAndSequences);
    }

    @Test
    public void refreshInboxDropsRowsTheServerNoLongerReturns() throws Exception {
        buildRepository();
        dao().upsertAll(List.of(conversation("conv-a", 20), conversation("conv-removed", 2)))
                .blockingAwait();
        stubInbox(List.of(inboxDto("conv-a", 20)));

        repository.refreshInbox().blockingGet();

        assertEquals(java.util.Set.of("conv-a"), dao().rows.keySet());
    }

    @Test
    public void refreshInboxSinglePageConvergesWhenRemovedRowHadHigherSequence() throws Exception {
        buildRepository();
        dao().upsertAll(List.of(conversation("conv-a", 20), conversation("conv-removed", 25)))
                .blockingAwait();
        stubInbox(List.of(inboxDto("conv-a", 20)));

        repository.refreshInbox().blockingGet();

        assertEquals(java.util.Set.of("conv-a"), dao().rows.keySet());
    }

    @Test
    public void refreshInboxFirstPageOnlyConvergesViaDeltaEndpointConfirmation() throws Exception {
        buildRepository();
        dao().upsertAll(List.of(
                conversation("conv-a", 100),
                conversation("conv-removed", 110),
                conversation("conv-page2", 10))).blockingAwait();
        stubInboxPage(null, "cursor-page-2", inboxJson("conv-a", 100));

        // Delta sync confirms absence of conv-removed (returns only conv-a)
        SyncResponse deltaResponse = new SyncResponse(List.of(
                new SyncResponse.ConversationDeltaDto("conv-a", List.of(), List.of(), List.of(), List.of(), 100L, false)),
                "2026-11-08T10:00:00Z");
        when(messageApi.syncDeltas(any(SyncRequest.class))).thenReturn(asCall(Response.success(deltaResponse)));

        repository.refreshInbox().blockingGet();

        assertEquals(java.util.Set.of("conv-a", "conv-page2"), dao().rows.keySet());
    }

    @Test
    public void refreshInboxFirstPageDoesNotDeleteConversationThatMovedPages() throws Exception {
        buildRepository();
        dao().upsertAll(List.of(
                conversation("conv-a", 100),
                conversation("conv-moved", 110),
                conversation("conv-page2", 10))).blockingAwait();
        stubInboxPage(null, "cursor-page-2", inboxJson("conv-a", 100));

        // Delta sync confirms conv-moved is still accessible by returning its delta
        SyncResponse deltaResponse = new SyncResponse(List.of(
                new SyncResponse.ConversationDeltaDto("conv-moved", List.of(), List.of(), List.of(), List.of(), 110L, false)),
                "2026-11-08T10:00:00Z");
        when(messageApi.syncDeltas(any(SyncRequest.class))).thenReturn(asCall(Response.success(deltaResponse)));

        repository.refreshInbox().blockingGet();

        assertEquals(java.util.Set.of("conv-a", "conv-moved", "conv-page2"), dao().rows.keySet());
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

        stubInbox(List.of(inboxDto("conv-old", 20), inboxDto("conv-new")));
        repository.refreshInbox().blockingGet();
        dao().upsert(conversation("conv-new", 2)).blockingAwait();
        stubInbox(List.of(inboxDto("conv-old", 20)));
        repository.refreshInbox().blockingGet();

        assertEquals(java.util.Set.of("conv-old"), dao().rows.keySet());
    }

    @Test
    public void refreshInboxRequestsTheFirstThirtyItemPage() throws Exception {
        buildRepository();
        stubInbox(List.of(inboxDto("conv-a")));

        repository.refreshInbox().blockingGet();

        verify(messageApi, times(1)).getConversations(eq(30), isNull());
    }

    @Test
    public void labelFilterUsesUnpagedServerQueryAndPreservesUnrelatedRoomRows() throws Exception {
        buildRepository();
        dao().upsert(conversation("cached-unrelated", 40)).blockingAwait();
        when(messageApi.getConversations(isNull(), isNull(), eq("label-1")))
                .thenReturn(asCall(Response.success(ResponseBody.create(
                        okhttp3.MediaType.parse("application/json"),
                        "[{\"id\":\"labeled\",\"type\":\"GROUP\","
                                + "\"title\":\"Labeled\",\"members\":[],"
                                + "\"labels\":[\"label-1\"]}]"))));

        Result<java.util.Set<String>> result = repository.refreshInboxByLabel("label-1").blockingGet();

        assertEquals(java.util.Set.of("labeled"),
                ((Result.Success<java.util.Set<String>>) result).getValue());
        assertEquals(java.util.Set.of("cached-unrelated", "labeled"), dao().rows.keySet());
        assertEquals(List.of("label-1"), dao().rows.get("labeled").getLabelIds());
        verify(messageApi, times(1)).getConversations(isNull(), isNull(), eq("label-1"));
    }

    @Test
    public void partialInboxPageIsNeverUsedToPrune() throws Exception {
        buildRepository();
        dao().upsertAll(List.of(conversation("conv-a", 1), conversation("conv-b", 2)))
                .blockingAwait();
        stubInboxPage(null, "cursor-page-2", inboxJson("conv-a", 50));

        Result<Boolean> result = repository.refreshInboxPage().blockingGet();

        assertTrue(((Result.Success<Boolean>) result).getValue());
        assertEquals(java.util.Set.of("conv-a", "conv-b"), dao().rows.keySet());
    }

    @Test
    public void completeWalkPrunesOnlyRowsOlderThanItsOldestFetchedSequence() throws Exception {
        buildRepository();
        dao().upsertAll(List.of(
                conversation("local-old", 10), conversation("local-newer", 120),
                conversation("local-empty", 0)))
                .blockingAwait();
        stubInboxPage(null, "cursor-page-2", inboxJson("server-new", 100));

        Result<Boolean> first = repository.refreshInboxPage().blockingGet();
        assertTrue(((Result.Success<Boolean>) first).getValue());
        assertTrue("partial first page must not prune", dao().rows.containsKey("local-old"));

        stubInboxPage("cursor-page-2", null, inboxJson("server-old", 50));
        Result<Boolean> last = repository.loadNextInboxPage().blockingGet();

        assertFalse(((Result.Success<Boolean>) last).getValue());
        assertEquals(java.util.Set.of(
                "local-newer", "local-empty", "server-new", "server-old"), dao().rows.keySet());
    }

    @Test
    public void zeroOneAndExactlyPageSizeInboxesAreHandledWithoutExtraRequests() throws Exception {
        buildRepository();
        dao().upsert(conversation("stale", 1)).blockingAwait();

        stubInboxPage(null, null, "[]");
        Result<Boolean> empty = repository.refreshInboxPage().blockingGet();
        assertFalse(((Result.Success<Boolean>) empty).getValue());
        assertTrue(dao().rows.isEmpty());

        stubInboxPage(null, null, inboxJson("only", 2));
        repository.refreshInboxPage().blockingGet();
        assertEquals(java.util.Set.of("only"), dao().rows.keySet());

        stubInboxPage(null, null, inboxJsonWithCount(30));
        repository.refreshInboxPage().blockingGet();
        assertEquals(30, dao().rows.size());
        verify(messageApi, times(3)).getConversations(eq(30), isNull());
    }

    @Test
    public void exhaustedPageStopsFurtherRequests() throws Exception {
        buildRepository();
        stubInboxPage(null, "cursor-page-2", inboxJson("conv-a", 20));
        repository.refreshInboxPage().blockingGet();
        stubInboxPage("cursor-page-2", null, inboxJson("conv-b", 10));

        Result<Boolean> last = repository.loadNextInboxPage().blockingGet();
        Result<Boolean> exhausted = repository.loadNextInboxPage().blockingGet();

        assertFalse(((Result.Success<Boolean>) last).getValue());
        assertFalse(((Result.Success<Boolean>) exhausted).getValue());
        verify(messageApi, times(1)).getConversations(eq(30), eq("cursor-page-2"));
    }

    @Test
    public void failedPageCanBeRetriedAtTheSameCursor() throws Exception {
        buildRepository();
        stubInboxPage(null, "cursor-retry", inboxJson("conv-a", 20));
        repository.refreshInboxPage().blockingGet();
        when(messageApi.getConversations(eq(30), eq("cursor-retry")))
                .thenReturn(asCall(Response.error(503, ResponseBody.create(
                        okhttp3.MediaType.parse("application/json"), "{}"))))
                .thenReturn(asCall(inboxResponse(inboxJson("conv-b", 10), null)));

        Result<Boolean> failed = repository.loadNextInboxPage().blockingGet();
        Result<Boolean> retried = repository.loadNextInboxPage().blockingGet();

        assertTrue(failed instanceof Result.Failure<?>);
        assertTrue("retry should be successful: " + retried, retried instanceof Result.Success<?>);
        assertFalse(((Result.Success<Boolean>) retried).getValue());
        assertEquals(java.util.Set.of("conv-a", "conv-b"), dao().rows.keySet());
        verify(messageApi, times(2)).getConversations(eq(30), eq("cursor-retry"));
    }

    @Test
    public void duplicateConversationAcrossPagesIsUpsertedOnceWithoutActivityRegression()
            throws Exception {
        buildRepository();
        stubInboxPage(null, "cursor-duplicate", inboxJson("shared", 80, "top", 100));
        repository.refreshInboxPage().blockingGet();
        stubInboxPage("cursor-duplicate", null, inboxJson("shared", 70, "older", 50));

        repository.loadNextInboxPage().blockingGet();

        assertEquals(java.util.Set.of("shared", "top", "older"), dao().rows.keySet());
        assertEquals(80L, dao().rows.get("shared").getLastMessageSeq());
    }

    @Test
    public void refreshBetweenPagesKeepsConversationMovedByNewMessageInStableOrder()
            throws Exception {
        buildRepository();
        stubInboxPage(null, "cursor-before-message", inboxJson("top-a", 100, "top-b", 90));
        repository.refreshInboxPage().blockingGet();

        // This conversation was below page one, then its new message moved it to the top.
        stubInboxPage(null, "cursor-after-message", inboxJson("moved", 110, "top-a", 100));
        repository.refreshInboxPage().blockingGet();
        stubInboxPage("cursor-after-message", null, inboxJson("top-b", 90, "older", 80));
        repository.loadNextInboxPage().blockingGet();

        List<ConversationEntity> ordered = dao().inboxFlow().blockingFirst();
        assertEquals(List.of("moved", "top-a", "top-b", "older"), ordered.stream()
                .map(ConversationEntity::getId).toList());
        verify(messageApi, never()).getConversations(eq(30), eq("cursor-before-message"));
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

    @Test
    public void muteWritesThePresetInstantAndLocalFlag() {
        buildRepository();
        dao().upsert(conversation("conv-m")).blockingAwait();
        Instant until = Instant.parse("2026-11-10T01:00:00Z");
        when(settingsApi.mute(eq("conv-m"), eq(MuteRequest.until(until))))
                .thenReturn(asCall(Response.<Void>success(204, null)));

        Result<Void> result = repository.muteConversation("conv-m", until).blockingGet();

        assertTrue(result instanceof Result.Success<?>);
        ConversationEntity row = dao().rows.get("conv-m");
        assertTrue(row.isMuted());
        assertEquals(Long.valueOf(until.toEpochMilli()), row.getMutedUntilEpochMs());
    }

    @Test
    public void muteForeverSendsTheForeverFlagWithoutAnExpiry() {
        buildRepository();
        dao().upsert(conversation("conv-m")).blockingAwait();
        when(settingsApi.mute(eq("conv-m"), eq(MuteRequest.forever())))
                .thenReturn(asCall(Response.<Void>success(204, null)));

        Result<Void> result = repository.muteConversationForever("conv-m").blockingGet();

        assertTrue(result instanceof Result.Success<?>);
        ConversationEntity row = dao().rows.get("conv-m");
        assertTrue(row.isMuted());
        assertNull(row.getMutedUntilEpochMs());
    }

    @Test
    public void failedUnmuteLeavesTheLocalMuteStateUntouched() {
        buildRepository();
        ConversationEntity muted = withPreferences(
                conversation("conv-m"), true, 2_000L, false, false);
        dao().upsert(muted).blockingAwait();
        when(settingsApi.unmute("conv-m")).thenReturn(asCall(Response.error(403,
                ResponseBody.create(okhttp3.MediaType.parse("application/json"),
                        "{\"error\":{\"code\":\"NOT_MEMBER\"}}"))));

        Result<Void> result = repository.unmuteConversation("conv-m").blockingGet();

        assertTrue(result instanceof Result.Failure<?>);
        assertTrue(dao().rows.get("conv-m").isMuted());
        assertEquals(Long.valueOf(2_000L), dao().rows.get("conv-m").getMutedUntilEpochMs());
    }

    @Test
    public void archiveAndUnarchiveWriteThroughToRoom() {
        buildRepository();
        dao().upsert(conversation("conv-a")).blockingAwait();
        when(settingsApi.archive("conv-a"))
                .thenReturn(asCall(Response.<Void>success(204, null)));
        when(settingsApi.unarchive("conv-a"))
                .thenReturn(asCall(Response.<Void>success(204, null)));

        repository.setConversationArchived("conv-a", true).blockingGet();
        assertTrue(dao().rows.get("conv-a").isArchived());

        repository.setConversationArchived("conv-a", false).blockingGet();
        assertFalse(dao().rows.get("conv-a").isArchived());
    }

    @Test
    public void aiOptOutReadsWritesAndClearsTheCallerState() {
        buildRepository();
        dao().upsert(conversation("conv-a")).blockingAwait();
        when(settingsApi.getAiOptOut("conv-a")).thenReturn(asCall(Response.success(
                new ConversationAiOptOutDto("conv-a", true))));
        when(settingsApi.optOutOfAi("conv-a")).thenReturn(asCall(Response.success(
                new ConversationAiOptOutDto("conv-a", true))));
        when(settingsApi.clearAiOptOut("conv-a")).thenReturn(asCall(Response.success(
                new ConversationAiOptOutDto("conv-a", false))));

        Result<Boolean> read = repository.getAiOptOut("conv-a").blockingGet();
        assertEquals(Boolean.TRUE, ((Result.Success<?>) read).getValue());
        assertTrue(dao().rows.get("conv-a").isAiOptOut());

        Result<Boolean> cleared = repository.setAiOptOut("conv-a", false).blockingGet();
        assertEquals(Boolean.FALSE, ((Result.Success<?>) cleared).getValue());
        assertFalse(dao().rows.get("conv-a").isAiOptOut());
    }

    @Test
    public void inboxPayloadWritesTheServerAiOptOutThrough() throws Exception {
        buildRepository();
        dao().upsert(conversation("conv-a")).blockingAwait();
        stubInboxPage(null, null,
                "[{\"id\":\"conv-a\",\"type\":\"GROUP\",\"title\":\"A\","
                        + "\"members\":[],\"aiOptOut\":true}]");

        repository.refreshInbox().blockingGet();
        assertTrue(dao().rows.get("conv-a").isAiOptOut());

        stubInboxPage(null, null,
                "[{\"id\":\"conv-a\",\"type\":\"GROUP\",\"title\":\"A\","
                        + "\"members\":[],\"aiOptOut\":false}]");

        repository.refreshInbox().blockingGet();
        assertFalse(dao().rows.get("conv-a").isAiOptOut());
    }

    @Test
    public void archivedInboxUsesTheArchivedEndpointAndPersistsTheFlag() {
        buildRepository();
        stubArchivedInbox("[{\"id\":\"conv-arch\",\"type\":\"GROUP\",\"title\":\"Arch\","
                + "\"archived\":true,\"members\":[]}]");

        Result<Boolean> result = repository.refreshInboxPage(true).blockingGet();

        assertTrue(result instanceof Result.Success<?>);
        assertTrue(dao().rows.get("conv-arch").isArchived());
        verify(messageApi).getArchivedConversations(eq(30), isNull());
        verify(messageApi, never()).getConversations(any(), any());
    }

    @Test
    public void archivedPagingContinuesOnTheArchivedEndpoint() throws Exception {
        buildRepository();
        stubArchivedInboxPage(null, "cursor-arch-2", inboxJsonArchived("conv-a", 10));
        stubArchivedInboxPage("cursor-arch-2", null, inboxJsonArchived("conv-b", 5));

        Result<Boolean> first = repository.refreshInboxPage(true).blockingGet();
        assertTrue(((Result.Success<Boolean>) first).getValue());
        Result<Boolean> second = repository.loadNextInboxPage().blockingGet();

        assertFalse(((Result.Success<Boolean>) second).getValue());
        verify(messageApi).getArchivedConversations(eq(30), eq("cursor-arch-2"));
        verify(messageApi, never()).getConversations(any(), any());
        assertEquals(java.util.Set.of("conv-a", "conv-b"), dao().rows.keySet());
    }

    @Test
    public void completedWalksPruneOnlyTheirOwnInboxVariant() throws Exception {
        buildRepository();
        dao().upsertAll(List.of(
                conversation("conv-inbox"),
                withPreferences(conversation("conv-arch"), false, null, true, false)))
                .blockingAwait();
        stubInbox(List.of());

        repository.refreshInboxPage(false).blockingGet();

        assertFalse("default walk must not delete archived rows",
                dao().rows.containsKey("conv-inbox"));
        assertTrue(dao().rows.containsKey("conv-arch"));

        stubArchivedInbox("[]");
        repository.refreshInboxPage(true).blockingGet();

        assertTrue(dao().rows.isEmpty());
    }

    @Test
    public void pagedDefaultWalkPrunesOnlyConfirmedDefaultRows() throws Exception {
        buildRepository();
        dao().upsertAll(List.of(
                conversation("conv-a", 100),
                conversation("conv-removed", 120),
                withPreferences(conversation("conv-arch", 110), false, null, true, false)))
                .blockingAwait();
        stubInboxPage(null, "cursor-page-2", inboxJson("conv-a", 100));

        SyncResponse deltaResponse = new SyncResponse(List.of(
                new SyncResponse.ConversationDeltaDto(
                        "conv-a", List.of(), List.of(), List.of(), List.of(), 100L, false)),
                "2026-11-08T10:00:00Z");
        when(messageApi.syncDeltas(any(SyncRequest.class)))
                .thenReturn(asCall(Response.success(deltaResponse)));

        repository.refreshInbox().blockingGet();

        assertEquals(java.util.Set.of("conv-a", "conv-arch"), dao().rows.keySet());
        ArgumentCaptor<SyncRequest> captor = ArgumentCaptor.forClass(SyncRequest.class);
        verify(messageApi).syncDeltas(captor.capture());
        List<String> confirmed = new ArrayList<>();
        for (SyncRequest.Watermark watermark : captor.getValue().getWatermarks()) {
            confirmed.add(watermark.getConversationId());
        }
        assertEquals(List.of("conv-removed"), confirmed);
    }

    @Test
    public void pagedArchivedWalkPrunesOnlyConfirmedArchivedRows() throws Exception {
        buildRepository();
        dao().upsertAll(List.of(
                withPreferences(conversation("conv-arch-a", 100), false, null, true, false),
                withPreferences(conversation("conv-arch-removed", 120), false, null, true, false),
                conversation("conv-inbox", 130)))
                .blockingAwait();
        stubArchivedInboxPage(null, "cursor-arch-2", inboxJsonArchived("conv-arch-a", 100));

        SyncResponse deltaResponse = new SyncResponse(List.of(
                new SyncResponse.ConversationDeltaDto(
                        "conv-arch-a", List.of(), List.of(), List.of(), List.of(), 100L, false)),
                "2026-11-08T10:00:00Z");
        when(messageApi.syncDeltas(any(SyncRequest.class)))
                .thenReturn(asCall(Response.success(deltaResponse)));

        repository.refreshInboxPage(true).blockingGet();

        assertEquals(java.util.Set.of("conv-arch-a", "conv-inbox"), dao().rows.keySet());
        ArgumentCaptor<SyncRequest> captor = ArgumentCaptor.forClass(SyncRequest.class);
        verify(messageApi).syncDeltas(captor.capture());
        List<String> confirmed = new ArrayList<>();
        for (SyncRequest.Watermark watermark : captor.getValue().getWatermarks()) {
            confirmed.add(watermark.getConversationId());
        }
        assertEquals(List.of("conv-arch-removed"), confirmed);
    }

    @Test
    public void selfRemovalDeletesTheConversationAndItsLocalCache() {
        buildRepository();
        dao().upsert(conversation("conv-a", 200)).blockingAwait();

        repository.handleConversationUpdate(new ChatEvent.ConversationUpdated(
                "conv-a", "MEMBER_REMOVED", List.of("other-member"))).blockingGet();

        assertFalse(dao().rows.containsKey("conv-a"));
        verify(messageDao).deleteForConversation("conv-a");
        verify(reactionDao).deleteForConversation("conv-a");
        verify(pinDao).deleteForConversation("conv-a");
        verify(outboxDao).deleteForConversation("conv-a", MY_ID);
    }

    @Test
    public void anotherMemberLeavingKeepsTheConversation() {
        buildRepository();
        dao().upsert(conversation("conv-a", 200)).blockingAwait();

        repository.handleConversationUpdate(new ChatEvent.ConversationUpdated(
                "conv-a", "MEMBER_LEFT", List.of(MY_ID, "other-member"))).blockingGet();

        assertTrue(dao().rows.containsKey("conv-a"));
        verify(messageDao, never()).deleteForConversation(any());
    }

    @Test
    public void pinConversationAppliesOptimisticallyThenPersistsOnSuccess() {
        buildRepository();
        dao().upsert(conversation("conv-pin", 100)).blockingAwait();
        when(messageApi.pinConversation("conv-pin")).thenReturn(asCall(Response.success(null)));

        Result<?> result = repository.pinConversation("conv-pin").blockingGet();

        assertTrue(result instanceof Result.Success<?>);
        ConversationEntity row = dao().rows.get("conv-pin");
        assertTrue(row.isPinned());
        assertTrue(row.getPinnedAt() != null && row.getPinnedAt() > 0);
        verify(messageApi, times(1)).pinConversation("conv-pin");
    }

    @Test
    public void pinConversationRevertsOnCapError() {
        buildRepository();
        dao().upsert(conversation("conv-pin", 100)).blockingAwait();
        ResponseBody errorBody = ResponseBody.create(
                okhttp3.MediaType.parse("application/json"),
                "{\"code\":\"CONVERSATION_PIN_LIMIT\",\"message\":\"Pin limit reached\"}");
        when(messageApi.pinConversation("conv-pin")).thenReturn(asCall(Response.error(409, errorBody)));

        Result<?> result = repository.pinConversation("conv-pin").blockingGet();

        assertTrue(result instanceof Result.Failure<?>);
        ConversationEntity row = dao().rows.get("conv-pin");
        assertFalse(row.isPinned());
        assertEquals(null, row.getPinnedAt());
    }

    @Test
    public void unpinConversationAppliesOptimisticallyThenPersistsOnSuccess() {
        buildRepository();
        ConversationEntity pinned = conversation("conv-pin", 100).withPinned(true, 123456L);
        dao().upsert(pinned).blockingAwait();
        when(messageApi.unpinConversation("conv-pin")).thenReturn(asCall(Response.success(null)));

        Result<?> result = repository.unpinConversation("conv-pin").blockingGet();

        assertTrue(result instanceof Result.Success<?>);
        ConversationEntity row = dao().rows.get("conv-pin");
        assertFalse(row.isPinned());
        assertEquals(null, row.getPinnedAt());
        verify(messageApi, times(1)).unpinConversation("conv-pin");
    }

    @Test
    public void unpinConversationRevertsOnNetworkError() throws Exception {
        buildRepository();
        ConversationEntity pinned = conversation("conv-pin", 100).withPinned(true, 123456L);
        dao().upsert(pinned).blockingAwait();
        Call<Void> failingCall = org.mockito.Mockito.mock(Call.class);
        when(failingCall.execute()).thenThrow(new java.io.IOException("Network down"));
        when(messageApi.unpinConversation("conv-pin")).thenReturn(failingCall);

        Result<?> result = repository.unpinConversation("conv-pin").blockingGet();

        assertTrue(result instanceof Result.Failure<?>);
        ConversationEntity row = dao().rows.get("conv-pin");
        assertTrue(row.isPinned());
        assertEquals(Long.valueOf(123456L), row.getPinnedAt());
    }

    @Test
    public void inboxFlowSortsPinnedConversationsFirstWithTies() {
        buildRepository();
        ConversationEntity c1 = conversation("c1", 100).withPinned(true, 2000L);
        ConversationEntity c2 = conversation("c2", 50).withPinned(true, 3000L);
        ConversationEntity c3 = conversation("c3", 200).withPinned(true, 2000L);
        ConversationEntity u1 = conversation("u1", 500);
        ConversationEntity u2 = conversation("u2", 600);

        dao().upsertAll(List.of(u1, c1, u2, c2, c3)).blockingAwait();

        List<ConversationSummary> summaries = repository.inboxFlow().blockingFirst();
        List<String> ids = summaries.stream().map(ConversationSummary::getId).toList();

        assertEquals(List.of("c2", "c3", "c1", "u2", "u1"), ids);
    }

    private FakeConversationDao dao() {
        return (FakeConversationDao) conversationDao;
    }

    private ConversationEntity conversation(String id) {
        return conversation(id, 0);
    }

    private ConversationEntity conversation(String id, long seq) {
        return new ConversationEntity(
                id, "GROUP", id, seq, null, null, seq == 0 ? null : seq, 0,
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
                row.getAvatarUrl(), peerReadSeq, role, row.getOtherUserId(), row.getLastMessageDeleted(),
                row.getPeerDeliveredSeq(), row.isMuted(), row.getMutedUntilEpochMs(),
                row.isArchived(), row.isAiOptOut(), row.isPinned(), row.getPinnedAt(),
                row.getLabelIdsCsv());
    }

    private ConversationEntity withPreferences(
            ConversationEntity row,
            boolean muted,
            Long mutedUntilEpochMs,
            boolean archived,
            boolean aiOptOut) {
        return new ConversationEntity(
                row.getId(), row.getType(), row.getTitle(), row.getLastMessageSeq(),
                row.getLastMessageContent(), row.getLastMessageSenderName(), row.getLastMessageAt(),
                row.getUnreadCount(), row.getOtherUserName(), row.getOtherUserAvatar(),
                row.getAvatarUrl(), row.getPeerReadSeq(), row.getMyRole(), row.getOtherUserId(),
                row.getLastMessageDeleted(), row.getPeerDeliveredSeq(), muted,
                mutedUntilEpochMs, archived, aiOptOut, row.isPinned(), row.getPinnedAt(),
                row.getLabelIdsCsv());
    }

    private ConversationInboxDto inboxDto(String id) {
        return inboxDto(id, "GROUP", "T", List.of(member(MY_ID, "MEMBER")));
    }

    private ConversationInboxDto inboxDto(String id, long seq) {
        String createdAt = Instant.ofEpochSecond(seq).toString();
        InboxLastMessageDto lastMessage = new InboxLastMessageDto(
                seq, "TEXT", null, "message-" + seq, "sender", "Sender", "Sender",
                createdAt, null);
        return new ConversationInboxDto(
                id, "GROUP", id, seq, lastMessage, null, null, null, 0, 0L,
                null, null, null, null, List.of(member(MY_ID, "MEMBER")), null);
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
        public Completable updateMute(
                String conversationId, boolean muted, Long mutedUntilEpochMs) {
            return Completable.fromAction(() -> {
                ConversationEntity row = rows.get(conversationId);
                if (row != null) {
                    rows.put(conversationId, withPreferences(
                            row, muted, mutedUntilEpochMs, row.isArchived(), row.isAiOptOut()));
                }
                publish();
            });
        }

        @Override
        public Completable updateArchived(String conversationId, boolean archived) {
            return Completable.fromAction(() -> {
                ConversationEntity row = rows.get(conversationId);
                if (row != null) {
                    rows.put(conversationId, withPreferences(
                            row, row.isMuted(), row.getMutedUntilEpochMs(), archived, row.isAiOptOut()));
                }
                publish();
            });
        }

        @Override
        public Completable updateAiOptOut(String conversationId, boolean aiOptOut) {
            return Completable.fromAction(() -> {
                ConversationEntity row = rows.get(conversationId);
                if (row != null) {
                    rows.put(conversationId, withPreferences(
                            row, row.isMuted(), row.getMutedUntilEpochMs(), row.isArchived(), aiOptOut));
                }
                publish();
            });
        }

        @Override
        public Completable clearInbox() {
            return Completable.fromAction(() -> {
                rows.values().removeIf(row -> !row.isArchived());
                publish();
            });
        }

        @Override
        public Completable clearArchived() {
            return Completable.fromAction(() -> {
                rows.values().removeIf(ConversationEntity::isArchived);
                publish();
            });
        }

        @Override
        public Completable deleteInboxExcept(List<String> ids) {
            return Completable.fromAction(() -> {
                rows.values().removeIf(row -> !row.isArchived() && !ids.contains(row.getId()));
                publish();
            });
        }

        @Override
        public Completable deleteArchivedExcept(List<String> ids) {
            return Completable.fromAction(() -> {
                rows.values().removeIf(row -> row.isArchived() && !ids.contains(row.getId()));
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
        public Completable deleteById(String conversationId) {
            return Completable.fromAction(() -> {
                rows.remove(conversationId);
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

        @Override
        public Completable bumpPeerDeliveredSeq(String conversationId, long seq) {
            return Completable.fromAction(() -> {
                ConversationEntity row = rows.get(conversationId);
                if (row != null && seq > row.getPeerDeliveredSeq()) {
                    rows.put(conversationId, row.withPeerDeliveredSeq(seq));
                    publish();
                }
            });
        }

        @Override
        public Completable updatePin(String conversationId, boolean pinned, Long pinnedAt) {
            return Completable.fromAction(() -> {
                ConversationEntity row = rows.get(conversationId);
                if (row != null) {
                    rows.put(conversationId, row.withPinned(pinned, pinnedAt));
                    publish();
                }
            });
        }

        @Override
        public Completable updateLabelIds(String conversationId, String labelIdsCsv) {
            return Completable.fromAction(() -> {
                ConversationEntity row = rows.get(conversationId);
                if (row != null) rows.put(conversationId, row.withLabelIdsCsv(labelIdsCsv));
                publish();
            });
        }

        @Override
        public Completable removeLabelIdFromAll(String labelId) {
            return Completable.fromAction(() -> {
                for (Map.Entry<String, ConversationEntity> entry : rows.entrySet()) {
                    ConversationEntity row = entry.getValue();
                    List<String> labels = row.getLabelIds().stream()
                            .filter(id -> !labelId.equals(id)).toList();
                    if (labels.size() != row.getLabelIds().size()) {
                        entry.setValue(row.withLabelIdsCsv(String.join(",", labels)));
                    }
                }
                publish();
            });
        }

        @Override
        public Single<List<ConversationWatermark>> getAllWatermarks() {
            return Single.fromCallable(() -> {
                List<ConversationWatermark> list = new ArrayList<>();
                for (ConversationEntity row : rows.values()) {
                    list.add(new ConversationWatermark(row.getId(), row.getLastMessageSeq()));
                }
                return list;
            });
        }

        private void publish() {
            List<ConversationEntity> ordered = new ArrayList<>(rows.values());
            ordered.sort((a, b) -> {
                int pinCompare = Boolean.compare(b.isPinned(), a.isPinned());
                if (pinCompare != 0) return pinCompare;
                long aPinnedAt = a.getPinnedAt() != null ? a.getPinnedAt() : 0L;
                long bPinnedAt = b.getPinnedAt() != null ? b.getPinnedAt() : 0L;
                int pinnedAtCompare = Long.compare(bPinnedAt, aPinnedAt);
                if (pinnedAtCompare != 0) return pinnedAtCompare;
                long aMsgAt = a.getLastMessageAt() != null ? a.getLastMessageAt() : 0L;
                long bMsgAt = b.getLastMessageAt() != null ? b.getLastMessageAt() : 0L;
                int msgCompare = Long.compare(bMsgAt, aMsgAt);
                if (msgCompare != 0) return msgCompare;
                return a.getId().compareTo(b.getId());
            });
            inbox.onNext(Collections.unmodifiableList(ordered));
        }

        private ConversationEntity withUnread(ConversationEntity row, int unread) {
            return new ConversationEntity(
                    row.getId(), row.getType(), row.getTitle(), row.getLastMessageSeq(),
                    row.getLastMessageContent(), row.getLastMessageSenderName(), row.getLastMessageAt(),
                    unread, row.getOtherUserName(), row.getOtherUserAvatar(), row.getAvatarUrl(),
                    row.getPeerReadSeq(), row.getMyRole(), row.getOtherUserId(),
                    row.getLastMessageDeleted(), row.getPeerDeliveredSeq(), row.isMuted(),
                    row.getMutedUntilEpochMs(), row.isArchived(), row.isAiOptOut(),
                    row.isPinned(), row.getPinnedAt(), row.getLabelIdsCsv());
        }

        private ConversationEntity withLastMessage(
                ConversationEntity row, long seq, String content, String sender, Long at, boolean deleted) {
            return new ConversationEntity(
                    row.getId(), row.getType(), row.getTitle(), seq, content, sender, at,
                    row.getUnreadCount(), row.getOtherUserName(), row.getOtherUserAvatar(), row.getAvatarUrl(),
                    row.getPeerReadSeq(), row.getMyRole(), row.getOtherUserId(), deleted,
                    row.getPeerDeliveredSeq(), row.isMuted(), row.getMutedUntilEpochMs(),
                    row.isArchived(), row.isAiOptOut(), row.isPinned(), row.getPinnedAt(),
                    row.getLabelIdsCsv());
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
