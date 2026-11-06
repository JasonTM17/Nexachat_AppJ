package com.nexachat.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.nexachat.core.network.SessionEvents;
import com.nexachat.core.network.stomp.ChatEvent;
import com.nexachat.core.network.stomp.RealtimeSession;
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
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.flow.MutableStateFlow;
import kotlinx.coroutines.flow.StateFlow;
import kotlinx.coroutines.flow.StateFlowKt;

/**
 * Peer presence as the feature layer sees it: online from the live
 * {@code USER_ONLINE}/{@code USER_OFFLINE} push, lastSeenAtEpochMs persisted
 * server-side on the last disconnect. Both stay false/null when the peer hides
 * their presence — a hidden peer is indistinguishable from a never-seen one,
 * by design (the server sends neither events nor a seed).
 */
@Singleton
public final class PresenceRepository {

    private final MutableStateFlow<Map<String, PeerPresence>> mutablePresences =
            StateFlowKt.MutableStateFlow(Collections.emptyMap());
    private final StateFlow<Map<String, PeerPresence>> presences =
            PresenceRepositoryFlow.stateView(mutablePresences);

    /**
     * Peers whose state came from a live push. An inbox snapshot is taken
     * before it travels back, so it can be older than an event already
     * applied; once a peer has a live value, seeds must not rewrite it.
     */
    private final Set<String> livePeers = ConcurrentHashMap.newKeySet();

    /** Latest server event time per peer, used to ignore delayed older frames. */
    private final ConcurrentHashMap<String, Instant> latestEventTimes =
            new ConcurrentHashMap<>();

    @Inject
    public PresenceRepository(
            @NonNull RealtimeSession session,
            @NonNull SessionEvents sessionEvents,
            @NonNull CoroutineScope scope) {
        PresenceRepositoryFlow.collect(scope, session.getEvents(), this::onSessionEvent);
        PresenceRepositoryFlow.collect(scope, sessionEvents.getEvents(), this::onSessionSignal);
    }

    /** User id → presence for every peer this session has heard about. */
    @NonNull
    public StateFlow<Map<String, PeerPresence>> getPresences() {
        return presences;
    }

    /**
     * Inbox refresh seeds. A seed may be older than a live event that landed
     * while the REST call was in flight, so it only fills peers without a
     * live value; a live push always wins.
     */
    public void seed(@NonNull Map<String, PeerPresence> seeds) {
        if (seeds.isEmpty()) {
            return;
        }
        updatePresences(current -> {
            Map<String, PeerPresence> next = new LinkedHashMap<>(current);
            boolean changed = false;
            for (Map.Entry<String, PeerPresence> entry : seeds.entrySet()) {
                String userId = entry.getKey();
                PeerPresence presence = entry.getValue();
                if (userId == null || PresenceRepositoryFlow.isBlank(userId)
                        || livePeers.contains(userId)) {
                    continue;
                }
                PeerPresence prior = next.get(userId);
                PeerPresence merged = prior != null && prior.getOnline() && !presence.getOnline()
                        ? prior
                        : presence;
                if (!Objects.equals(merged, prior)) {
                    next.put(userId, merged);
                    changed = true;
                }
            }
            return changed ? immutableCopy(next) : current;
        });
    }

    /** Logout / account switch: nothing may carry over. */
    public void clear() {
        livePeers.clear();
        latestEventTimes.clear();
        mutablePresences.setValue(Collections.emptyMap());
    }

    /** Drops one peer (hidden presence, or no longer a DIRECT contact). */
    public void clear(@NonNull String userId) {
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
        if (userId == null || PresenceRepositoryFlow.isBlank(userId)) {
            return;
        }
        Instant eventTime = parseEventTime(presence.getServerTs());
        if (eventTime != null && !recordIfCurrent(userId, eventTime)) {
            return;
        }
        PeerPresence next = new PeerPresence(
                presence.getOnline(),
                PresenceRepositoryFlow.isoToEpochMsOrNullFromMessageRepository(
                        presence.getLastSeenAt()));
        livePeers.add(userId);
        updatePresences(current -> {
            Map<String, PeerPresence> nextMap = new LinkedHashMap<>(current);
            nextMap.put(userId, next);
            return immutableCopy(nextMap);
        });
    }

    private void onSessionSignal(SessionEvents.Event event) {
        if (event instanceof SessionEvents.Event.LoggedOut) {
            clear();
        }
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

    private void updatePresences(UnaryOperator<Map<String, PeerPresence>> update) {
        while (true) {
            Map<String, PeerPresence> current = mutablePresences.getValue();
            Map<String, PeerPresence> next = update.apply(current);
            if (mutablePresences.compareAndSet(current, next)) {
                return;
            }
        }
    }

    private static Map<String, PeerPresence> immutableCopy(
            Map<String, PeerPresence> presences) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(presences));
    }
}
