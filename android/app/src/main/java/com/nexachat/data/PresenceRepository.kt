package com.nexachat.data

import com.nexachat.core.network.SessionEvents
import com.nexachat.core.network.stomp.ChatEvent
import com.nexachat.core.network.stomp.RealtimeSession
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Peer presence as the feature layer sees it: [online] from the live
 * `USER_ONLINE`/`USER_OFFLINE` push, [lastSeenAtEpochMs] persisted server-side
 * on the last disconnect. Both stay `false`/`null` when the peer hides their
 * presence — a hidden peer is indistinguishable from a never-seen one, by
 * design (the server sends neither events nor a seed).
 */
data class PeerPresence(
    val online: Boolean,
    val lastSeenAtEpochMs: Long?,
)

/**
 * In-memory presence fan-in (F3). Seeded from DIRECT inbox rows
 * (`peerPresence`) and kept live by the session's presence queue; keyed by
 * peer user id so the inbox and the open chat share one source.
 *
 * The map is cleared on logout — presence is per-session data, and a stale
 * "online" dot from the previous account must never leak into the next one.
 */
@Singleton
class PresenceRepository @Inject constructor(
    private val session: RealtimeSession,
    private val sessionEvents: SessionEvents,
    private val scope: CoroutineScope,
) {

    private val _presences = MutableStateFlow<Map<String, PeerPresence>>(emptyMap())

    /**
     * Peers whose state came from a live push. An inbox snapshot is taken
     * before it travels back, so it can be older than an event already
     * applied; once a peer has a live value, seeds must not rewrite it.
     */
    private val livePeers = ConcurrentHashMap.newKeySet<String>()

    /** Latest server event time per peer, used to ignore delayed older frames. */
    private val latestEventTimes = ConcurrentHashMap<String, Instant>()

    /** User id → presence for every peer this session has heard about. */
    val presences: StateFlow<Map<String, PeerPresence>> = _presences.asStateFlow()

    init {
        scope.launch {
            session.events.collect { event ->
                val presence = event as? ChatEvent.Presence ?: return@collect
                val userId = presence.userId?.takeIf { it.isNotBlank() } ?: return@collect
                val eventTime = parseEventTime(presence.serverTs)
                if (eventTime != null && !recordIfCurrent(userId, eventTime)) return@collect
                val next = PeerPresence(
                    online = presence.online,
                    lastSeenAtEpochMs = isoToEpochMsOrNull(presence.lastSeenAt),
                )
                livePeers.add(userId)
                _presences.update { it + (userId to next) }
            }
        }
        scope.launch {
            sessionEvents.events.collect { event ->
                if (event is SessionEvents.Event.LoggedOut) clear()
            }
        }
    }

    /**
     * Inbox refresh seeds. A seed may be older than a live event that landed
     * while the REST call was in flight, so it only fills peers without a
     * live value; a live push always wins.
     */
    fun seed(seeds: Map<String, PeerPresence>) {
        if (seeds.isEmpty()) return
        _presences.update { current ->
            var changed = false
            val next = current.toMutableMap()
            for ((userId, presence) in seeds) {
                if (userId.isBlank() || livePeers.contains(userId)) continue
                val prior = next[userId]
                val merged = if (prior?.online == true && !presence.online) prior else presence
                if (merged != prior) {
                    next[userId] = merged
                    changed = true
                }
            }
            if (changed) next else current
        }
    }

    /** Logout / account switch: nothing may carry over. */
    fun clear() {
        livePeers.clear()
        latestEventTimes.clear()
        _presences.value = emptyMap()
    }

    /** Drops one peer (hidden presence, or no longer a DIRECT contact). */
    fun clear(userId: String) {
        livePeers.remove(userId)
        latestEventTimes.remove(userId)
        _presences.update { current -> if (current.containsKey(userId)) current - userId else current }
    }

    /** Records a timestamp unless a later presence event was already applied. */
    private fun recordIfCurrent(userId: String, eventTime: Instant): Boolean {
        while (true) {
            val previous = latestEventTimes[userId]
            if (previous != null && eventTime.isBefore(previous)) return false
            if (previous == null) {
                if (latestEventTimes.putIfAbsent(userId, eventTime) == null) return true
            } else if (latestEventTimes.replace(userId, previous, eventTime)) {
                return true
            }
        }
    }

    private fun parseEventTime(serverTs: String?): Instant? = serverTs?.let { value ->
        runCatching { Instant.parse(value) }.getOrElse {
            runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
        }
    }
}
