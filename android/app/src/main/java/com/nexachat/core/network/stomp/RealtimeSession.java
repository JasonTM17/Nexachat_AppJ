package com.nexachat.core.network.stomp;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.nexachat.core.network.AppForegroundState;
import com.nexachat.core.network.NetworkMonitor;
import com.nexachat.core.network.TokenRefreshOutcome;
import com.nexachat.core.network.TokenRefresher;
import com.nexachat.core.network.dto.MessageDto;
import com.nexachat.core.network.dto.PinUpdateDto;
import com.nexachat.core.network.dto.ReactionUpdateDto;
import com.nexachat.core.network.dto.ConversationUpdateDto;
import com.nexachat.core.network.dto.SendMessageRequest;
import com.nexachat.core.network.stomp.dto.MessageDeletedDto;
import com.nexachat.core.network.stomp.dto.PresenceEventDto;
import com.nexachat.core.network.stomp.dto.ReadUpdateDto;
import com.nexachat.core.network.stomp.dto.SendErrorDto;
import com.nexachat.core.network.stomp.dto.TypingSignalDto;
import com.nexachat.core.network.stomp.dto.WsEnvelope;
import com.nexachat.core.storage.TokenStore;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.disposables.SerialDisposable;
import io.reactivex.rxjava3.processors.BehaviorProcessor;
import io.reactivex.rxjava3.processors.FlowableProcessor;
import io.reactivex.rxjava3.processors.PublishProcessor;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.core.Scheduler;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Owns the STOMP session lifecycle and maps inbound frames to typed events.
 *
 * <p>The connection loop is caller-owned per the {@link StompClient} contract.
 * It shadows {@link TokenStore}'s valid-session flow, reconnects with
 * {@link ReconnectPolicy}, and re-arms after a user action, a validated
 * network regain, app foregrounding, or a bounded foreground retry timer.
 */
@Singleton
public final class RealtimeSession {

    public static final String APP_PREFIX = "/app";
    public static final String TOPIC_PREFIX = "/topic/conversations.";
    public static final String USER_INBOX_DESTINATION = "/user/queue/inbox";
    public static final String USER_ERRORS_DESTINATION = "/user/queue/errors";
    public static final String USER_PRESENCE_DESTINATION = "/user/queue/presence";

    public static final String EVENT_MESSAGE_CREATED = "MESSAGE_CREATED";
    public static final String EVENT_MESSAGE_UPDATED = "MESSAGE_UPDATED";
    public static final String EVENT_MESSAGE_DELETED = "MESSAGE_DELETED";
    public static final String EVENT_USER_ONLINE = "USER_ONLINE";
    public static final String EVENT_USER_OFFLINE = "USER_OFFLINE";
    public static final String EVENT_USER_TYPING = "USER_TYPING";
    public static final String EVENT_MESSAGE_READ = "MESSAGE_READ";
    public static final String EVENT_MESSAGE_DELIVERED = "MESSAGE_DELIVERED";
    public static final String EVENT_CONVERSATION_UPDATED = "CONVERSATION_UPDATED";
    public static final String EVENT_REACTION_UPDATED = "REACTION_UPDATED";
    public static final String EVENT_PIN_UPDATED = "PIN_UPDATED";

    /** First parked re-arm delay; doubles per round up to {@link #PARKED_RETRY_MAX_MS}. */
    public static final long PARKED_RETRY_BASE_MS = 5_000L;
    public static final long PARKED_RETRY_MAX_MS = 60_000L;

    private static final int NORMAL_CODE = 1000;
    private static final int EVENTS_BUFFER = 128;
    private static final int RESYNC_BUFFER = 4;

    private final StompClient stompClient;
    private final TokenStore tokenStore;
    private final TokenRefresher tokenRefresher;
    private final ReconnectPolicy reconnectPolicy;
    private final NetworkMonitor networkMonitor;
    private final AppForegroundState foreground;
    private final Scheduler reconnectScheduler;

    private final JsonAdapter<WsEnvelope> envelopeAdapter;
    private final JsonAdapter<MessageDto> messageAdapter;
    private final JsonAdapter<SendMessageRequest> sendAdapter;
    private final JsonAdapter<TypingSignalDto> typingAdapter;
    private final JsonAdapter<ReadUpdateDto> readAdapter;
    private final JsonAdapter<SendErrorDto> sendErrorAdapter;
    private final JsonAdapter<ReactionUpdateDto> reactionAdapter;
    private final JsonAdapter<PinUpdateDto> pinAdapter;
    private final JsonAdapter<MessageDeletedDto> messageDeletedAdapter;
    private final JsonAdapter<PresenceEventDto> presenceAdapter;
    private final JsonAdapter<ConversationUpdateDto> conversationUpdateAdapter;
    private final JsonAdapter<Map<String, Object>> mapAdapter;

    private final FlowableProcessor<StompClient.State> state = BehaviorProcessor.<StompClient.State>createDefault(
            new StompClient.State.Closed(NORMAL_CODE, "never connected")).toSerialized();
    private final BehaviorProcessor<Boolean> openState = BehaviorProcessor.createDefault(false);
    private final FlowableProcessor<Boolean> isOpen = openState.toSerialized();
    private final FlowableProcessor<ChatEvent> events = PublishProcessor.<ChatEvent>create().toSerialized();
    private final FlowableProcessor<Object> resyncRequests = PublishProcessor.create().toSerialized();
    private final FlowableProcessor<Object> rearmRequests = PublishProcessor.create().toSerialized();
    private final CompositeDisposable subscriptions = new CompositeDisposable();
    private final SerialDisposable connection = new SerialDisposable();
    private final SerialDisposable parkedWake = new SerialDisposable();
    private final SerialDisposable authRecovery = new SerialDisposable();
    private final AtomicLong connectionAttemptSeq = new AtomicLong();
    private volatile long activeConnectionAttemptId;
    private volatile boolean hasSession;
    private volatile boolean tokensPresent;
    private volatile boolean sessionAdmitted;
    private boolean attemptActive;
    private int failures;
    private int parkedRounds;
    /** Consecutive auth-suspect failures since the last successful CONNECT. */
    /** Set by the attempt's state listener so finishAttempt resolves auth before retrying. */
    private volatile boolean authFailurePending;
    @Nullable private volatile String attemptAccessToken;

    /** Destinations that must be (re-)subscribed on every Open. */
    private final Set<String> wantedSubscriptions = ConcurrentHashMap.newKeySet();

    @Inject
    public RealtimeSession(
            @NonNull StompClient stompClient,
            @NonNull TokenStore tokenStore,
            @NonNull TokenRefresher tokenRefresher,
            @NonNull Moshi moshi,
            @NonNull ReconnectPolicy reconnectPolicy,
            @NonNull NetworkMonitor networkMonitor,
            @NonNull AppForegroundState foreground) {
        this(stompClient, tokenStore, tokenRefresher, moshi, reconnectPolicy,
                networkMonitor, foreground, Schedulers.single());
    }

    /** Deterministic scheduler seam for reconnect policy tests. */
    public RealtimeSession(
            @NonNull StompClient stompClient,
            @NonNull TokenStore tokenStore,
            @NonNull TokenRefresher tokenRefresher,
            @NonNull Moshi moshi,
            @NonNull ReconnectPolicy reconnectPolicy,
            @NonNull NetworkMonitor networkMonitor,
            @NonNull AppForegroundState foreground,
            @NonNull Scheduler reconnectScheduler) {
        this.stompClient = stompClient;
        this.tokenStore = tokenStore;
        this.tokenRefresher = tokenRefresher;
        this.reconnectPolicy = reconnectPolicy;
        this.networkMonitor = networkMonitor;
        this.foreground = foreground;
        this.reconnectScheduler = reconnectScheduler;

        envelopeAdapter = moshi.adapter(WsEnvelope.class);
        messageAdapter = moshi.adapter(MessageDto.class);
        sendAdapter = moshi.adapter(SendMessageRequest.class);
        typingAdapter = moshi.adapter(TypingSignalDto.class);
        readAdapter = moshi.adapter(ReadUpdateDto.class);
        sendErrorAdapter = moshi.adapter(SendErrorDto.class);
        reactionAdapter = moshi.adapter(ReactionUpdateDto.class);
        pinAdapter = moshi.adapter(PinUpdateDto.class);
        messageDeletedAdapter = moshi.adapter(MessageDeletedDto.class);
        presenceAdapter = moshi.adapter(PresenceEventDto.class);
        conversationUpdateAdapter = moshi.adapter(ConversationUpdateDto.class);
        Type mapType = Types.newParameterizedType(
                Map.class, String.class, Object.class);
        mapAdapter = moshi.adapter(mapType);

        wantedSubscriptions.add(USER_INBOX_DESTINATION);
        wantedSubscriptions.add(USER_ERRORS_DESTINATION);
        // Presence must be subscribed before the first peer event can land.
        wantedSubscriptions.add(USER_PRESENCE_DESTINATION);

        subscriptions.add(tokenStore.getHasValidRefresh()
                .distinctUntilChanged()
                .subscribe(present -> {
                    tokensPresent = present;
                    applySessionSignals();
                }));
        subscriptions.add(tokenRefresher.sessionAdmission()
                .distinctUntilChanged()
                .subscribe(admitted -> {
                    sessionAdmitted = admitted;
                    applySessionSignals();
                }));
        subscriptions.add(networkMonitor.getIsOnline()
                .filter(Boolean::booleanValue)
                .subscribe(ignored -> {
                    if (hasSession && sessionAdmitted && !isCurrentlyOpen()) {
                        ensureConnected();
                    }
                }));
        subscriptions.add(stompClient.getFrames().subscribe(this::dispatch));
    }

    @NonNull
    public Flowable<StompClient.State> getState() {
        return state.onBackpressureLatest();
    }

    @NonNull
    public Flowable<Boolean> getIsOpen() {
        return isOpen.onBackpressureLatest();
    }

    @NonNull
    public Flowable<ChatEvent> getEvents() {
        return events.onBackpressureBuffer(EVENTS_BUFFER, () -> { }, io.reactivex.rxjava3.core.BackpressureOverflowStrategy.DROP_OLDEST);
    }

    @NonNull
    public Flowable<Object> getResyncRequests() {
        return resyncRequests.onBackpressureBuffer(RESYNC_BUFFER, () -> { }, io.reactivex.rxjava3.core.BackpressureOverflowStrategy.DROP_OLDEST);
    }

    public boolean isCurrentlyOpen() { return Boolean.TRUE.equals(openState.getValue()); }

    private void applySessionSignals() {
        if (!tokensPresent) {
            tearDownLoggedOutSession();
            return;
        }
        if (!sessionAdmitted) {
            closeForAdmissionBoundary();
            return;
        }
        boolean wasAdmitted = hasSession;
        hasSession = true;
        if (!wasAdmitted) {
            failures = 0;
            parkedRounds = 0;
        }
        launchConnectionLoop();
    }

    private void tearDownLoggedOutSession() {
        hasSession = false;
        synchronized (this) {
            attemptActive = false;
        }
        authRecovery.set(Disposable.empty());
        wantedSubscriptions.clear();
        connection.set(Disposable.empty());
        parkedWake.set(Disposable.empty());
        stompClient.close();
        isOpen.onNext(false);
        state.onNext(new StompClient.State.Closed(NORMAL_CODE, "logged out"));
    }

    /** Closes the socket when admission closes while refresh bytes may still exist. */
    private void closeForAdmissionBoundary() {
        hasSession = tokensPresent;
        synchronized (this) {
            attemptActive = false;
        }
        authRecovery.set(Disposable.empty());
        connection.set(Disposable.empty());
        parkedWake.set(Disposable.empty());
        stompClient.close();
        isOpen.onNext(false);
    }

    /** Starts at most one socket attempt; SerialDisposable cancels the previous attempt or timer. */
    private void launchConnectionLoop() {
        synchronized (this) {
            if (!hasSession || !sessionAdmitted || !tokensPresent || isCurrentlyOpen() || attemptActive) {
                return;
            }
            long epoch = tokenRefresher.sessionEpoch();
            if (!tokenRefresher.isSessionCurrent(epoch)) {
                return;
            }
            attemptActive = true;
        }
        connectOnce();
    }

    /** Starts the loop if needed and wakes it after retry-budget exhaustion. */
    public void ensureConnected() {
        rearmRequests.onNext(new Object());
        launchConnectionLoop();
    }

    /** Re-arms a socket that died while the app was backgrounded. */
    public void onAppForeground() {
        if (hasSession && !isCurrentlyOpen()) ensureConnected();
    }

    public void dispose() {
        hasSession = false;
        tokensPresent = false;
        sessionAdmitted = false;
        synchronized (this) {
            attemptActive = false;
        }
        authRecovery.dispose();
        subscriptions.dispose();
        connection.dispose();
        parkedWake.dispose();
        stompClient.close();
        isOpen.onNext(false);
    }

    private void connectOnce() {
        final long sessionEpoch = tokenRefresher.sessionEpoch();
        if (!tokenRefresher.isSessionCurrent(sessionEpoch)) {
            synchronized (this) {
                attemptActive = false;
            }
            return;
        }
        final long attemptId = connectionAttemptSeq.incrementAndGet();
        activeConnectionAttemptId = attemptId;
        SerialDisposable attemptHolder = new SerialDisposable();
        connection.set(attemptHolder);
        authRecovery.set(Disposable.empty());
        authFailurePending = false;
        Flowable<StompClient.State> prepared = tokenStore.readTokens()
                .flatMapPublisher(tokens -> {
                    if (!isAttemptLive(sessionEpoch, attemptId)) {
                        return Flowable.empty();
                    }
                    String storedBearer = tokens.getAccessToken();
                    if (!tokens.isAccessExpired()) {
                        attemptAccessToken = storedBearer;
                        return stompClient.connect(storedBearer);
                    }
                    return tokenRefresher.refreshOutcomeIfNeeded(storedBearer)
                            .flatMapPublisher(outcome -> {
                                if (!isAttemptLive(sessionEpoch, attemptId)) {
                                    return Flowable.empty();
                                }
                                if (outcome instanceof TokenRefreshOutcome.FreshToken fresh) {
                                    String rotated = fresh.getAccessToken();
                                    attemptAccessToken = rotated;
                                    return stompClient.connect(rotated);
                                }
                                if (outcome instanceof TokenRefreshOutcome.Rejected) {
                                    tearDownLoggedOutSession();
                                    return Flowable.empty();
                                }
                                if (outcome instanceof TokenRefreshOutcome.RecoveryRequired) {
                                    return Flowable.just(new StompClient.State.Failed(
                                            StompClient.REASON_OFFLINE));
                                }
                                return Flowable.just(new StompClient.State.Failed(
                                        StompClient.REASON_OFFLINE));
                            });
                })
                .switchIfEmpty(Flowable.just(new StompClient.State.Failed(StompClient.REASON_NO_TOKEN)));
        AtomicBoolean attemptTerminal = new AtomicBoolean();
        Disposable attempt = prepared.subscribe(
                connectionState -> {
                    if (!isAttemptLive(sessionEpoch, attemptId)) {
                        return;
                    }
                    state.onNext(connectionState);
                    if (connectionState instanceof StompClient.State.Open) {
                        failures = 0;
                        parkedRounds = 0;
                        isOpen.onNext(true);
                        resubscribeWanted();
                        resyncRequests.onNext(new Object());
                    } else {
                        isOpen.onNext(false);
                        if (isAuthSuspectFailure(connectionState)) {
                            authFailurePending = true;
                        }
                        if ((connectionState instanceof StompClient.State.Failed
                                || connectionState instanceof StompClient.State.Closed)
                                && attemptTerminal.compareAndSet(false, true)) {
                            finishAttempt(sessionEpoch, attemptId);
                        }
                    }
                },
                ignored -> {
                    if (attemptTerminal.compareAndSet(false, true)) {
                        finishAttempt(sessionEpoch, attemptId);
                    }
                },
                () -> {
                    if (attemptTerminal.compareAndSet(false, true)) {
                        finishAttempt(sessionEpoch, attemptId);
                    }
                });
        attemptHolder.set(attempt);
    }

    private boolean isAttemptLive(long sessionEpoch, long attemptId) {
        return tokenRefresher.isSessionCurrent(sessionEpoch)
                && activeConnectionAttemptId == attemptId;
    }

    /** Only explicit STOMP authentication failures trigger token recovery. */
    private static boolean isAuthSuspectFailure(StompClient.State state) {
        if (!(state instanceof StompClient.State.Failed failed)) {
            return false;
        }
        return failed.isAuth();
    }

    private void finishAttempt(long sessionEpoch, long attemptId) {
        if (!isAttemptLive(sessionEpoch, attemptId)) {
            return;
        }
        synchronized (this) {
            attemptActive = false;
        }
        isOpen.onNext(false);
        if (!hasSession || !sessionAdmitted) {
            return;
        }
        if (authFailurePending) {
            authFailurePending = false;
            recoverFromAuthFailure(sessionEpoch, attemptId);
            return;
        }
        failures++;
        if (reconnectPolicy.shouldRetry(failures)) {
            Disposable timer = reconnectScheduler.scheduleDirect(
                    () -> {
                        if (isAttemptLive(sessionEpoch, attemptId)) {
                            launchConnectionLoop();
                        }
                    },
                    reconnectPolicy.delayMillis(failures),
                    TimeUnit.MILLISECONDS);
            connection.set(timer);
            return;
        }
        failures = 0;
        parkedRounds++;
        state.onNext(new StompClient.State.Failed(StompClient.REASON_OFFLINE));
        long delay = reconnectPolicy.parkedDelayMillis(parkedRounds);
        Flowable<Object> wakeSignals = Flowable.merge(
                networkMonitor.getIsOnline().filter(Boolean::booleanValue).map(value -> new Object()),
                rearmRequests);
        Flowable<Object> foregroundTimer = foreground.getIsForeground()
                .distinctUntilChanged()
                .switchMap(isForeground -> isForeground
                        ? Flowable.timer(delay, TimeUnit.MILLISECONDS, reconnectScheduler)
                                .map(value -> new Object())
                        : Flowable.never());
        Disposable wake = Flowable.merge(wakeSignals, foregroundTimer)
                .take(1)
                .subscribe(
                        ignored -> {
                            if (isAttemptLive(sessionEpoch, attemptId)) {
                                launchConnectionLoop();
                            }
                        },
                        ignored -> { });
        parkedWake.set(wake);
    }

    /** A transient refresh failure follows the same bounded backoff as socket failures. */
    private void recoverFromAuthFailure(long sessionEpoch, long attemptId) {
        if (!hasSession || !sessionAdmitted) {
            return;
        }
        final String failedBearer = attemptAccessToken;
        authRecovery.set(tokenRefresher.refreshOutcomeIfNeeded(failedBearer)
                .subscribe(outcome -> {
                    if (!isAttemptLive(sessionEpoch, attemptId)) {
                        return;
                    }
                    if (!hasSession || !sessionAdmitted) {
                        return;
                    }
                    if (outcome instanceof TokenRefreshOutcome.Rejected) {
                        tearDownLoggedOutSession();
                        return;
                    }
                    if (outcome instanceof TokenRefreshOutcome.RecoveryRequired) {
                        failures = 0;
                        parkedRounds++;
                        state.onNext(new StompClient.State.Failed(StompClient.REASON_OFFLINE));
                        return;
                    }
                    finishAttempt(sessionEpoch, attemptId);
                }, ignored -> {
                    if (isAttemptLive(sessionEpoch, attemptId)) {
                        finishAttempt(sessionEpoch, attemptId);
                    }
                }));
    }

    private void resubscribeWanted() {
        for (String destination : wantedSubscriptions) {
            stompClient.subscribe(destination);
        }
    }

    /** Subscribes to {@code /topic/conversations.{id}} and remembers it across reconnects. */
    public void watchConversation(@NonNull String conversationId) {
        String destination = topicDestination(conversationId);
        wantedSubscriptions.add(destination);
        if (isCurrentlyOpen()) {
            stompClient.subscribe(destination);
        }
    }

    public void unwatchConversation(@NonNull String conversationId) {
        String destination = topicDestination(conversationId);
        if (wantedSubscriptions.remove(destination)) {
            stompClient.unsubscribe(destination);
        }
    }

    /** STOMP SEND to {@code /app/conversations/{id}/messages}. */
    public boolean sendMessage(
            @NonNull String conversationId,
            @NonNull SendMessageRequest request) {
        return stompClient.send(
                APP_PREFIX + "/conversations/" + conversationId + "/messages",
                sendAdapter.toJson(request));
    }

    /** Typing ping with an empty body; client throttling happens upstream. */
    public boolean sendTyping(@NonNull String conversationId) {
        return stompClient.send(
                APP_PREFIX + "/conversations/" + conversationId + "/typing",
                "{}");
    }

    /** Delivery receipt ack to /app/chat.delivery.ack. */
    public boolean sendDeliveryAck(
            @NonNull String conversationId,
            long upToSeq) {
        String payload = "{\"conversationId\":\"" + conversationId + "\",\"upToSeq\":" + upToSeq + "}";
        return stompClient.send(APP_PREFIX + "/chat.delivery.ack", payload);
    }

    /** Read receipt ack to /app/chat.read.ack. */
    public boolean sendReadAck(
            @NonNull String conversationId,
            long upToSeq) {
        String payload = "{\"conversationId\":\"" + conversationId + "\",\"upToSeq\":" + upToSeq + "}";
        return stompClient.send(APP_PREFIX + "/chat.read.ack", payload);
    }

    private void dispatch(@NonNull StompFrame frame) {
        if (!StompCommand.MESSAGE.equals(frame.getCommand())) {
            return;
        }

        // Error-queue frames are identified by subscription id because the
        // broker rewrites user queues to /queue/errors-user<sessionId>.
        if (Objects.equals(
                stompClient.subscriptionIdFor(USER_ERRORS_DESTINATION),
                frame.getHeaders().get("subscription"))) {
            SendErrorDto error = parse(sendErrorAdapter, frame.getBody());
            events.onNext(
                    new ChatEvent.SendRejected(
                            error == null ? null : error.getCode(),
                            error == null ? null : error.getClientMessageId()));
            return;
        }

        WsEnvelope envelope = parse(envelopeAdapter, frame.getBody());
        if (envelope == null) {
            return;
        }
        events.onNext(toChatEvent(envelope));
    }

    @NonNull
    private ChatEvent toChatEvent(@NonNull WsEnvelope envelope) {
        String eventType = envelope.getEventType();
        String conversationId = envelope.getConversationId();
        switch (eventType == null ? "" : eventType) {
            case EVENT_MESSAGE_CREATED: {
                MessageDto message = parse(messageAdapter, payloadJson(envelope));
                if (message == null) {
                    return new ChatEvent.Unknown(eventType, conversationId);
                }
                String resolvedConversationId = conversationId != null
                        ? conversationId
                        : message.getConversationId();
                return new ChatEvent.MessageCreated(
                        message, resolvedConversationId, envelope.getServerTs());
            }
            case EVENT_USER_TYPING: {
                TypingSignalDto signal = parse(typingAdapter, payloadJson(envelope));
                return new ChatEvent.UserTyping(
                        conversationId,
                        signal == null ? null : signal.getUserId(),
                        signal == null ? null : signal.getDisplayName());
            }
            case EVENT_MESSAGE_UPDATED: {
                MessageDto message = parse(messageAdapter, payloadJson(envelope));
                if (message == null) {
                    return new ChatEvent.Unknown(eventType, conversationId);
                }
                String resolvedConversationId = conversationId != null
                        ? conversationId
                        : message.getConversationId();
                return new ChatEvent.MessageUpdated(
                        message, resolvedConversationId, envelope.getServerTs());
            }
            case EVENT_MESSAGE_DELETED: {
                MessageDeletedDto update = parse(messageDeletedAdapter, payloadJson(envelope));
                return new ChatEvent.MessageDeleted(
                        conversationId,
                        update == null ? null : update.getMessageId(),
                        update == null ? null : update.getDeletedAt());
            }
            case EVENT_USER_ONLINE:
            case EVENT_USER_OFFLINE: {
                PresenceEventDto presence = parse(presenceAdapter, payloadJson(envelope));
                Boolean payloadOnline = presence == null ? null : presence.getOnline();
                boolean online = payloadOnline != null
                        ? payloadOnline
                        : EVENT_USER_ONLINE.equals(eventType);
                return new ChatEvent.Presence(
                        presence == null ? null : presence.getUserId(),
                        online,
                        presence == null ? null : presence.getLastSeenAt(),
                        envelope.getServerTs());
            }
            case EVENT_MESSAGE_READ: {
                ReadUpdateDto update = parse(readAdapter, payloadJson(envelope));
                return new ChatEvent.MessageRead(
                        conversationId,
                        update == null ? null : update.getResolvedUserId(),
                        update == null ? null : update.getResolvedSeq());
            }
            case EVENT_MESSAGE_DELIVERED: {
                ReadUpdateDto update = parse(readAdapter, payloadJson(envelope));
                return new ChatEvent.MessageDelivered(
                        conversationId,
                        update == null ? null : update.getResolvedUserId(),
                        update == null ? null : update.getResolvedSeq());
            }
            case EVENT_CONVERSATION_UPDATED:
                ConversationUpdateDto conversationUpdate =
                        parse(conversationUpdateAdapter, payloadJson(envelope));
                return new ChatEvent.ConversationUpdated(
                        conversationId,
                        conversationUpdate == null ? null : conversationUpdate.getChange(),
                        conversationUpdate == null
                                ? Collections.emptyList()
                                : conversationUpdate.getMemberIds());
            case EVENT_REACTION_UPDATED: {
                ReactionUpdateDto update = parse(reactionAdapter, payloadJson(envelope));
                return new ChatEvent.ReactionUpdated(
                        conversationId,
                        update == null ? null : update.getMessageId(),
                        update == null ? Collections.emptyList() : update.getReactions());
            }
            case EVENT_PIN_UPDATED: {
                PinUpdateDto update = parse(pinAdapter, payloadJson(envelope));
                return new ChatEvent.PinUpdated(
                        conversationId,
                        update == null ? null : update.getMessageId(),
                        update == null ? null : update.getPinned());
            }
            default:
                return new ChatEvent.Unknown(eventType, conversationId);
        }
    }

    /** Re-encodes map payloads so typed adapters receive JSON numbers, not Double values. */
    @Nullable
    private String payloadJson(@NonNull WsEnvelope envelope) {
        Map<String, Object> payload = envelope.getPayload();
        if (payload == null) {
            return null;
        }
        try {
            return mapAdapter.toJson(payload);
        } catch (RuntimeException malformedPayload) {
            return null;
        }
    }

    @Nullable
    private static <T> T parse(@NonNull JsonAdapter<T> adapter, @Nullable String json) {
        if (json == null) {
            return null;
        }
        try {
            return adapter.fromJson(json);
        } catch (IOException | RuntimeException malformedJson) {
            return null;
        }
    }

    @NonNull
    public static String topicDestination(@NonNull String conversationId) {
        return TOPIC_PREFIX + conversationId;
    }
}
