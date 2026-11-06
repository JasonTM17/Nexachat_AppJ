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
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CancellationException;
import javax.inject.Inject;
import javax.inject.Singleton;
import kotlin.Unit;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.Job;
import kotlinx.coroutines.channels.BufferOverflow;
import kotlinx.coroutines.flow.MutableSharedFlow;
import kotlinx.coroutines.flow.MutableStateFlow;
import kotlinx.coroutines.flow.SharedFlow;
import kotlinx.coroutines.flow.SharedFlowKt;
import kotlinx.coroutines.flow.StateFlow;
import kotlinx.coroutines.flow.StateFlowKt;

/**
 * Owns the STOMP session lifecycle and maps inbound frames to typed events.
 *
 * <p>The connection loop is caller-owned per the {@link StompClient} contract.
 * It shadows {@link TokenStore}'s valid-session flow, reconnects with
 * {@link ReconnectPolicy}, and re-arms after a user action, a validated
 * network regain, app foregrounding, or a bounded foreground retry timer.
 * Kotlin Flow collection and suspend calls are isolated in
 * {@link RealtimeSessionFlow}; the session contract and event handling are
 * Java.
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
    private final CoroutineScope scope;

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

    private final MutableStateFlow<StompClient.State> mutableState =
            StateFlowKt.MutableStateFlow(
                    new StompClient.State.Closed(NORMAL_CODE, "never connected"));
    private final MutableStateFlow<Boolean> mutableIsOpen = StateFlowKt.MutableStateFlow(false);
    private final StateFlow<StompClient.State> stateView =
            RealtimeSessionFlow.stateView(mutableState);
    private final StateFlow<Boolean> isOpenView = RealtimeSessionFlow.stateView(mutableIsOpen);
    private final MutableSharedFlow<ChatEvent> mutableEvents =
            SharedFlowKt.MutableSharedFlow(
                    0, EVENTS_BUFFER, BufferOverflow.DROP_OLDEST);
    private final MutableSharedFlow<Unit> mutableResyncRequests =
            SharedFlowKt.MutableSharedFlow(
                    0, RESYNC_BUFFER, BufferOverflow.DROP_OLDEST);
    private final SharedFlow<ChatEvent> eventsView =
            RealtimeSessionFlow.sharedView(mutableEvents);
    private final SharedFlow<Unit> resyncRequestsView =
            RealtimeSessionFlow.sharedView(mutableResyncRequests);
    private final MutableSharedFlow<Unit> rearmRequests =
            SharedFlowKt.MutableSharedFlow(
                    0, 1, BufferOverflow.DROP_OLDEST);

    /** Destinations that must be (re-)subscribed on every Open. */
    private final Set<String> wantedSubscriptions = ConcurrentHashMap.newKeySet();
    private final Object connectionJobLock = new Object();

    @Nullable
    private volatile Job connectionJob;

    private final RealtimeSessionFlow.ConnectionCallbacks connectionCallbacks =
            new RealtimeSessionFlow.ConnectionCallbacks() {
                @Override
                public void onConnectionState(@NonNull StompClient.State state) {
                    mutableState.setValue(state);
                    if (state instanceof StompClient.State.Open) {
                        mutableIsOpen.setValue(true);
                        resubscribeWanted();
                        mutableResyncRequests.tryEmit(Unit.INSTANCE);
                    } else {
                        mutableIsOpen.setValue(false);
                    }
                }

                @Override
                public void onConnectionEnded() {
                    mutableIsOpen.setValue(false);
                }

                @Override
                public void onOffline() {
                    mutableState.setValue(new StompClient.State.Failed(StompClient.REASON_OFFLINE));
                    mutableIsOpen.setValue(false);
                }
            };

    @Inject
    public RealtimeSession(
            @NonNull StompClient stompClient,
            @NonNull TokenStore tokenStore,
            @NonNull TokenRefresher tokenRefresher,
            @NonNull Moshi moshi,
            @NonNull ReconnectPolicy reconnectPolicy,
            @NonNull NetworkMonitor networkMonitor,
            @NonNull AppForegroundState foreground,
            @NonNull CoroutineScope scope) {
        this.stompClient = stompClient;
        this.tokenStore = tokenStore;
        this.tokenRefresher = tokenRefresher;
        this.reconnectPolicy = reconnectPolicy;
        this.networkMonitor = networkMonitor;
        this.foreground = foreground;
        this.scope = scope;

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

        RealtimeSessionFlow.observeTokens(scope, tokenStore, this::onTokenPresence);
        RealtimeSessionFlow.observeNetworkRegain(
                scope, tokenStore, networkMonitor, this::isSocketOpen, this::ensureConnected);
        RealtimeSessionFlow.collectFrames(scope, stompClient.getFrames(), this::dispatch);
    }

    @NonNull
    public StateFlow<StompClient.State> getState() {
        return stateView;
    }

    @NonNull
    public StateFlow<Boolean> getIsOpen() {
        return isOpenView;
    }

    @NonNull
    public SharedFlow<ChatEvent> getEvents() {
        return eventsView;
    }

    @NonNull
    public SharedFlow<Unit> getResyncRequests() {
        return resyncRequestsView;
    }

    private void onTokenPresence(boolean hasToken) {
        if (hasToken) {
            launchConnectionLoop();
            return;
        }

        // Logout: stop the loop and hang up so nothing lingers. The same lock
        // as launch prevents a regain re-arm from slipping between cancel/null.
        synchronized (connectionJobLock) {
            if (connectionJob != null) {
                connectionJob.cancel((CancellationException) null);
                connectionJob = null;
            }
        }
        stompClient.close();
        mutableIsOpen.setValue(false);
    }

    /** Atomically starts at most one connection loop. */
    private void launchConnectionLoop() {
        synchronized (connectionJobLock) {
            if (connectionJob != null && connectionJob.isActive()) {
                return;
            }
            connectionJob = RealtimeSessionFlow.launchConnectionLoop(
                    scope,
                    tokenStore,
                    tokenRefresher,
                    stompClient,
                    reconnectPolicy,
                    networkMonitor,
                    foreground,
                    rearmRequests,
                    connectionCallbacks);
        }
    }

    /** Starts the loop if needed and wakes it after retry-budget exhaustion. */
    public void ensureConnected() {
        launchConnectionLoop();
        rearmRequests.tryEmit(Unit.INSTANCE);
    }

    /** Re-arms a socket that died while the app was backgrounded. */
    public void onAppForeground() {
        RealtimeSessionFlow.rearmOnForeground(
                scope, tokenStore, this::isSocketOpen, this::ensureConnected);
    }

    private boolean isSocketOpen() {
        return mutableIsOpen.getValue();
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
        if (mutableIsOpen.getValue()) {
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
            mutableEvents.tryEmit(
                    new ChatEvent.SendRejected(
                            error == null ? null : error.getCode(),
                            error == null ? null : error.getClientMessageId()));
            return;
        }

        WsEnvelope envelope = parse(envelopeAdapter, frame.getBody());
        if (envelope == null) {
            return;
        }
        mutableEvents.tryEmit(toChatEvent(envelope));
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
