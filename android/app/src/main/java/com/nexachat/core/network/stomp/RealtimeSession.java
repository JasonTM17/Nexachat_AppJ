package com.nexachat.core.network.stomp;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.nexachat.core.network.AppForegroundState;
import com.nexachat.core.network.NetworkMonitor;
import com.nexachat.core.network.TokenRefresher;
import com.nexachat.core.network.dto.MessageDto;
import com.nexachat.core.network.dto.PinUpdateDto;
import com.nexachat.core.network.dto.ReactionUpdateDto;
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
    private volatile boolean hasSession;
    private boolean attemptActive;
    private int failures;
    private int parkedRounds;

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
        Type mapType = Types.newParameterizedType(
                Map.class, String.class, Object.class);
        mapAdapter = moshi.adapter(mapType);

        wantedSubscriptions.add(USER_INBOX_DESTINATION);
        wantedSubscriptions.add(USER_ERRORS_DESTINATION);
        // Presence must be subscribed before the first peer event can land.
        wantedSubscriptions.add(USER_PRESENCE_DESTINATION);

        subscriptions.add(tokenStore.getHasValidRefresh().subscribe(this::onTokenPresence));
        subscriptions.add(networkMonitor.getIsOnline()
                .filter(Boolean::booleanValue)
                .subscribe(ignored -> { if (hasSession && !isCurrentlyOpen()) ensureConnected(); }));
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

    private void onTokenPresence(boolean hasToken) {
        hasSession = hasToken;
        if (hasToken) {
            launchConnectionLoop();
            return;
        }
        synchronized (this) { attemptActive = false; }
        connection.set(Disposable.empty());
        parkedWake.set(Disposable.empty());
        stompClient.close();
        isOpen.onNext(false);
        state.onNext(new StompClient.State.Closed(NORMAL_CODE, "logged out"));
    }

    /** Starts at most one socket attempt; SerialDisposable cancels the previous attempt or timer. */
    private void launchConnectionLoop() {
        synchronized (this) {
            if (!hasSession || isCurrentlyOpen() || attemptActive) return;
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
        synchronized (this) { attemptActive = false; }
        subscriptions.dispose();
        connection.dispose();
        parkedWake.dispose();
        stompClient.close();
        isOpen.onNext(false);
    }

    private void connectOnce() {
        SerialDisposable attemptHolder = new SerialDisposable();
        connection.set(attemptHolder);
        Flowable<StompClient.State> prepared = tokenStore.readTokens()
                .flatMapPublisher(tokens -> {
                    Single<Boolean> valid = tokens.isAccessExpired()
                            ? tokenRefresher.refreshIfNeeded(tokens.getAccessToken()).map(Optional::isPresent)
                            : Single.just(true);
                    return valid.flatMapPublisher(canConnect -> canConnect
                            ? stompClient.connect()
                            : Flowable.just(new StompClient.State.Failed(StompClient.REASON_NO_TOKEN)));
                })
                .switchIfEmpty(Flowable.just(new StompClient.State.Failed(StompClient.REASON_NO_TOKEN)));
        Disposable attempt = prepared.subscribe(
                connectionState -> {
                    state.onNext(connectionState);
                    if (connectionState instanceof StompClient.State.Open) {
                        failures = 0;
                        parkedRounds = 0;
                        isOpen.onNext(true);
                        resubscribeWanted();
                        resyncRequests.onNext(new Object());
                    } else {
                        isOpen.onNext(false);
                        if (connectionState instanceof StompClient.State.Failed
                                && ((StompClient.State.Failed) connectionState).isAuth()) {
                            tokenRefresher.refresh().subscribe(ignored -> { }, ignored -> { });
                        }
                    }
                }, ignored -> finishAttempt(), this::finishAttempt);
        attemptHolder.set(attempt);
    }

    private void finishAttempt() {
        synchronized (this) { attemptActive = false; }
        isOpen.onNext(false);
        if (!hasSession) return;
        failures++;
        if (reconnectPolicy.shouldRetry(failures)) {
            Disposable timer = reconnectScheduler.scheduleDirect(
                    this::launchConnectionLoop, reconnectPolicy.delayMillis(failures), TimeUnit.MILLISECONDS);
            connection.set(timer);
            return;
        }
        failures = 0;
        parkedRounds++;
        state.onNext(new StompClient.State.Failed(StompClient.REASON_OFFLINE));
        long delay = Math.min(PARKED_RETRY_BASE_MS << Math.min(parkedRounds - 1, 4), PARKED_RETRY_MAX_MS);
        Flowable<Object> wakeSignals = Flowable.merge(
                networkMonitor.getIsOnline().filter(Boolean::booleanValue).map(value -> new Object()),
                rearmRequests);
        Flowable<Object> foregroundTimer = foreground.getIsForeground()
                .distinctUntilChanged()
                .switchMap(isForeground -> isForeground
                        ? Flowable.timer(delay, TimeUnit.MILLISECONDS, reconnectScheduler).map(value -> new Object())
                        : Flowable.never());
        Disposable wake = Flowable.merge(wakeSignals, foregroundTimer)
                .take(1)
                .subscribe(ignored -> launchConnectionLoop(), ignored -> { });
        parkedWake.set(wake);
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
                return new ChatEvent.ConversationUpdated(conversationId);
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
