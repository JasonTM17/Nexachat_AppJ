package com.nexachat.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.nexachat.core.realtime.ephemeral.EphemeralStateRouter;
import com.nexachat.core.realtime.ephemeral.PresenceUpdate;
import com.nexachat.core.network.SessionEvents;
import com.nexachat.core.network.stomp.ChatEvent;
import com.nexachat.core.network.stomp.RealtimeSession;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.processors.BehaviorProcessor;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Peer presence as the feature layer sees it: online from the live
 * {@code USER_ONLINE}/{@code USER_OFFLINE} push, lastSeenAtEpochMs persisted
 * server-side on the last disconnect. Both stay false/null when the peer hides
 * their presence — a hidden peer is indistinguishable from a never-seen one,
 * by design (the server sends neither events nor a seed).
 *
 * <p>Presence pushes arrive from realtime's event stream. The processor emits
 * immutable snapshots; no network or database work runs in this repository.
 */
@Singleton
public final class PresenceRepository implements AutoCloseable {

    private final BehaviorProcessor<Map<String, PeerPresence>> mutablePresences =
            BehaviorProcessor.createDefault(Collections.emptyMap());
    private final Flowable<Map<String, PeerPresence>> presences =
            mutablePresences.hide().distinctUntilChanged();
    private final CompositeDisposable subscriptions = new CompositeDisposable();

    /**
     * Peers whose state came from a live push. An inbox snapshot is taken
     * before it travels back, so it can be older than an event already
     * applied; once a peer has a live value, seeds must not rewrite it.
     */
    private final Set<String> livePeers = ConcurrentHashMap.newKeySet();
    private final Object observerLock = new Object();
    private final Map<String, String> presenceConversationByPeer = new LinkedHashMap<>();
    @Nullable private final EphemeralStateRouter router;

    /** Latest server event time per peer, used to ignore delayed older frames. */
    private final ConcurrentHashMap<String, Instant> latestEventTimes =
            new ConcurrentHashMap<>();

    @Inject
    public PresenceRepository(
            @NonNull EphemeralStateRouter router,
            @NonNull SessionEvents sessionEvents) {
        this.router = router;
        subscriptions.add(router.presenceEvents().subscribe(this::onPresenceUpdate, ignored -> { }));
        subscriptions.add(router.selectionChanges()
                .subscribe(ignored -> clearPresenceState(), ignored -> { }));
        Disposable sessionSignals = sessionEvents.getEvents()
                .subscribe(this::onSessionSignal, ignored -> { });
        subscriptions.add(sessionSignals);
        subscriptions.add(sessionEvents.getLifecycleEvents()
                .filter(event -> event instanceof SessionEvents.Event.SessionClosing)
                .subscribe(this::onSessionSignal, ignored -> { }));
    }

    /** Compatibility path for focused repository tests that exercise raw STOMP ordering. */
    public PresenceRepository(
            @NonNull RealtimeSession session,
            @NonNull SessionEvents sessionEvents) {
        this.router = null;
        Disposable realtime = session.getEvents().subscribe(this::onSessionEvent, ignored -> { });
        Disposable sessionSignals = sessionEvents.getEvents()
                .subscribe(this::onSessionSignal, ignored -> { });
        subscriptions.add(realtime);
        subscriptions.add(sessionSignals);
        subscriptions.add(sessionEvents.getLifecycleEvents()
                .filter(event -> event instanceof SessionEvents.Event.SessionClosing)
                .subscribe(this::onSessionSignal, ignored -> { }));
    }

    /** User id → presence for every peer this session has heard about. */
    @NonNull
    public Flowable<Map<String, PeerPresence>> getPresences() {
        return presences;
    }

    /**
     * Inbox refresh seeds. A seed may be older than a live event that landed
     * while the REST call was in flight, so it only fills peers without a
     * live value; a live push always wins.
     */
    public void seed(@NonNull Map<String, PeerPresence> seeds) {
        if (router != null && router.isFirebaseSelected()) return;
        if (seeds.isEmpty()) {
            return;
        }
        updatePresences(current -> {
            Map<String, PeerPresence> next = new LinkedHashMap<>(current);
            for (Map.Entry<String, PeerPresence> entry : seeds.entrySet()) {
                String userId = entry.getKey();
                PeerPresence presence = entry.getValue();
                if (userId == null || userId.isBlank() || livePeers.contains(userId)) {
                    continue;
                }
                PeerPresence prior = next.get(userId);
                PeerPresence merged = prior != null && prior.getOnline() && !presence.getOnline()
                        ? prior
                        : presence;
                if (!Objects.equals(merged, prior)) {
                    next.put(userId, merged);
                }
            }
            return immutableCopy(next);
        });
    }

    /** Logout / account switch: nothing may carry over. */
    public synchronized void clear() {
        clearPresenceState();
        synchronized (observerLock) {
            presenceConversationByPeer.clear();
            publishPresenceObserversLocked();
        }
    }

    private synchronized void clearPresenceState() {
        livePeers.clear();
        latestEventTimes.clear();
        mutablePresences.onNext(Collections.emptyMap());
    }

    /** Drops one peer (hidden presence, or no longer a DIRECT contact). */
    public void clear(@NonNull String userId) {
        synchronized (observerLock) {
            presenceConversationByPeer.remove(userId);
            publishPresenceObserversLocked();
        }
        livePeers.remove(userId);
        latestEventTimes.remove(userId);
        updatePresences(current -> {
            if (!current.containsKey(userId)) {
                return current;
            }
            Map<String, PeerPresence> next = new LinkedHashMap<>(current);
            next.remove(userId);
            return immutableCopy(next);
        });
    }

    private void onSessionEvent(ChatEvent event) {
        if (!(event instanceof ChatEvent.Presence presence)) {
            return;
        }
        String userId = presence.getUserId();
        if (userId == null || userId.isBlank()) {
            return;
        }
        onPresenceUpdate(new PresenceUpdate(userId, new PeerPresence(
                presence.getOnline(), TimeAndJwt.isoToEpochMsOrNull(presence.getLastSeenAt())),
                TimeAndJwt.isoToEpochMsOrNull(presence.getServerTs())));
    }

    private void onPresenceUpdate(PresenceUpdate update) {
        String userId = update.getUserId();
        Long eventAt = update.getEventAtEpochMs();
        if (eventAt != null && !recordIfCurrent(userId, Instant.ofEpochMilli(eventAt))) return;
        PeerPresence next = update.getPresence();
        livePeers.add(userId);
        updatePresences(current -> {
            Map<String, PeerPresence> nextMap = new LinkedHashMap<>(current);
            nextMap.put(userId, next);
            return immutableCopy(nextMap);
        });
    }

    private void onSessionSignal(SessionEvents.Event event) {
        if (event instanceof SessionEvents.Event.SessionClosing
                || event instanceof SessionEvents.Event.LoggedOut
                || event instanceof SessionEvents.Event.ReauthenticationRequired) {
            clear();
        }
    }

    /** Replaces the authenticated inbox's direct, visible peer scope. */
    public void setPresenceObservers(@NonNull Map<String, String> conversationByPeer) {
        if (router == null) return;
        synchronized (observerLock) {
            presenceConversationByPeer.clear();
            presenceConversationByPeer.putAll(conversationByPeer);
            publishPresenceObserversLocked();
        }
    }

    private void publishPresenceObserversLocked() {
        if (router != null) router.setPresenceObservers(Map.copyOf(presenceConversationByPeer));
    }

    /** Records a timestamp unless a later presence event was already applied. */
    private boolean recordIfCurrent(String userId, Instant eventTime) {
        while (true) {
            Instant previous = latestEventTimes.get(userId);
            if (previous != null && eventTime.isBefore(previous)) {
                return false;
            }
            if (previous == null) {
                if (latestEventTimes.putIfAbsent(userId, eventTime) == null) {
                    return true;
                }
            } else if (latestEventTimes.replace(userId, previous, eventTime)) {
                return true;
            }
        }
    }

    @Nullable
    private Instant parseEventTime(@Nullable String serverTs) {
        return PresenceTime.parseEventTime(serverTs);
    }

    private synchronized void updatePresences(
            UnaryOperator<Map<String, PeerPresence>> update) {
        Map<String, PeerPresence> current = mutablePresences.getValue();
        Map<String, PeerPresence> next = update.apply(current);
        if (!Objects.equals(current, next)) {
            mutablePresences.onNext(next);
        }
    }

    private static Map<String, PeerPresence> immutableCopy(
            Map<String, PeerPresence> presences) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(presences));
    }

    /** Releases the app-lifetime realtime subscriptions; also used by scoped tests. */
    @Override
    public void close() {
        subscriptions.clear();
    }
}
