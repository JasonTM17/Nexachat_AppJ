package com.nexachat.data;

import android.content.Context;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.common.Result;
import com.nexachat.core.database.AppDatabase;
import com.nexachat.core.database.ConversationDao;
import com.nexachat.core.database.ConversationEntity;
import com.nexachat.core.network.ApiResult;
import com.nexachat.core.network.ConversationSettingsApi;
import com.nexachat.core.network.GroupApi;
import com.nexachat.core.network.MessageApi;
import com.nexachat.core.network.dto.AddGroupMembersRequest;
import com.nexachat.core.network.dto.AvatarResponse;
import com.nexachat.core.network.dto.ConversationAiOptOutDto;
import com.nexachat.core.network.dto.ConversationDto;
import com.nexachat.core.network.dto.ConversationInboxDto;
import com.nexachat.core.network.dto.ConversationMemberDto;
import com.nexachat.core.network.dto.ConversationsPageDto;
import com.nexachat.core.network.dto.CreateGroupRequest;
import com.nexachat.core.network.dto.MuteRequest;
import com.nexachat.core.network.dto.PeerPresenceDto;
import com.nexachat.core.network.dto.SyncRequest;
import com.nexachat.core.network.dto.SyncResponse;
import com.nexachat.core.network.dto.UpdateConversationRequest;
import com.nexachat.core.network.stomp.ChatEvent;
import com.nexachat.core.network.stomp.RealtimeSession;
import com.nexachat.core.storage.TokenStore;
import com.nexachat.core.storage.Tokens;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import dagger.hilt.android.qualifiers.ApplicationContext;
import io.reactivex.rxjava3.core.BackpressureOverflowStrategy;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.processors.FlowableProcessor;
import io.reactivex.rxjava3.processors.PublishProcessor;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;
import javax.inject.Inject;
import javax.inject.Singleton;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import okio.Timeout;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Conversation/inbox boundary for {@code GET /conversations} and group
 * management (plan §10.2 and §12.2). Room is the render source; inbox reads
 * remain local, while REST refreshes write through. Realtime pushes only
 * trigger refreshes: a debounce folds {@code CONVERSATION_UPDATED} and
 * {@code MESSAGE_CREATED} bursts into one page-one REST request. Inbox pages
 * contain 30 rows and merge by conversation id. Pruning is allowed only after
 * a walk started at page one reaches a response without a next cursor; a
 * single-page response is authoritative, while a paged walk only deletes rows
 * that are provably older than the walk or that the delta endpoint confirms
 * are no longer accessible. Equal/newer activity, rows without a sequence, and
 * locally-created conversations are retained because Room does not store the
 * server cursor's creation-time tie-breaker. Reconnect resync requests perform
 * the same page-one pull.
 *
 * <p>Mute, archive and AI opt-out are caller-scoped server preferences. Writes
 * go through the REST endpoints first and are written through to Room only on
 * success; inbox payloads and delta bundles carry the authoritative mute,
 * archive and AI opt-out flags. The archived inbox is a separate keyset walk
 * ({@code archived=true}); pruning is scoped to the walk's variant so one
 * filter can never delete the other variant's rows.
 *
 * <p>Retrofit calls and avatar reads run on {@link Schedulers#io()}. Room
 * operations use Room's RxJava executor and are chained without blocking.
 */
@Singleton
public final class ConversationRepository implements AutoCloseable {

    private static final String TYPE_DIRECT = "DIRECT";
    private static final String TYPE_GROUP = "GROUP";
    private static final long PUSH_REFRESH_DEBOUNCE_MS = 400L;
    private static final String NEXT_CURSOR_HEADER = "X-Next-Cursor";
    private static final int INBOX_PAGE_SIZE = 30;
    private static final int MEMBERS_PAGE_LIMIT = 100;
    private static final int MAX_AVATAR_BYTES = 25 * 1024 * 1024;
    private static final int AVATAR_READ_BUFFER_BYTES = 64 * 1024;
    private static final String ERROR_UNEXPECTED_EMPTY_BODY = "UNEXPECTED_EMPTY_BODY";

    private final MessageApi messageApi;
    private final GroupApi groupApi;
    private final ConversationSettingsApi settingsApi;
    private final ConversationDao conversationDao;
    private final AppDatabase appDatabase;
    private final SessionOperationTracker operationTracker;
    private final InboxReadWatermarkCache inboxReadWatermarkCache;
    @Nullable private final MessageRepository messageRepository;
    private final PresenceRepository presenceRepository;
    private final RealtimeSession session;
    private final TokenStore tokenStore;
    private final Moshi moshi;
    private final Context context;
    private final JsonAdapter<ConversationsPageDto> pageAdapter;
    private final JsonAdapter<List<ConversationInboxDto>> listAdapter;
    /** Small fan-in that coalesces bursts of push events into one inbox pull. */
    private final FlowableProcessor<Boolean> refreshRequests = PublishProcessor.<Boolean>create().toSerialized();
    /** Group ids updated by the server, consumed only by currently-open detail screens. */
    private final FlowableProcessor<String> groupUpdateIds = PublishProcessor.<String>create().toSerialized();
    /**
     * Groups just created by this device whose row may not yet be present in
     * a GET response already in flight. Entries are removed once a fetch lists
     * them, preventing refresh pruning from deleting a freshly-created row.
     */
    private final Set<String> locallyCreatedIds = ConcurrentHashMap.newKeySet();
    private final CompositeDisposable subscriptions = new CompositeDisposable();
    private final Object inboxPagingLock = new Object();
    /** Current walk's visible, privacy-authorized direct peers for Firebase presence. */
    private final Map<String, String> inboxPresenceObservers = new LinkedHashMap<>();
    private long inboxPageGeneration;
    @Nullable private String nextInboxCursor;
    private boolean hasMoreInboxPages;
    private boolean inboxWalkStartedAtFirstPage;
    /** Which inbox variant the current cursor walk belongs to. */
    private boolean inboxArchivedMode;
    private long oldestInboxSequence = Long.MAX_VALUE;
    private final Set<String> seenInboxCursors = new HashSet<>();
    private final Set<String> inboxWalkIds = new LinkedHashSet<>();
    @Nullable private Single<Result<Boolean>> inFlightNextInboxPage;
    private final ConversationPreferenceLanes preferenceLanes = new ConversationPreferenceLanes();
    private final ConversationPreferenceNetworkQueue preferenceNetwork;

    @Inject
    public ConversationRepository(
            @NonNull MessageApi messageApi,
            @NonNull GroupApi groupApi,
            @NonNull ConversationSettingsApi settingsApi,
            @NonNull ConversationDao conversationDao,
            @NonNull PresenceRepository presenceRepository,
            @NonNull RealtimeSession session,
            @NonNull TokenStore tokenStore,
            @NonNull Moshi moshi,
            @ApplicationContext @NonNull Context context,
            @NonNull AppDatabase appDatabase,
            @NonNull SessionOperationTracker operationTracker,
            @NonNull InboxReadWatermarkCache inboxReadWatermarkCache,
            @NonNull MessageRepository messageRepository) {
        this.messageApi = messageApi;
        this.groupApi = groupApi;
        this.settingsApi = settingsApi;
        this.conversationDao = conversationDao;
        this.appDatabase = appDatabase;
        this.operationTracker = operationTracker;
        this.inboxReadWatermarkCache = inboxReadWatermarkCache;
        this.messageRepository = messageRepository;
        this.presenceRepository = presenceRepository;
        this.session = session;
        this.tokenStore = tokenStore;
        this.moshi = moshi;
        this.context = context;
        this.pageAdapter = moshi.adapter(ConversationsPageDto.class);
        this.listAdapter = moshi.adapter(Types.newParameterizedType(
                List.class, ConversationInboxDto.class));
        this.preferenceNetwork = new ConversationPreferenceNetworkQueue(
                this::callVoid,
                new ConversationPreferenceNetworkQueue.OutcomeHandler() {
                    @Override
                    @NonNull
                    public Single<Result<Void>> onArchiveNetworkComplete(
                            @NonNull String conversationId,
                            long version,
                            boolean attemptedArchived,
                            @NonNull Result<Void> result) {
                        return resolveArchiveNetwork(conversationId, version, result);
                    }

                    @Override
                    @NonNull
                    public Single<Result<Void>> onMuteNetworkComplete(
                            @NonNull String conversationId,
                            long version,
                            @NonNull ConversationPreferenceNetworkQueue.MuteState attempted,
                            @NonNull Result<Void> result) {
                        return resolveMuteNetwork(conversationId, version, result);
                    }

                    @Override
                    @NonNull
                    public Single<Result<Void>> onPinNetworkComplete(
                            @NonNull String conversationId,
                            long version,
                            @NonNull ConversationPreferenceNetworkQueue.PinState attempted,
                            @NonNull Result<Void> result) {
                        return resolvePinNetwork(conversationId, version, result);
                    }
                });
        watchPushTriggers();
    }

    /** Retained for local repository tests without a shared account operation tracker. */
    public ConversationRepository(
            @NonNull MessageApi messageApi,
            @NonNull GroupApi groupApi,
            @NonNull ConversationSettingsApi settingsApi,
            @NonNull ConversationDao conversationDao,
            @NonNull PresenceRepository presenceRepository,
            @NonNull RealtimeSession session,
            @NonNull TokenStore tokenStore,
            @NonNull Moshi moshi,
            @ApplicationContext @NonNull Context context,
            @NonNull AppDatabase appDatabase) {
        this(messageApi, groupApi, settingsApi, conversationDao, presenceRepository,
                session, tokenStore, moshi, context, appDatabase, new SessionOperationTracker(),
                new InboxReadWatermarkCache(), null);
    }

    /**
     * Room inbox rows joined with the latest peer presence snapshot. This is
     * a pure local stream; REST only writes new snapshots into Room.
     */
    @NonNull
    public Flowable<List<ConversationSummary>> inboxFlow() {
        return Flowable.combineLatest(
                        conversationDao.inboxFlow(),
                        presenceRepository.getPresences(),
                        (rows, presence) -> {
                            List<ConversationSummary> summaries = new ArrayList<>(rows.size());
                            for (ConversationEntity row : rows) summaries.add(toSummary(row, presence));
                            return Collections.unmodifiableList(summaries);
                        })
                .subscribeOn(Schedulers.io());
    }

    /**
     * Single conversation for the chat title bar. An empty {@link Optional}
     * means the row is absent; RxJava streams cannot emit {@code null} values.
     */
    @NonNull
    public Flowable<Optional<ConversationSummary>> conversationFlow(@NonNull String conversationId) {
        return Flowable.combineLatest(
                        conversationDao.observeById(conversationId),
                        presenceRepository.getPresences(),
                        (rows, presence) -> rows.isEmpty()
                                ? Optional.<ConversationSummary>empty()
                                : Optional.of(toSummary(rows.get(0), presence)))
                .distinctUntilChanged()
                .subscribeOn(Schedulers.io());
    }

    /** Emits {@code true} for each update to the requested group. */
    @NonNull
    public Flowable<Boolean> groupUpdates(@NonNull String conversationId) {
        return groupUpdateIds
                .onBackpressureBuffer(8, () -> { }, BackpressureOverflowStrategy.DROP_OLDEST)
                .filter(conversationId::equals)
                .map(ignored -> Boolean.TRUE);
    }

    /**
     * Pulls the first inbox page of the current filter mode, merges it into
     * Room, and resets the paging cursor. Realtime and reconnect refreshes use
     * the same bounded request.
     */
    @NonNull
    public Single<Result<Void>> refreshInbox() {
        final boolean archived;
        synchronized (inboxPagingLock) {
            archived = inboxArchivedMode;
        }
        return refreshInboxPage(archived).map(result -> result instanceof Result.Failure<?>
                ? Result.<Void>cast(result)
                : successVoid());
    }

    /** Refreshes the default (non-archived) inbox page one. */
    @NonNull
    public Single<Result<Boolean>> refreshInboxPage() {
        return refreshInboxPage(false);
    }

    /**
     * Refreshes page one of one inbox variant and returns whether more rows can
     * be loaded. The server hides archived conversations unless asked.
     */
    @NonNull
    public Single<Result<Boolean>> refreshInboxPage(boolean archived) {
        final long generation;
        synchronized (inboxPagingLock) {
            generation = ++inboxPageGeneration;
            inboxArchivedMode = archived;
            nextInboxCursor = null;
            hasMoreInboxPages = false;
            inboxWalkStartedAtFirstPage = true;
            oldestInboxSequence = Long.MAX_VALUE;
            seenInboxCursors.clear();
            inboxWalkIds.clear();
            inFlightNextInboxPage = null;
        }
        return operationTracker.track(currentUserIdOptional()
                .flatMap(myId -> fetchAndPersistInboxPage(
                        generation, INBOX_PAGE_SIZE, null, myId.orElse(null), true, archived))
                .subscribeOn(Schedulers.io()));
    }

    /**
     * Fetches the server-filtered label set without pruning unrelated cached
     * conversations, then writes the returned rows through to Room.
     */
    @NonNull
    public Single<Result<Set<String>>> refreshInboxByLabel(@NonNull String labelId) {
        return operationTracker.track(currentUserIdOptional()
                .flatMap(myId -> fetchInboxItems(null, null, false, labelId).flatMap(result -> {
                    if (result instanceof Result.Failure<?>) {
                        return Single.<Result<Set<String>>>just(Result.cast(result));
                    }
                    InboxFetch fetch = successValue(result);
                    List<ConversationEntity> rows = mapInboxRows(fetch.items, myId.orElse(null));
                    Set<String> ids = new LinkedHashSet<>();
                    for (ConversationEntity row : rows) ids.add(row.getId());
                    return upsertPreservingClientColumns(rows)
                            .andThen(Single.fromCallable(() ->
                                    new Result.Success<Set<String>>(Set.copyOf(ids))));
                }))
                .subscribeOn(Schedulers.io()));
    }

    /**
     * Loads the next keyset page of the current filter mode. Concurrent callers
     * share one network request; a failed page leaves its cursor intact so a
     * later call retries it.
     */
    @NonNull
    public Single<Result<Boolean>> loadNextInboxPage() {
        synchronized (inboxPagingLock) {
            if (inFlightNextInboxPage != null) return inFlightNextInboxPage;
            if (!hasMoreInboxPages || nextInboxCursor == null) {
                return Single.just(new Result.Success<>(Boolean.FALSE));
            }
            final long generation = inboxPageGeneration;
            final String cursor = nextInboxCursor;
            final boolean archived = inboxArchivedMode;
            AtomicReference<Single<Result<Boolean>>> sharedRef = new AtomicReference<>();
            Single<Result<Boolean>> shared = operationTracker.track(currentUserIdOptional()
                    .flatMap(myId -> fetchAndPersistInboxPage(
                            generation, INBOX_PAGE_SIZE, cursor, myId.orElse(null), false, archived))
                    // Clear before the terminal event reaches waiters so a failed page can retry
                    // without receiving the cached in-flight Single.
                    .doOnSuccess(ignored -> clearInFlightNextInboxPageIfCurrent(sharedRef.get()))
                    .doOnError(ignored -> clearInFlightNextInboxPageIfCurrent(sharedRef.get()))
                    .doFinally(() -> clearInFlightNextInboxPageIfCurrent(sharedRef.get()))
                    .cache());
            sharedRef.set(shared);
            inFlightNextInboxPage = shared;
            return shared;
        }
    }

    private void clearInFlightNextInboxPageIfCurrent(@Nullable Single<Result<Boolean>> shared) {
        synchronized (inboxPagingLock) {
            if (inFlightNextInboxPage == shared) inFlightNextInboxPage = null;
        }
    }

    /** Our own JWT subject, used to resolve the caller's member row. */
    @NonNull
    public Maybe<String> myUserId() {
        return currentUserId();
    }

    /**
     * Mutes the conversation for the caller until {@code until}. Only the
     * caller's member row changes; the other members are never notified.
     */
    @NonNull
    public Single<Result<Void>> muteConversation(
            @NonNull String conversationId, @NonNull java.time.Instant until) {
        return operationTracker.track(orderedMute(
                conversationId,
                true,
                until.toEpochMilli(),
                state -> muteCallFor(conversationId, state))
                .subscribeOn(Schedulers.io()));
    }

    /** Mutes the conversation until the caller turns notifications back on. */
    @NonNull
    public Single<Result<Void>> muteConversationForever(@NonNull String conversationId) {
        return operationTracker.track(orderedMute(
                conversationId,
                true,
                null,
                state -> muteCallFor(conversationId, state))
                .subscribeOn(Schedulers.io()));
    }

    /** Clears the caller's mute; idempotent when already unmuted. */
    @NonNull
    public Single<Result<Void>> unmuteConversation(@NonNull String conversationId) {
        return operationTracker.track(orderedMute(
                conversationId,
                false,
                null,
                state -> muteCallFor(conversationId, state))
                .subscribeOn(Schedulers.io()));
    }

    /** Hides or restores the conversation in the caller's default inbox. */
    @NonNull
    public Single<Result<Void>> setConversationArchived(
            @NonNull String conversationId, boolean archived) {
        return operationTracker.track(orderedArchive(
                conversationId,
                archived,
                isArchived -> isArchived
                        ? settingsApi.archive(conversationId)
                        : settingsApi.unarchive(conversationId))
                .subscribeOn(Schedulers.io()));
    }

    /** Reads the caller's own AI opt-out state for one conversation. */
    @NonNull
    public Single<Result<Boolean>> getAiOptOut(@NonNull String conversationId) {
        return operationTracker.track(call(settingsApi.getAiOptOut(conversationId))
                .flatMap(result -> {
                    if (result instanceof Result.Failure<?>) {
                        return Single.<Result<Boolean>>just(Result.cast(result));
                    }
                    Boolean optOut = successValue(result).getAiOptOut();
                    boolean value = Boolean.TRUE.equals(optOut);
                    return conversationDao.updateAiOptOut(conversationId, value)
                            .andThen(Single.fromCallable(() -> new Result.Success<>(value)));
                })
                .subscribeOn(Schedulers.io()));
    }

    /**
     * Sets the caller's own AI opt-out. The server is the source of truth; the
     * returned state is written through to Room on success.
     */
    @NonNull
    public Single<Result<Boolean>> setAiOptOut(
            @NonNull String conversationId, boolean optOut) {
        Call<ConversationAiOptOutDto> call = optOut
                ? settingsApi.optOutOfAi(conversationId)
                : settingsApi.clearAiOptOut(conversationId);
        return operationTracker.track(call(call)
                .flatMap(result -> {
                    if (result instanceof Result.Failure<?>) {
                        return Single.<Result<Boolean>>just(Result.cast(result));
                    }
                    Boolean resolved = successValue(result).getAiOptOut();
                    boolean value = resolved == null ? optOut : resolved;
                    return conversationDao.updateAiOptOut(conversationId, value)
                            .andThen(Single.fromCallable(() -> new Result.Success<>(value)));
                })
                .subscribeOn(Schedulers.io()));
    }

    /**
     * Creates a GROUP and writes through the row so the new chat screen has a
     * title before the server broadcast lands. The create response omits the
     * title, so the requested value is kept as a fallback.
     */
    @NonNull
    public Single<Result<String>> createGroup(@NonNull String title, @NonNull List<String> memberIds) {
        String trimmed = title.trim();
        return operationTracker.track(call(groupApi.createGroup(new CreateGroupRequest(trimmed, memberIds)))
                .flatMap(result -> {
                    if (result instanceof Result.Failure<?>) {
                        return Single.<Result<String>>just(Result.cast(result));
                    }
                    ConversationDto created = successValue(result);
                    locallyCreatedIds.add(created.getId());
                    ConversationEntity row = toEntity(created, trimmed);
                    return upsertPreservingClientColumns(Collections.singletonList(row))
                            .andThen(Single.fromCallable(() -> new Result.Success<>(created.getId())));
                })
                .subscribeOn(Schedulers.io()));
    }

    /**
     * Member list and title for the group detail screen. No individual detail
     * endpoint is assumed, so this walks the inbox's opaque cursor pages until
     * it finds the row and writes it through to Room.
     */
    @NonNull
    public Single<Result<GroupDetail>> groupDetail(@NonNull String conversationId) {
        return operationTracker.track(currentUserIdOptional()
                .flatMap(myId -> groupDetailPage(
                        conversationId, myId.orElse(null), null, new HashSet<>()))
                .subscribeOn(Schedulers.io()));
    }

    @NonNull
    public Single<Result<Void>> addGroupMembers(
            @NonNull String conversationId,
            @NonNull List<String> userIds) {
        return operationTracker.track(callVoid(groupApi.addMembers(
                conversationId, new AddGroupMembersRequest(userIds)))
                .subscribeOn(Schedulers.io()));
    }

    @NonNull
    public Single<Result<Void>> removeGroupMember(
            @NonNull String conversationId,
            @NonNull String userId) {
        return operationTracker.track(callVoid(groupApi.removeMember(conversationId, userId))
                .subscribeOn(Schedulers.io()));
    }

    /**
     * Renames the group (ADMIN-only server-side). On success the local row is
     * patched so {@link #conversationFlow(String)} re-emits without waiting
     * for {@code CONVERSATION_UPDATED}.
     */
    @NonNull
    public Single<Result<Void>> renameGroup(@NonNull String conversationId, @NonNull String title) {
        String trimmed = title.trim();
        return operationTracker.track(callVoid(groupApi.updateConversation(
                        conversationId, new UpdateConversationRequest(trimmed)))
                .flatMap(result -> {
                    if (result instanceof Result.Failure<?>) {
                        return Single.<Result<Void>>just(Result.cast(result));
                    }
                    return conversationDao.byId(conversationId)
                            .flatMapCompletable(row -> conversationDao.upsert(withTitle(row, trimmed)))
                            .andThen(Single.fromCallable(ConversationRepository::successVoid));
                })
                .subscribeOn(Schedulers.io()));
    }

    /**
     * Group avatar upload (ADMIN-only server-side). On success the local row
     * is patched for an immediate inbox update; other members get the change
     * through {@code CONVERSATION_UPDATED} and their next inbox refresh.
     */
    @NonNull
    public Single<Result<Void>> setGroupAvatar(@NonNull String conversationId, @NonNull Uri uri) {
        return operationTracker.track(Maybe.fromCallable(() -> readBoundedAvatar(uri))
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .subscribeOn(Schedulers.io())
                .flatMap(bytesOpt -> {
                    if (bytesOpt.isEmpty()) {
                        return Single.just(Result.<Void>failure(
                                new ApiError.Network("avatar read failed")));
                    }
                    byte[] bytes = bytesOpt.get();
                    String mime = resolveMime(uri);
                    String fileName = uri.getLastPathSegment() != null
                            ? uri.getLastPathSegment()
                            : "group-avatar";
                    MultipartBody.Part part = MultipartBody.Part.createFormData(
                            "file", fileName,
                            RequestBody.create(MediaType.parse(mime), bytes));
                    return call(groupApi.uploadAvatar(conversationId, part))
                            .flatMap(result -> {
                                if (result instanceof Result.Failure<?>) {
                                    return Single.<Result<Void>>just(Result.cast(result));
                                }
                                AvatarResponse avatar = successValue(result);
                                return conversationDao.byId(conversationId)
                                        .flatMapCompletable(row -> conversationDao.upsert(
                                                withAvatarUrl(row, avatar.getAvatarUrl())))
                                        .andThen(Single.fromCallable(
                                                ConversationRepository::successVoid));
                            });
                }));
    }

    /**
     * Self-leave. The DAO has no delete-by-id operation, so clear and refill
     * the conversation table from the authoritative inbox after server success.
     */
    @NonNull
    public Single<Result<Void>> leaveGroup(@NonNull String conversationId) {
        return operationTracker.track(callVoid(groupApi.leaveGroup(conversationId))
                .flatMap(result -> {
                    if (result instanceof Result.Failure<?>) {
                        return Single.<Result<Void>>just(Result.cast(result));
                    }
                    return conversationDao.clear().andThen(refreshInbox());
                })
                .subscribeOn(Schedulers.io()));
    }

    /**
     * Pins the conversation for the caller with optimistic Room update and rollback on failure.
     */
    @NonNull
    public Single<Result<Void>> pinConversation(@NonNull String conversationId) {
        return operationTracker.track(orderedPin(
                conversationId,
                true,
                state -> pinCallFor(conversationId, state))
                .subscribeOn(Schedulers.io()));
    }

    /**
     * Unpins the conversation for the caller with optimistic Room update and rollback on failure.
     */
    @NonNull
    public Single<Result<Void>> unpinConversation(@NonNull String conversationId) {
        return operationTracker.track(orderedPin(
                conversationId,
                false,
                state -> pinCallFor(conversationId, state))
                .subscribeOn(Schedulers.io()));
    }

    /**
     * Toggles the pin state for the specified conversation.
     */
    @NonNull
    public Single<Result<Void>> setConversationPinned(@NonNull String conversationId, boolean pin) {
        return pin ? pinConversation(conversationId) : unpinConversation(conversationId);
    }

    @Override
    public void close() {
        subscriptions.dispose();
    }

    /**
     * Realtime events are refresh triggers only; they are never rendered
     * directly. Reconnect requests also pull REST because it is the source
     * of truth after a disconnected interval.
     */
    private void watchPushTriggers() {
        subscriptions.add(session.getEvents()
                .subscribe(this::handlePushEvent, RxJavaPlugins::onError));
        subscriptions.add(refreshRequests
                .onBackpressureBuffer(4, () -> { }, BackpressureOverflowStrategy.DROP_OLDEST)
                .debounce(PUSH_REFRESH_DEBOUNCE_MS, TimeUnit.MILLISECONDS)
                .concatMapSingle(ignored -> refreshInbox())
                .subscribe(ignored -> { }, RxJavaPlugins::onError));
        subscriptions.add(session.getResyncRequests()
                .concatMapSingle(ignored -> refreshInbox())
                .subscribe(ignored -> { }, RxJavaPlugins::onError));
    }

    private void handlePushEvent(ChatEvent event) {
        if (event instanceof ChatEvent.ConversationUpdated updated) {
            if (updated.getConversationId() != null) {
                groupUpdateIds.onNext(updated.getConversationId());
            }
            if (updated.isRemovalEvent()) {
                handleConversationUpdate(updated).subscribe(
                        removed -> {
                            if (!removed) refreshRequests.onNext(Boolean.TRUE);
                        }, RxJavaPlugins::onError);
                return;
            }
            refreshRequests.onNext(Boolean.TRUE);
        } else if (event instanceof ChatEvent.MessageCreated) {
            refreshRequests.onNext(Boolean.TRUE);
        }
    }

    /**
     * Applies a membership snapshot. A removal frame is authoritative for the
     * caller: when the snapshot no longer contains this user, the inbox row and
     * its cached history are deleted instead of triggering a refresh that the
     * conservative prune could keep. Removal of another member only refreshes.
     */
    Single<Boolean> handleConversationUpdate(ChatEvent.ConversationUpdated update) {
        String conversationId = update.getConversationId();
        if (conversationId == null) return Single.just(Boolean.FALSE);
        return operationTracker.track(currentUserIdOptional().flatMap(myId -> {
            String ownerUserId = myId.orElse(null);
            if (ownerUserId == null || !update.isSelfRemoval(ownerUserId)) {
                return Single.just(Boolean.FALSE);
            }
            return Completable.fromAction(() -> {
                synchronized (inboxPagingLock) {
                    locallyCreatedIds.remove(conversationId);
                    inboxWalkIds.remove(conversationId);
                }
                appDatabase.runInTransaction(() ->
                        LocalConversationCleaner.deleteInTransaction(
                                appDatabase, conversationId, ownerUserId));
            }).subscribeOn(Schedulers.io()).andThen(Single.just(Boolean.TRUE));
        }));
    }

    /** Decodes the JWT subject without copying token material into logs or fields. */
    private Maybe<String> currentUserId() {
        return tokenStore.readTokens()
                .flatMap(tokens -> Maybe.fromCallable(
                        () -> TimeAndJwt.userIdFromJwt(tokens.getAccessToken(), moshi)));
    }

    private Single<Optional<String>> currentUserIdOptional() {
        return currentUserId()
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty());
    }

    private Single<Result<GroupDetail>> groupDetailPage(
            String conversationId,
            @Nullable String myId,
            @Nullable String cursor,
            Set<String> seenCursors) {
        return fetchInboxItems(MEMBERS_PAGE_LIMIT, cursor, false).flatMap(result -> {
            if (result instanceof Result.Failure<?>) {
                return Single.<Result<GroupDetail>>just(Result.cast(result));
            }
            InboxFetch fetch = successValue(result);
            ConversationInboxDto inbox = null;
            for (ConversationInboxDto item : fetch.items) {
                if (conversationId.equals(item.getId())) {
                    inbox = item;
                    break;
                }
            }
            if (inbox != null) {
                ConversationEntity row = toEntity(inbox, myId);
                GroupDetail detail = new GroupDetail(inbox.getTitle(), mapMembers(inbox.getMembers()));
                return upsertPreservingClientColumns(Collections.singletonList(row))
                        .andThen(Single.fromCallable(() -> new Result.Success<>(detail)));
            }
            if (fetch.nextCursor == null) {
                return Single.just(Result.<GroupDetail>failure(
                        new ApiError.Unknown("CONVERSATION_NOT_FOUND")));
            }
            if (!seenCursors.add(fetch.nextCursor)) {
                return Single.just(Result.<GroupDetail>failure(
                        new ApiError.Unknown("INVALID_INBOX_CURSOR")));
            }
            return groupDetailPage(conversationId, myId, fetch.nextCursor, seenCursors);
        });
    }

    private Single<Result<InboxFetch>> fetchInboxItems(
            @Nullable Integer limit,
            @Nullable String cursor,
            boolean archived) {
        return fetchInboxItems(limit, cursor, archived, null);
    }

    private Single<Result<InboxFetch>> fetchInboxItems(
            @Nullable Integer limit,
            @Nullable String cursor,
            boolean archived,
            @Nullable String labelId) {
        AtomicReference<ResponseBody> responseBody = new AtomicReference<>();
        AtomicReference<String> headerCursor = new AtomicReference<>();
        Call<ResponseBody> request = archived
                ? messageApi.getArchivedConversations(limit, cursor)
                : labelId == null
                        ? messageApi.getConversations(limit, cursor)
                        : messageApi.getConversations(limit, cursor, labelId);
        Call<ResponseBody> capturingCall = new ResponseCapturingCall<>(
                request, response -> {
                    responseBody.set(response.body());
                    headerCursor.set(response.headers().get(NEXT_CURSOR_HEADER));
                });
        return safeApiCall(capturingCall).map(result -> {
            if (result instanceof ApiResult.Success<?>) {
                ResponseBody body = responseBody.get();
                if (body == null) {
                    return Result.<InboxFetch>failure(
                            new ApiError.Unknown(ERROR_UNEXPECTED_EMPTY_BODY));
                }
                final String json;
                try {
                    json = body.string().trim();
                } catch (IOException error) {
                    return Result.<InboxFetch>failure(new ApiError.Network(error.getMessage()));
                }
                InboxFetch parsed = parseInboxBody(json, headerCursor.get());
                if (parsed == null) {
                    return Result.<InboxFetch>failure(new ApiError.Unknown("UNEXPECTED_SHAPE"));
                }
                return new Result.Success<>(parsed);
            }
            if (result instanceof ApiResult.HttpError<?> httpError) {
                return Result.<InboxFetch>failure(
                        ApiError.from(httpError.getStatus(), httpError.getErrorCode()));
            }
            ApiResult.NetworkError<?> networkError = (ApiResult.NetworkError<?>) result;
            return Result.<InboxFetch>failure(
                    new ApiError.Network(networkError.getCause().getMessage()));
        });
    }

    private Single<Result<Boolean>> fetchAndPersistInboxPage(
            long generation,
            Integer limit,
            @Nullable String cursor,
            @Nullable String myId,
            boolean firstPage,
            boolean archived) {
        return fetchInboxItems(limit, cursor, archived).flatMap(result -> {
            if (result instanceof Result.Failure<?>) {
                return Single.<Result<Boolean>>just(Result.cast(result));
            }
            InboxFetch fetch = successValue(result);
            synchronized (inboxPagingLock) {
                if (generation != inboxPageGeneration) {
                    return Single.just(new Result.Success<>(Boolean.FALSE));
                }
                if (!firstPage && (!hasMoreInboxPages
                        || !Objects.equals(nextInboxCursor, cursor))) {
                    return Single.just(Result.<Boolean>failure(
                            new ApiError.Unknown("INBOX_CURSOR_CHANGED")));
                }
                if (fetch.nextCursor != null
                        && (fetch.nextCursor.equals(cursor)
                                || seenInboxCursors.contains(fetch.nextCursor))) {
                    return Single.just(Result.<Boolean>failure(
                            new ApiError.Unknown("INVALID_INBOX_CURSOR")));
                }
            }

            List<ConversationEntity> rows = mapInboxRows(fetch.items, myId);
            List<String> pageIds = new ArrayList<>(rows.size());
            for (ConversationEntity row : rows) pageIds.add(row.getId());
            Map<String, PeerPresence> presenceSeeds = presenceSeeds(fetch.items, myId);
            AtomicBoolean fullWalkComplete = new AtomicBoolean();
            return upsertPreservingClientColumns(rows)
                    .andThen(Completable.fromAction(() -> {
                        synchronized (inboxPagingLock) {
                            if (generation != inboxPageGeneration
                                    || (!firstPage && !Objects.equals(nextInboxCursor, cursor))) {
                                return;
                            }
                            for (ConversationEntity row : rows) {
                                inboxWalkIds.add(row.getId());
                                oldestInboxSequence = Math.min(
                                        oldestInboxSequence, row.getLastMessageSeq());
                            }
                            locallyCreatedIds.removeAll(pageIds);
                            if (fetch.nextCursor != null) {
                                seenInboxCursors.add(fetch.nextCursor);
                            }
                            nextInboxCursor = fetch.nextCursor;
                            hasMoreInboxPages = fetch.nextCursor != null;
                            if (fetch.complete && inboxWalkStartedAtFirstPage) {
                                fullWalkComplete.set(true);
                                inboxWalkStartedAtFirstPage = false;
                            }
                        }
                    }))
                    .andThen(Completable.fromAction(() -> {
                        if (isInboxPageGenerationCurrent(generation)) {
                            presenceRepository.seed(presenceSeeds);
                            clearHiddenDirectPeers(fetch.items, presenceSeeds, myId);
                            if (!archived) {
                                updatePresenceObservers(fetch.items, myId, firstPage, generation);
                            }
                        }
                    }))
                    .andThen(Completable.defer(() ->
                            reconcileInboxRows(generation, firstPage, fullWalkComplete.get(),
                                    pageIds, oldestInboxSequence, archived, myId)))
                    .andThen(Single.fromCallable(() -> {
                        synchronized (inboxPagingLock) {
                            return new Result.Success<>(generation == inboxPageGeneration
                                    && hasMoreInboxPages);
                        }
                    }));
        });
    }

    private boolean isInboxPageGenerationCurrent(long generation) {
        synchronized (inboxPagingLock) {
            return generation == inboxPageGeneration;
        }
    }

    private InboxFetch parseInboxBody(String body, @Nullable String headerCursor) {
        if (body.isEmpty()) {
            return new InboxFetch(Collections.emptyList(), headerCursor, headerCursor == null);
        }
        try {
            if (body.startsWith("[")) {
                List<ConversationInboxDto> items = listAdapter.fromJson(body);
                return items == null
                        ? null
                        : new InboxFetch(items, headerCursor, headerCursor == null);
            }
            ConversationsPageDto page = pageAdapter.fromJson(body);
            if (page == null) return null;
            String nextCursor = headerCursor != null ? headerCursor : page.getNextCursor();
            boolean hasMore = page.getHasMore() || nextCursor != null;
            if (hasMore && nextCursor == null) return null;
            return new InboxFetch(
                    page.getItems(), nextCursor, !hasMore);
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private List<ConversationEntity> mapInboxRows(
            List<ConversationInboxDto> items,
            @Nullable String myId) {
        List<ConversationEntity> rows = new ArrayList<>(items.size());
        for (ConversationInboxDto item : items) rows.add(toEntity(item, myId));
        return rows;
    }

    private Map<String, PeerPresence> presenceSeeds(
            List<ConversationInboxDto> items,
            @Nullable String myId) {
        Map<String, PeerPresence> seeds = new LinkedHashMap<>();
        for (ConversationInboxDto dto : items) {
            PeerPresenceDto presence = dto.getPeerPresence();
            String peerId = dto.resolvedOtherId(myId);
            if (presence == null || peerId == null) continue;
            seeds.put(peerId, new PeerPresence(
                    Boolean.TRUE.equals(presence.getOnline()),
                    TimeAndJwt.isoToEpochMsOrNull(presence.getLastSeenAt())));
        }
        return seeds;
    }

    private void updatePresenceObservers(
            List<ConversationInboxDto> items,
            @Nullable String myId,
            boolean firstPage,
            long generation) {
        Map<String, String> pageObservers = new LinkedHashMap<>();
        for (ConversationInboxDto dto : items) {
            if (!TYPE_DIRECT.equals(dto.getType()) || dto.getPeerPresence() == null) continue;
            String peerId = dto.resolvedOtherId(myId);
            if (peerId != null) pageObservers.put(peerId, dto.getId());
        }
        Map<String, String> currentObservers;
        synchronized (inboxPagingLock) {
            if (generation != inboxPageGeneration) return;
            if (firstPage) inboxPresenceObservers.clear();
            inboxPresenceObservers.putAll(pageObservers);
            currentObservers = new LinkedHashMap<>(inboxPresenceObservers);
        }
        presenceRepository.setPresenceObservers(currentObservers);
    }

    private void clearHiddenDirectPeers(
            List<ConversationInboxDto> items,
            Map<String, PeerPresence> seeds,
            @Nullable String myId) {
        for (ConversationInboxDto dto : items) {
            if (TYPE_DIRECT.equals(dto.getType() != null ? dto.getType() : TYPE_DIRECT)) {
                String peerId = dto.resolvedOtherId(myId);
                if (peerId != null && !seeds.containsKey(peerId)) presenceRepository.clear(peerId);
            }
        }
    }

    /**
     * Safe inbox convergence and reconciliation rule, scoped to one inbox
     * variant (default or archived):
     * 1. A page alone is not a deletion oracle, because active conversations can move
     *    between pages during a paged walk when new messages arrive.
     * 2. However, a first-page refresh must converge so removed/inaccessible conversations
     *    do not stay as stale rows in Room until the next delta sync or realtime push.
     * 3. Single-page complete inbox (firstPage && isComplete):
     *    The server returned the authoritative full set in a single request without paging.
     *    No conversation could have moved pages. Any local row of this variant omitted
     *    from this set (and not locally created) is definitively gone and is safely removed.
     * 4. Paged inboxes (firstPage && !isComplete, or completed multi-page walks):
     *    Any local row of this variant absent from the fetched items whose sequence is
     *    equal to or newer than the oldest sequence fetched could either be removed or
     *    have moved to another page due to concurrent message activity. To safely
     *    distinguish between the two without deleting active rows, we confirm absence
     *    against the authoritative delta endpoint (POST /api/v1/sync). If the delta
     *    response omits the watermark, the server confirms access loss and the
     *    conversation is pruned together with its local cache. If the delta response
     *    includes the watermark, the conversation is confirmed accessible.
     *
     * <p>Rows of the other variant are never candidates in any case: an archived
     * row cannot be pruned by a default-inbox walk and vice versa.
     */
    private Completable reconcileInboxRows(
            long generation,
            boolean firstPage,
            boolean isComplete,
            List<String> pageIds,
            long oldestSequenceInPage,
            boolean archived,
            @Nullable String ownerUserId) {
        if (!isInboxPageGenerationCurrent(generation)) {
            return Completable.complete();
        }
        return conversationDao.inboxFlow().firstOrError().flatMapCompletable(localRows -> {
            Set<String> pageIdSet = new HashSet<>(pageIds);
            Set<String> walkIdSet;
            synchronized (inboxPagingLock) {
                walkIdSet = new HashSet<>(inboxWalkIds);
            }

            // Case 1: First page and complete (single-page full inbox)
            if (firstPage && isComplete) {
                Set<String> toDelete = new LinkedHashSet<>();
                for (ConversationEntity row : localRows) {
                    if (row.isArchived() != archived) continue;
                    if (!pageIdSet.contains(row.getId()) && !locallyCreatedIds.contains(row.getId())) {
                        toDelete.add(row.getId());
                    }
                }
                if (toDelete.isEmpty()) return Completable.complete();
                return Completable.fromAction(() -> {
                    appDatabase.runInTransaction(() -> {
                        for (String id : toDelete) {
                            LocalConversationCleaner.deleteInTransaction(appDatabase, id, ownerUserId);
                        }
                    });
                }).andThen(Completable.fromAction(() -> locallyCreatedIds.removeAll(pageIdSet)));
            }

            // Case 2: Multi-page walk completion
            if (isComplete) {
                List<ConversationEntity> suspects = new ArrayList<>();
                Set<String> definiteDeletes = new LinkedHashSet<>();
                for (ConversationEntity row : localRows) {
                    if (row.isArchived() != archived) continue;
                    if (walkIdSet.contains(row.getId()) || locallyCreatedIds.contains(row.getId())) {
                        continue;
                    }
                    if (row.getLastMessageSeq() > 0 && row.getLastMessageSeq() < oldestSequenceInPage) {
                        // Older than the oldest row in a walk that reached the end: definitely absent.
                        definiteDeletes.add(row.getId());
                    } else {
                        // Newer or equal, or no-message chat (seq 0): could have moved during the walk; verify via delta endpoint.
                        suspects.add(row);
                    }
                }
                Completable deleteDefinite = definiteDeletes.isEmpty()
                        ? Completable.complete()
                        : Completable.fromAction(() -> {
                            appDatabase.runInTransaction(() -> {
                                for (String id : definiteDeletes) {
                                    LocalConversationCleaner.deleteInTransaction(appDatabase, id, ownerUserId);
                                }
                            });
                        });
                return deleteDefinite
                        .andThen(confirmAbsenceAndPrune(suspects, ownerUserId))
                        .andThen(Completable.fromAction(() -> locallyCreatedIds.removeAll(walkIdSet)));
            }

            // Case 3: First page of a paged inbox (firstPage && !isComplete)
            if (firstPage) {
                List<ConversationEntity> suspects = new ArrayList<>();
                for (ConversationEntity row : localRows) {
                    if (row.isArchived() != archived) continue;
                    if (!pageIdSet.contains(row.getId())
                            && !locallyCreatedIds.contains(row.getId())
                            && row.getLastMessageSeq() >= oldestSequenceInPage) {
                        // Expected to be on page 1 by sequence, but missing: suspect.
                        suspects.add(row);
                    }
                }
                return confirmAbsenceAndPrune(suspects, ownerUserId);
            }

            return Completable.complete();
        });
    }

    /**
     * Confirms suspects against the authoritative delta endpoint. The server
     * lists every conversation the caller can still access; an omitted
     * watermark therefore proves the conversation is gone, so its local cache
     * is pruned in the same transaction as the rows already applied.
     */
    private Completable confirmAbsenceAndPrune(
            List<ConversationEntity> suspects, @Nullable String ownerUserId) {
        if (suspects.isEmpty()) {
            return Completable.complete();
        }
        List<SyncRequest.Watermark> watermarks = new ArrayList<>(suspects.size());
        for (ConversationEntity suspect : suspects) {
            watermarks.add(new SyncRequest.Watermark(
                    suspect.getId(), suspect.getLastMessageSeq()));
        }
        SyncRequest request = new SyncRequest(watermarks, 1, null);
        Call<SyncResponse> call;
        try {
            call = messageApi.syncDeltas(request);
        } catch (RuntimeException e) {
            return Completable.complete();
        }
        if (call == null) {
            return Completable.complete();
        }
        return safeApiCall(call).flatMapCompletable(result -> {
            if (!(result instanceof ApiResult.Success<?> success)) {
                return Completable.complete();
            }
            SyncResponse response = (SyncResponse) success.getBody();
            if (response == null) {
                return Completable.complete();
            }
            Set<String> accessible = new HashSet<>();
            for (SyncResponse.ConversationDeltaDto delta : response.getDeltas()) {
                if (delta.getConversationId() != null) {
                    accessible.add(delta.getConversationId());
                }
            }
            List<String> toPrune = new ArrayList<>();
            for (ConversationEntity suspect : suspects) {
                if (!accessible.contains(suspect.getId())) {
                    toPrune.add(suspect.getId());
                }
            }
            if (toPrune.isEmpty()) {
                return Completable.complete();
            }
            return Completable.fromAction(() -> {
                appDatabase.runInTransaction(() -> {
                    for (String id : toPrune) {
                        LocalConversationCleaner.deleteInTransaction(appDatabase, id, ownerUserId);
                    }
                });
            });
        });
    }

    @NonNull
    private Single<Result<Void>> orderedArchive(
            @NonNull String conversationId,
            boolean archived,
            @NonNull Function<Boolean, Call<Void>> callForArchived) {
        ConversationPreferenceLanes.Ticket ticket =
                preferenceLanes.begin(conversationId, ConversationPreferenceLanes.Kind.ARCHIVE);
        return conversationDao.byId(conversationId)
                .flatMapSingle(existing -> {
                    preferenceNetwork.seedArchiveIfUnknown(conversationId, existing.isArchived());
                    return conversationDao.updateArchived(conversationId, archived)
                            .andThen(preferenceNetwork.submitArchive(
                                    conversationId, ticket, archived, callForArchived));
                })
                .switchIfEmpty(Single.just(Result.failure(
                        new ApiError.Unknown("CONVERSATION_NOT_FOUND"))));
    }

    @NonNull
    private Single<Result<Void>> orderedMute(
            @NonNull String conversationId,
            boolean muted,
            @Nullable Long mutedUntilEpochMs,
            @NonNull Function<ConversationPreferenceNetworkQueue.MuteState, Call<Void>> callForState) {
        ConversationPreferenceLanes.Ticket ticket =
                preferenceLanes.begin(conversationId, ConversationPreferenceLanes.Kind.MUTE);
        ConversationPreferenceNetworkQueue.MuteState desired =
                new ConversationPreferenceNetworkQueue.MuteState(muted, mutedUntilEpochMs);
        return conversationDao.byId(conversationId)
                .flatMapSingle(existing -> {
                    preferenceNetwork.seedMuteIfUnknown(
                            conversationId, existing.isMuted(), existing.getMutedUntilEpochMs());
                    return conversationDao.updateMute(conversationId, muted, mutedUntilEpochMs)
                            .andThen(preferenceNetwork.submitMute(
                                    conversationId, ticket, desired, callForState));
                })
                .switchIfEmpty(Single.just(Result.failure(
                        new ApiError.Unknown("CONVERSATION_NOT_FOUND"))));
    }

    @NonNull
    private Single<Result<Void>> orderedPin(
            @NonNull String conversationId,
            boolean pinned,
            @NonNull Function<ConversationPreferenceNetworkQueue.PinState, Call<Void>> callForState) {
        ConversationPreferenceLanes.Ticket ticket =
                preferenceLanes.begin(conversationId, ConversationPreferenceLanes.Kind.PIN);
        long pinnedAt = pinned ? System.currentTimeMillis() : 0L;
        Long appliedPinnedAt = pinned ? pinnedAt : null;
        ConversationPreferenceNetworkQueue.PinState desired =
                new ConversationPreferenceNetworkQueue.PinState(pinned, appliedPinnedAt);
        return conversationDao.byId(conversationId)
                .flatMapSingle(existing -> {
                    preferenceNetwork.seedPinIfUnknown(
                            conversationId, existing.isPinned(), existing.getPinnedAt());
                    return conversationDao.updatePin(conversationId, pinned, appliedPinnedAt)
                            .andThen(preferenceNetwork.submitPin(
                                    conversationId, ticket, desired, callForState));
                })
                .switchIfEmpty(Single.just(Result.failure(
                        new ApiError.Unknown("CONVERSATION_NOT_FOUND"))));
    }

    @NonNull
    private Single<Result<Void>> resolveArchiveNetwork(
            @NonNull String conversationId,
            long version,
            @NonNull Result<Void> result) {
        return resolveOrderedPreferenceResult(
                conversationId,
                ConversationPreferenceLanes.Kind.ARCHIVE,
                version,
                result,
                rollbackArchive(conversationId).andThen(Single.just(result)));
    }

    @NonNull
    private Single<Result<Void>> resolveMuteNetwork(
            @NonNull String conversationId,
            long version,
            @NonNull Result<Void> result) {
        return resolveOrderedPreferenceResult(
                conversationId,
                ConversationPreferenceLanes.Kind.MUTE,
                version,
                result,
                rollbackMute(conversationId).andThen(Single.just(result)));
    }

    @NonNull
    private Single<Result<Void>> resolvePinNetwork(
            @NonNull String conversationId,
            long version,
            @NonNull Result<Void> result) {
        return resolveOrderedPreferenceResult(
                conversationId,
                ConversationPreferenceLanes.Kind.PIN,
                version,
                result,
                rollbackPin(conversationId).andThen(Single.just(result)));
    }

    @NonNull
    private Completable rollbackArchive(@NonNull String conversationId) {
        return conversationDao.updateArchived(
                conversationId, preferenceNetwork.confirmedArchived(conversationId));
    }

    @NonNull
    private Completable rollbackMute(@NonNull String conversationId) {
        ConversationPreferenceNetworkQueue.MuteState confirmed =
                preferenceNetwork.confirmedMute(conversationId);
        return conversationDao.updateMute(
                conversationId, confirmed.muted, confirmed.mutedUntilEpochMs);
    }

    @NonNull
    private Completable rollbackPin(@NonNull String conversationId) {
        ConversationPreferenceNetworkQueue.PinState confirmed =
                preferenceNetwork.confirmedPin(conversationId);
        return conversationDao.updatePin(
                conversationId, confirmed.pinned, confirmed.pinnedAtEpochMs);
    }

    @NonNull
    private Call<Void> muteCallFor(
            @NonNull String conversationId,
            @NonNull ConversationPreferenceNetworkQueue.MuteState state) {
        if (!state.muted) {
            return settingsApi.unmute(conversationId);
        }
        if (state.mutedUntilEpochMs == null) {
            return settingsApi.mute(conversationId, MuteRequest.forever());
        }
        return settingsApi.mute(
                conversationId,
                MuteRequest.until(java.time.Instant.ofEpochMilli(state.mutedUntilEpochMs)));
    }

    @NonNull
    private Call<Void> pinCallFor(
            @NonNull String conversationId,
            @NonNull ConversationPreferenceNetworkQueue.PinState state) {
        return state.pinned
                ? messageApi.pinConversation(conversationId)
                : messageApi.unpinConversation(conversationId);
    }

    @NonNull
    private Single<Result<Void>> resolveOrderedPreferenceResult(
            @NonNull String conversationId,
            @NonNull ConversationPreferenceLanes.Kind kind,
            long version,
            @NonNull Result<Void> result,
            @NonNull Single<Result<Void>> rollback) {
        if (preferenceLanes.isStale(conversationId, kind, version)) {
            preferenceLanes.finish(conversationId, kind, version);
            return Single.just(successVoid());
        }
        if (result instanceof Result.Failure<?>) {
            return rollback.doOnSuccess(ignored ->
                    preferenceLanes.finish(conversationId, kind, version));
        }
        preferenceLanes.finish(conversationId, kind, version);
        return Single.just(successVoid());
    }

    private boolean preservePreferenceMuted(
            @Nullable ConversationEntity old, @NonNull ConversationEntity row, @NonNull String id) {
        if (old != null
                && preferenceLanes.preserveLocalOverServer(
                        id, ConversationPreferenceLanes.Kind.MUTE)) {
            return old.isMuted();
        }
        return row.isMuted();
    }

    @Nullable
    private Long preservePreferenceMutedUntil(
            @Nullable ConversationEntity old, @NonNull ConversationEntity row, @NonNull String id) {
        if (old != null
                && preferenceLanes.preserveLocalOverServer(
                        id, ConversationPreferenceLanes.Kind.MUTE)) {
            return old.getMutedUntilEpochMs();
        }
        return row.getMutedUntilEpochMs();
    }

    private boolean preservePreferenceArchived(
            @Nullable ConversationEntity old, @NonNull ConversationEntity row, @NonNull String id) {
        if (old != null
                && preferenceLanes.preserveLocalOverServer(
                        id, ConversationPreferenceLanes.Kind.ARCHIVE)) {
            return old.isArchived();
        }
        return row.isArchived();
    }

    private boolean preservePreferencePinned(
            @Nullable ConversationEntity old, @NonNull ConversationEntity row, @NonNull String id) {
        if (old != null
                && preferenceLanes.preserveLocalOverServer(
                        id, ConversationPreferenceLanes.Kind.PIN)) {
            return old.isPinned();
        }
        return row.isPinned();
    }

    @Nullable
    private Long preservePreferencePinnedAt(
            @Nullable ConversationEntity old, @NonNull ConversationEntity row, @NonNull String id) {
        if (old != null
                && preferenceLanes.preserveLocalOverServer(
                        id, ConversationPreferenceLanes.Kind.PIN)) {
            return old.getPinnedAt();
        }
        return row.getPinnedAt();
    }

    /**
     * Room uses REPLACE, so preserve client-only fields that REST does not
     * carry: {@code peerReadSeq}, an already-known role, and the peer id.
     */
    private Completable upsertPreservingClientColumns(List<ConversationEntity> rows) {
        if (rows.isEmpty()) return Completable.complete();
        List<String> ids = new ArrayList<>(rows.size());
        for (ConversationEntity row : rows) ids.add(row.getId());
        return conversationDao.byIds(ids).flatMapCompletable(priorRows -> {
            Map<String, ConversationEntity> prior = new HashMap<>();
            for (ConversationEntity row : priorRows) prior.put(row.getId(), row);
            List<ConversationEntity> merged = new ArrayList<>(rows.size());
            for (ConversationEntity row : rows) {
                ConversationEntity old = prior.get(row.getId());
                String conversationId = row.getId();
                if (!preferenceLanes.preserveLocalOverServer(
                        conversationId, ConversationPreferenceLanes.Kind.MUTE)) {
                    preferenceNetwork.noteServerMute(
                            conversationId, row.isMuted(), row.getMutedUntilEpochMs());
                }
                if (!preferenceLanes.preserveLocalOverServer(
                        conversationId, ConversationPreferenceLanes.Kind.ARCHIVE)) {
                    preferenceNetwork.noteServerArchive(conversationId, row.isArchived());
                }
                if (!preferenceLanes.preserveLocalOverServer(
                        conversationId, ConversationPreferenceLanes.Kind.PIN)) {
                    preferenceNetwork.noteServerPin(
                            conversationId, row.isPinned(), row.getPinnedAt());
                }
                boolean keepNewerLocalActivity = old != null
                        && old.getLastMessageSeq() > row.getLastMessageSeq();
                merged.add(new ConversationEntity(
                        row.getId(),
                        row.getType(),
                        row.getTitle(),
                        keepNewerLocalActivity ? old.getLastMessageSeq() : row.getLastMessageSeq(),
                        keepNewerLocalActivity ? old.getLastMessageContent() : row.getLastMessageContent(),
                        keepNewerLocalActivity ? old.getLastMessageSenderName() : row.getLastMessageSenderName(),
                        keepNewerLocalActivity ? old.getLastMessageAt() : row.getLastMessageAt(),
                        keepNewerLocalActivity ? old.getUnreadCount() : row.getUnreadCount(),
                        row.getOtherUserName(),
                        row.getOtherUserAvatar(),
                        row.getAvatarUrl(),
                        old != null ? old.getPeerReadSeq() : 0L,
                        row.getMyRole() != null ? row.getMyRole() : old != null ? old.getMyRole() : null,
                        row.getOtherUserId() != null
                                ? row.getOtherUserId()
                                : old != null ? old.getOtherUserId() : null,
                        keepNewerLocalActivity
                                ? old.getLastMessageDeleted()
                                : row.getLastMessageDeleted(),
                        old != null ? old.getPeerDeliveredSeq() : 0L,
                        preservePreferenceMuted(old, row, row.getId()),
                        preservePreferenceMutedUntil(old, row, row.getId()),
                        preservePreferenceArchived(old, row, row.getId()),
                        row.isAiOptOut(),
                        preservePreferencePinned(old, row, row.getId()),
                        preservePreferencePinnedAt(old, row, row.getId()),
                        row.getLabelIdsCsv()));
            }
            return conversationDao.upsertAll(merged);
        }).subscribeOn(Schedulers.io());
    }

    /** Caller read watermark remembered for inbox rendering and unread divider anchoring. */
    public long peekMyLastReadSeq(@NonNull String conversationId) {
        return inboxReadWatermarkCache.peek(conversationId);
    }

    private void rememberInboxLastReadSeq(@NonNull String conversationId, @Nullable Long lastReadSeq) {
        if (lastReadSeq == null || lastReadSeq <= 0L) {
            return;
        }
        inboxReadWatermarkCache.adopt(conversationId, lastReadSeq);
        if (messageRepository != null) {
            messageRepository.adoptInboxReadSeqFromCache(conversationId, lastReadSeq);
        }
    }

    /** Maps the flexible inbox wire shape into the stable Room row. */
    private ConversationEntity toEntity(ConversationInboxDto dto, @Nullable String myId) {
        if (dto.getId() != null) {
            rememberInboxLastReadSeq(dto.getId(), dto.getLastReadSeq());
        }
        Long lastSeq = dto.resolvedLastSeq();
        String lastAt = dto.resolvedLastAt();
        List<ConversationMemberDto> members = dto.getMembers();
        String myRole = null;
        for (ConversationMemberDto member : members) {
            if (myId != null && myId.equals(member.getUserId())) {
                myRole = member.getRole();
                break;
            }
        }
        boolean pinned = dto.isPinned();
        Long pinnedAtMs = dto.getPinnedAt() != null ? TimeAndJwt.isoToEpochMsOrNull(dto.getPinnedAt()) : null;
        return new ConversationEntity(
                dto.getId(),
                dto.getType() != null ? dto.getType() : TYPE_DIRECT,
                dto.getTitle(),
                lastSeq != null ? lastSeq : 0L,
                dto.resolvedLastContent(),
                dto.resolvedLastSender(),
                lastAt != null ? TimeAndJwt.isoToEpochMs(lastAt) : null,
                dto.getUnreadCount() != null ? dto.getUnreadCount() : 0,
                dto.resolvedOtherName(myId),
                dto.resolvedOtherAvatar(myId),
                dto.getAvatarUrl(),
                0L,
                myRole,
                dto.resolvedOtherId(myId),
                dto.getLastMessage() != null && dto.getLastMessage().getDeletedAt() != null,
                0L,
                Boolean.TRUE.equals(dto.getMuted()),
                dto.getMutedUntil() == null
                        ? null
                        : TimeAndJwt.isoToEpochMsOrNull(dto.getMutedUntil()),
                Boolean.TRUE.equals(dto.getArchived()),
                Boolean.TRUE.equals(dto.getAiOptOut()),
                pinned,
                pinnedAtMs,
                String.join(",", dto.getLabels()));
    }

    private ConversationEntity toEntity(ConversationDto dto, String fallbackTitle) {
        return new ConversationEntity(
                dto.getId(),
                dto.getType() != null ? dto.getType() : TYPE_GROUP,
                dto.getTitle() != null ? dto.getTitle() : fallbackTitle,
                0L,
                null,
                null,
                null,
                0,
                null,
                null,
                dto.getAvatarUrl(),
                0L,
                GroupMember.ROLE_ADMIN,
                null,
                false,
                0L,
                false,
                null,
                false,
                Boolean.TRUE.equals(dto.getAiOptOut()),
                false,
                null);
    }

    private List<GroupMember> mapMembers(List<ConversationMemberDto> members) {
        List<GroupMember> mapped = new ArrayList<>();
        for (ConversationMemberDto member : members) {
            String userId = member.getUserId();
            if (userId == null) continue;
            String displayName = member.getDisplayName() != null
                    ? member.getDisplayName()
                    : member.getPhone() != null ? member.getPhone() : "";
            mapped.add(new GroupMember(
                    userId, displayName, member.getPhone(), member.getAvatarUrl(), member.getRole()));
        }
        return Collections.unmodifiableList(mapped);
    }

    private ConversationSummary toSummary(
            ConversationEntity row,
            Map<String, PeerPresence> presence) {
        PeerPresence peer = TYPE_DIRECT.equals(row.getType()) && row.getOtherUserId() != null
                ? presence.get(row.getOtherUserId())
                : null;
        long myLastReadSeq = peekMyLastReadSeq(row.getId());
        return new ConversationSummary(
                row.getId(),
                row.getType(),
                row.getTitle(),
                row.getLastMessageContent(),
                row.getLastMessageSenderName(),
                row.getLastMessageAt(),
                row.getLastMessageSeq(),
                row.getUnreadCount(),
                myLastReadSeq,
                row.getOtherUserName(),
                row.getOtherUserAvatar(),
                row.getAvatarUrl(),
                row.getPeerReadSeq(),
                row.getPeerDeliveredSeq(),
                row.getMyRole(),
                row.getOtherUserId(),
                row.getLastMessageDeleted(),
                peer,
                row.isMuted(),
                row.getMutedUntilEpochMs(),
                row.isArchived(),
                row.isAiOptOut(),
                row.isPinned(),
                row.getPinnedAt(),
                row.getLabelIds());
    }

    private ConversationEntity withTitle(ConversationEntity row, String title) {
        return row.withTitle(title);
    }

    private ConversationEntity withAvatarUrl(ConversationEntity row, @Nullable String avatarUrl) {
        return row.withAvatarUrl(avatarUrl);
    }

    private String resolveMime(Uri uri) {
        try {
            String mime = context.getContentResolver().getType(uri);
            return mime != null ? mime : "image/jpeg";
        } catch (RuntimeException ignored) {
            return "image/jpeg";
        }
    }

    /** Reads a picked avatar without trusting content-provider size metadata. */
    private byte[] readBoundedAvatar(Uri uri) {
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) return null;
            byte[] buffer = new byte[AVATAR_READ_BUFFER_BYTES];
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            int total = 0;
            while (true) {
                int read = input.read(buffer);
                if (read < 0) return output.toByteArray();
                if (read == 0) {
                    int single = input.read();
                    if (single < 0) return output.toByteArray();
                    if (total == MAX_AVATAR_BYTES) return null;
                    output.write(single);
                    total++;
                    continue;
                }
                if (total + read > MAX_AVATAR_BYTES) return null;
                output.write(buffer, 0, read);
                total += read;
            }
        } catch (IOException ignored) {
            return null;
        }
    }

    /** Maps network-layer outcomes to the domain Result vocabulary. */
    private <T> Single<Result<T>> call(@NonNull Call<T> call) {
        return call(call, ApiError::from);
    }

    private <T> Single<Result<T>> call(
            @NonNull Call<T> call,
            @NonNull BiFunction<Integer, String, ApiError> mapError) {
        return safeApiCall(call).map(result -> toDomainResult(result, mapError));
    }

    private Single<Result<Void>> callVoid(@NonNull Call<?> call) {
        return callVoid(call, ApiError::from);
    }

    private Single<Result<Void>> callVoid(
            @NonNull Call<?> call,
            @NonNull BiFunction<Integer, String, ApiError> mapError) {
        return safeApiCall(call).map(result -> {
            if (result instanceof ApiResult.Success<?>) return successVoid();
            if (result instanceof ApiResult.HttpError<?> httpError) {
                return Result.<Void>failure(mapError.apply(httpError.getStatus(), httpError.getErrorCode()));
            }
            ApiResult.NetworkError<?> networkError = (ApiResult.NetworkError<?>) result;
            return Result.<Void>failure(new ApiError.Network(networkError.getCause().getMessage()));
        });
    }

    /**
     * Shared Retrofit adapter. IO failures stay typed ApiResult values; an
     * erased empty-body cast becomes an unexpected-body error.
     */
    private <T> Single<ApiResult<T>> safeApiCall(@NonNull Call<T> call) {
        return Single.defer(() -> ApiResult.safeApiCall(call))
                .onErrorResumeNext(error -> {
                    if (error instanceof ClassCastException) {
                        return Single.just(new ApiResult.HttpError<T>(
                                200, ERROR_UNEXPECTED_EMPTY_BODY));
                    }
                    return Single.error(error);
                })
                .subscribeOn(Schedulers.io());
    }

    private <T> Result<T> toDomainResult(
            ApiResult<T> result,
            BiFunction<Integer, String, ApiError> mapError) {
        if (result instanceof ApiResult.Success<?> success) {
            Object body = success.getBody();
            if (body == null || body instanceof com.nexachat.core.common.Unit) {
                return Result.<T>failure(new ApiError.Unknown(ERROR_UNEXPECTED_EMPTY_BODY));
            }
            @SuppressWarnings("unchecked")
            T value = (T) body;
            return new Result.Success<>(value);
        }
        if (result instanceof ApiResult.HttpError<?> httpError) {
            return Result.<T>failure(mapError.apply(httpError.getStatus(), httpError.getErrorCode()));
        }
        ApiResult.NetworkError<?> networkError = (ApiResult.NetworkError<?>) result;
        return Result.<T>failure(new ApiError.Network(networkError.getCause().getMessage()));
    }

    @SuppressWarnings("unchecked")
    private static <T> T successValue(Result<T> result) {
        return (T) ((Result.Success<?>) result).getValue();
    }

    private static Result<Void> successVoid() {
        return new Result.Success<>(null);
    }

    private static final class InboxFetch {
        private final List<ConversationInboxDto> items;
        @Nullable private final String nextCursor;
        private final boolean complete;

        private InboxFetch(List<ConversationInboxDto> items, @Nullable String nextCursor, boolean complete) {
            this.items = items;
            this.nextCursor = nextCursor;
            this.complete = complete;
        }
    }

    /** Keeps response headers available while {@code safeApiCall} executes the Retrofit call. */
    private static final class ResponseCapturingCall<T> implements Call<T> {
        private final Call<T> delegate;
        private final java.util.function.Consumer<Response<T>> capture;

        private ResponseCapturingCall(Call<T> delegate, java.util.function.Consumer<Response<T>> capture) {
            this.delegate = delegate;
            this.capture = capture;
        }

        @Override
        public Response<T> execute() throws IOException {
            Response<T> response = delegate.execute();
            capture.accept(response);
            return response;
        }

        @Override
        public void enqueue(Callback<T> callback) {
            delegate.enqueue(new Callback<>() {
                @Override
                public void onResponse(Call<T> call, Response<T> response) {
                    capture.accept(response);
                    callback.onResponse(ResponseCapturingCall.this, response);
                }

                @Override
                public void onFailure(Call<T> call, Throwable error) {
                    callback.onFailure(ResponseCapturingCall.this, error);
                }
            });
        }

        @Override
        public boolean isExecuted() { return delegate.isExecuted(); }
        @Override
        public void cancel() { delegate.cancel(); }
        @Override
        public boolean isCanceled() { return delegate.isCanceled(); }
        @Override
        public Call<T> clone() { return new ResponseCapturingCall<>(delegate.clone(), capture); }
        @Override
        public okhttp3.Request request() { return delegate.request(); }
        @Override
        public Timeout timeout() { return delegate.timeout(); }
    }
}
