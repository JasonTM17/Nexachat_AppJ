package com.nexachat.data;

import android.content.Context;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.common.Result;
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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
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
 * a walk started at page one reaches a response without a next cursor; then
 * absent rows are removed only when their positive {@code lastMessageSeq} is
 * lower than the oldest sequence observed in the walk. Equal/newer activity,
 * rows without a sequence, and locally-created conversations are retained
 * because Room does not store the server cursor's creation-time tie-breaker.
 * A complete empty response clears old rows except local creations awaiting
 * server visibility. Reconnect resync requests perform the same page-one pull.
 *
 * <p>Mute, archive and AI opt-out are caller-scoped server preferences. Writes
 * go through the REST endpoints first and are written through to Room only on
 * success; inbox payloads and delta bundles carry the authoritative mute and
 * archive flags. The archived inbox is a separate keyset walk ({@code
 * archived=true}); pruning is scoped to the walk's variant so one filter can
 * never delete the other variant's rows.
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
    /**
     * Last caller-known AI opt-out per conversation. Additive inbox payloads can
     * omit the field during a rolling deploy; preserving the last known value
     * avoids silently flipping the switch back off.
     */
    private final Map<String, Boolean> knownAiOptOut = new ConcurrentHashMap<>();
    private final CompositeDisposable subscriptions = new CompositeDisposable();
    private final Object inboxPagingLock = new Object();
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
            @ApplicationContext @NonNull Context context) {
        this.messageApi = messageApi;
        this.groupApi = groupApi;
        this.settingsApi = settingsApi;
        this.conversationDao = conversationDao;
        this.presenceRepository = presenceRepository;
        this.session = session;
        this.tokenStore = tokenStore;
        this.moshi = moshi;
        this.context = context;
        this.pageAdapter = moshi.adapter(ConversationsPageDto.class);
        this.listAdapter = moshi.adapter(Types.newParameterizedType(
                List.class, ConversationInboxDto.class));
        watchPushTriggers();
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
        return currentUserIdOptional()
                .flatMap(myId -> fetchAndPersistInboxPage(
                        generation, INBOX_PAGE_SIZE, null, myId.orElse(null), true, archived))
                .subscribeOn(Schedulers.io());
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
            Single<Result<Boolean>> shared = currentUserIdOptional()
                    .flatMap(myId -> fetchAndPersistInboxPage(
                            generation, INBOX_PAGE_SIZE, cursor, myId.orElse(null), false, archived))
                    .doFinally(() -> {
                        synchronized (inboxPagingLock) {
                            if (inFlightNextInboxPage == sharedRef.get()) {
                                inFlightNextInboxPage = null;
                            }
                        }
                    })
                    .cache();
            sharedRef.set(shared);
            inFlightNextInboxPage = shared;
            return shared;
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
        return callVoid(settingsApi.mute(conversationId, MuteRequest.until(until)))
                .flatMap(result -> result instanceof Result.Failure<?>
                        ? Single.just(result)
                        : conversationDao.updateMute(conversationId, true, until.toEpochMilli())
                                .andThen(Single.just(successVoid())))
                .subscribeOn(Schedulers.io());
    }

    /** Mutes the conversation until the caller turns notifications back on. */
    @NonNull
    public Single<Result<Void>> muteConversationForever(@NonNull String conversationId) {
        return callVoid(settingsApi.mute(conversationId, MuteRequest.forever()))
                .flatMap(result -> result instanceof Result.Failure<?>
                        ? Single.just(result)
                        : conversationDao.updateMute(conversationId, true, null)
                                .andThen(Single.just(successVoid())))
                .subscribeOn(Schedulers.io());
    }

    /** Clears the caller's mute; idempotent when already unmuted. */
    @NonNull
    public Single<Result<Void>> unmuteConversation(@NonNull String conversationId) {
        return callVoid(settingsApi.unmute(conversationId))
                .flatMap(result -> result instanceof Result.Failure<?>
                        ? Single.just(result)
                        : conversationDao.updateMute(conversationId, false, null)
                                .andThen(Single.just(successVoid())))
                .subscribeOn(Schedulers.io());
    }

    /** Hides or restores the conversation in the caller's default inbox. */
    @NonNull
    public Single<Result<Void>> setConversationArchived(
            @NonNull String conversationId, boolean archived) {
        Call<Void> call = archived
                ? settingsApi.archive(conversationId)
                : settingsApi.unarchive(conversationId);
        return callVoid(call)
                .flatMap(result -> result instanceof Result.Failure<?>
                        ? Single.just(result)
                        : conversationDao.updateArchived(conversationId, archived)
                                .andThen(Single.just(successVoid())))
                .subscribeOn(Schedulers.io());
    }

    /** Reads the caller's own AI opt-out state for one conversation. */
    @NonNull
    public Single<Result<Boolean>> getAiOptOut(@NonNull String conversationId) {
        return call(settingsApi.getAiOptOut(conversationId))
                .flatMap(result -> {
                    if (result instanceof Result.Failure<?>) {
                        return Single.<Result<Boolean>>just(Result.cast(result));
                    }
                    Boolean optOut = successValue(result).getAiOptOut();
                    boolean value = Boolean.TRUE.equals(optOut);
                    knownAiOptOut.put(conversationId, value);
                    return conversationDao.updateAiOptOut(conversationId, value)
                            .andThen(Single.fromCallable(() -> new Result.Success<>(value)));
                })
                .subscribeOn(Schedulers.io());
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
        return call(call)
                .flatMap(result -> {
                    if (result instanceof Result.Failure<?>) {
                        return Single.<Result<Boolean>>just(Result.cast(result));
                    }
                    Boolean resolved = successValue(result).getAiOptOut();
                    boolean value = resolved == null ? optOut : resolved;
                    knownAiOptOut.put(conversationId, value);
                    return conversationDao.updateAiOptOut(conversationId, value)
                            .andThen(Single.fromCallable(() -> new Result.Success<>(value)));
                })
                .subscribeOn(Schedulers.io());
    }

    /**
     * Creates a GROUP and writes through the row so the new chat screen has a
     * title before the server broadcast lands. The create response omits the
     * title, so the requested value is kept as a fallback.
     */
    @NonNull
    public Single<Result<String>> createGroup(@NonNull String title, @NonNull List<String> memberIds) {
        String trimmed = title.trim();
        return call(groupApi.createGroup(new CreateGroupRequest(trimmed, memberIds)))
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
                .subscribeOn(Schedulers.io());
    }

    /**
     * Member list and title for the group detail screen. No individual detail
     * endpoint is assumed, so this walks the inbox's opaque cursor pages until
     * it finds the row and writes it through to Room.
     */
    @NonNull
    public Single<Result<GroupDetail>> groupDetail(@NonNull String conversationId) {
        return currentUserIdOptional()
                .flatMap(myId -> groupDetailPage(
                        conversationId, myId.orElse(null), null, new HashSet<>()))
                .subscribeOn(Schedulers.io());
    }

    @NonNull
    public Single<Result<Void>> addGroupMembers(
            @NonNull String conversationId,
            @NonNull List<String> userIds) {
        return callVoid(groupApi.addMembers(
                conversationId, new AddGroupMembersRequest(userIds)))
                .subscribeOn(Schedulers.io());
    }

    @NonNull
    public Single<Result<Void>> removeGroupMember(
            @NonNull String conversationId,
            @NonNull String userId) {
        return callVoid(groupApi.removeMember(conversationId, userId))
                .subscribeOn(Schedulers.io());
    }

    /**
     * Renames the group (ADMIN-only server-side). On success the local row is
     * patched so {@link #conversationFlow(String)} re-emits without waiting
     * for {@code CONVERSATION_UPDATED}.
     */
    @NonNull
    public Single<Result<Void>> renameGroup(@NonNull String conversationId, @NonNull String title) {
        String trimmed = title.trim();
        return callVoid(groupApi.updateConversation(
                        conversationId, new UpdateConversationRequest(trimmed)))
                .flatMap(result -> {
                    if (result instanceof Result.Failure<?>) {
                        return Single.<Result<Void>>just(Result.cast(result));
                    }
                    return conversationDao.byId(conversationId)
                            .flatMapCompletable(row -> conversationDao.upsert(withTitle(row, trimmed)))
                            .andThen(Single.fromCallable(ConversationRepository::successVoid));
                })
                .subscribeOn(Schedulers.io());
    }

    /**
     * Group avatar upload (ADMIN-only server-side). On success the local row
     * is patched for an immediate inbox update; other members get the change
     * through {@code CONVERSATION_UPDATED} and their next inbox refresh.
     */
    @NonNull
    public Single<Result<Void>> setGroupAvatar(@NonNull String conversationId, @NonNull Uri uri) {
        return Maybe.fromCallable(() -> readBoundedAvatar(uri))
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
                });
    }

    /**
     * Self-leave. The DAO has no delete-by-id operation, so clear and refill
     * the conversation table from the authoritative inbox after server success.
     */
    @NonNull
    public Single<Result<Void>> leaveGroup(@NonNull String conversationId) {
        return callVoid(groupApi.leaveGroup(conversationId))
                .flatMap(result -> {
                    if (result instanceof Result.Failure<?>) {
                        return Single.<Result<Void>>just(Result.cast(result));
                    }
                    return conversationDao.clear().andThen(refreshInbox());
                })
                .subscribeOn(Schedulers.io());
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
            refreshRequests.onNext(Boolean.TRUE);
        } else if (event instanceof ChatEvent.MessageCreated) {
            refreshRequests.onNext(Boolean.TRUE);
        }
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
        AtomicReference<ResponseBody> responseBody = new AtomicReference<>();
        AtomicReference<String> headerCursor = new AtomicReference<>();
        Call<ResponseBody> request = archived
                ? messageApi.getArchivedConversations(limit, cursor)
                : messageApi.getConversations(limit, cursor);
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
            AtomicReference<PruneSnapshot> pruneSnapshot = new AtomicReference<>();
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
                                pruneSnapshot.set(new PruneSnapshot(
                                        new LinkedHashSet<>(inboxWalkIds), oldestInboxSequence,
                                        archived));
                                inboxWalkStartedAtFirstPage = false;
                            }
                        }
                    }))
                    .andThen(Completable.fromAction(() -> {
                        if (isInboxPageGenerationCurrent(generation)) {
                            presenceRepository.seed(presenceSeeds);
                            clearHiddenDirectPeers(fetch.items, presenceSeeds, myId);
                        }
                    }))
                    .andThen(Completable.defer(() -> {
                        PruneSnapshot completedWalk = pruneSnapshot.get();
                        return completedWalk == null
                                ? Completable.complete()
                                : pruneAfterCompleteWalk(completedWalk);
                    }))
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
     * A page is never a deletion oracle. Only a walk that began at page one
     * and reached a response with no next cursor may prune. The walk owns one
     * inbox variant: pruning never deletes rows from the other variant. For a
     * non-empty walk, compare the sequence (the only activity key Room stores)
     * and delete absent local rows strictly older than the oldest fetched row;
     * retain equal/newer rows and local creations because they may have moved
     * while pages were being read. If the server proves the variant is empty,
     * clear only that variant, except creations still waiting to be observed.
     */
    private Completable pruneAfterCompleteWalk(PruneSnapshot walk) {
        return conversationDao.inboxFlow().firstOrError().flatMapCompletable(localRows -> {
            Set<String> keep = new LinkedHashSet<>(walk.fetchedIds);
            if (walk.fetchedIds.isEmpty()) {
                if (!walk.archived) keep.addAll(locallyCreatedIds);
            } else {
                for (ConversationEntity row : localRows) {
                    if (row.isArchived() != walk.archived) continue;
                    if (row.getLastMessageSeq() == 0
                            || row.getLastMessageSeq() >= walk.oldestSequence) {
                        keep.add(row.getId());
                    }
                }
                if (!walk.archived) keep.addAll(locallyCreatedIds);
            }
            Completable prune;
            if (keep.isEmpty()) {
                prune = walk.archived
                        ? conversationDao.clearArchived()
                        : conversationDao.clearInbox();
            } else {
                prune = walk.archived
                        ? conversationDao.deleteArchivedExcept(new ArrayList<>(keep))
                        : conversationDao.deleteInboxExcept(new ArrayList<>(keep));
            }
            return prune;
        }).andThen(Completable.fromAction(() -> locallyCreatedIds.removeAll(walk.fetchedIds)));
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
                        row.isMuted(),
                        row.getMutedUntilEpochMs(),
                        row.isArchived(),
                        row.isAiOptOut()));
            }
            return conversationDao.upsertAll(merged);
        }).subscribeOn(Schedulers.io());
    }

    /** Maps the flexible inbox wire shape into the stable Room row. */
    private ConversationEntity toEntity(ConversationInboxDto dto, @Nullable String myId) {
        Long lastSeq = dto.resolvedLastSeq();
        String lastAt = dto.resolvedLastAt();
        Boolean aiOptOut = dto.getAiOptOut();
        if (aiOptOut != null) {
            knownAiOptOut.put(dto.getId(), aiOptOut);
        }
        List<ConversationMemberDto> members = dto.getMembers();
        String myRole = null;
        for (ConversationMemberDto member : members) {
            if (myId != null && myId.equals(member.getUserId())) {
                myRole = member.getRole();
                break;
            }
        }
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
                aiOptOut != null
                        ? aiOptOut
                        : Boolean.TRUE.equals(knownAiOptOut.get(dto.getId())));
    }

    private ConversationEntity toEntity(ConversationDto dto, String fallbackTitle) {
        Boolean aiOptOut = dto.getAiOptOut();
        if (aiOptOut != null) {
            knownAiOptOut.put(dto.getId(), aiOptOut);
        }
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
                aiOptOut != null
                        ? aiOptOut
                        : Boolean.TRUE.equals(knownAiOptOut.get(dto.getId())));
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
        // Seed the last-known AI state from persisted Room rows before a refresh
        // can see an inbox payload that omits the additive field.
        knownAiOptOut.putIfAbsent(row.getId(), row.isAiOptOut());
        PeerPresence peer = TYPE_DIRECT.equals(row.getType()) && row.getOtherUserId() != null
                ? presence.get(row.getOtherUserId())
                : null;
        return new ConversationSummary(
                row.getId(),
                row.getType(),
                row.getTitle(),
                row.getLastMessageContent(),
                row.getLastMessageSenderName(),
                row.getLastMessageAt(),
                row.getLastMessageSeq(),
                row.getUnreadCount(),
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
                row.isAiOptOut());
    }

    private ConversationEntity withTitle(ConversationEntity row, String title) {
        return new ConversationEntity(
                row.getId(), row.getType(), title, row.getLastMessageSeq(),
                row.getLastMessageContent(), row.getLastMessageSenderName(), row.getLastMessageAt(),
                row.getUnreadCount(), row.getOtherUserName(), row.getOtherUserAvatar(),
                row.getAvatarUrl(), row.getPeerReadSeq(), row.getMyRole(), row.getOtherUserId(),
                row.getLastMessageDeleted(), row.getPeerDeliveredSeq(), row.isMuted(),
                row.getMutedUntilEpochMs(), row.isArchived(), row.isAiOptOut());
    }

    private ConversationEntity withAvatarUrl(ConversationEntity row, @Nullable String avatarUrl) {
        return new ConversationEntity(
                row.getId(), row.getType(), row.getTitle(), row.getLastMessageSeq(),
                row.getLastMessageContent(), row.getLastMessageSenderName(), row.getLastMessageAt(),
                row.getUnreadCount(), row.getOtherUserName(), row.getOtherUserAvatar(),
                avatarUrl, row.getPeerReadSeq(), row.getMyRole(), row.getOtherUserId(),
                row.getLastMessageDeleted(), row.getPeerDeliveredSeq(), row.isMuted(),
                row.getMutedUntilEpochMs(), row.isArchived(), row.isAiOptOut());
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

    private static final class PruneSnapshot {
        private final Set<String> fetchedIds;
        private final long oldestSequence;
        private final boolean archived;

        private PruneSnapshot(Set<String> fetchedIds, long oldestSequence, boolean archived) {
            this.fetchedIds = fetchedIds;
            this.oldestSequence = oldestSequence;
            this.archived = archived;
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
