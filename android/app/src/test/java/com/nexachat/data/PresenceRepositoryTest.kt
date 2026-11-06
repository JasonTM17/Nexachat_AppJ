package com.nexachat.data

import com.nexachat.core.network.SessionEvents
import com.nexachat.core.network.AppForegroundState
import com.nexachat.core.network.NetworkMonitor
import com.nexachat.core.network.TokenRefresher
import com.nexachat.core.network.stomp.ChatEvent
import com.nexachat.core.network.stomp.ReconnectPolicy
import com.nexachat.core.network.stomp.RealtimeSession
import com.nexachat.core.network.stomp.StompClient
import com.nexachat.core.network.stomp.StompCommand
import com.nexachat.core.network.stomp.StompFrame
import com.nexachat.core.storage.TokenStore
import com.squareup.moshi.Moshi
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Presence fan-in lifecycle: live pushes, inbox seeds (a stale seed must not
 * demote a peer already seen online) and the logout wipe.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PresenceRepositoryTest {

    private val session = mockk<RealtimeSession>()
    private val sessionEvents = mockk<SessionEvents>()
    private val chatEvents = MutableSharedFlow<ChatEvent>(extraBufferCapacity = 8)
    private val sessionSignals = MutableSharedFlow<SessionEvents.Event>(extraBufferCapacity = 4)

    private lateinit var repository: PresenceRepository

    @Before
    fun setUp() {
        every { session.events } returns chatEvents
        every { sessionEvents.events } returns sessionSignals
    }

    private fun TestScope.build() {
        repository = PresenceRepository(
            session = session,
            sessionEvents = sessionEvents,
            scope = backgroundScope,
        )
        // Start the init collectors before the first emission — a SharedFlow
        // with no subscriber drops it.
        runCurrent()
    }

    private fun TestScope.buildWithProductionRealtime(): MutableSharedFlow<StompFrame> {
        val frames = MutableSharedFlow<StompFrame>(extraBufferCapacity = 8)
        val tokenStore = mockk<TokenStore>()
        every { tokenStore.hasValidRefresh } returns MutableStateFlow(false)
        coEvery { tokenStore.readTokens() } returns null

        val stompClient = mockk<StompClient>(relaxed = true)
        every { stompClient.frames } returns frames
        every { stompClient.subscriptionIdFor(any()) } returns "errors-sub"
        val networkMonitor = mockk<NetworkMonitor>()
        every { networkMonitor.regained } returns MutableSharedFlow()
        val foreground = object : AppForegroundState {
            override val isForeground = flowOf(false)
        }
        val realtimeSession = RealtimeSession(
            stompClient = stompClient,
            tokenStore = tokenStore,
            tokenRefresher = mockk<TokenRefresher>(relaxed = true),
            moshi = Moshi.Builder().build(),
            reconnectPolicy = ReconnectPolicy(),
            networkMonitor = networkMonitor,
            foreground = foreground,
            scope = backgroundScope,
        )
        repository = PresenceRepository(
            session = realtimeSession,
            sessionEvents = sessionEvents,
            scope = backgroundScope,
        )
        runCurrent()
        return frames
    }

    private suspend fun TestScope.emitFrame(frames: MutableSharedFlow<StompFrame>, body: String) {
        frames.emit(
            StompFrame(
                StompCommand.MESSAGE,
                linkedMapOf("subscription" to "presence-sub"),
                body,
            ),
        )
        runCurrent()
    }

    @Test
    fun `online and offline pushes update the map`() = runTest {
        build()

        chatEvents.emit(ChatEvent.Presence("u-1", online = true, lastSeenAt = null))
        runCurrent()
        assertEquals(PeerPresence(true, null), repository.presences.value["u-1"])

        chatEvents.emit(
            ChatEvent.Presence("u-1", online = false, lastSeenAt = "2026-11-03T09:12:04Z"),
        )
        runCurrent()
        assertEquals(
            PeerPresence(false, isoToEpochMsOrNull("2026-11-03T09:12:04Z")),
            repository.presences.value["u-1"],
        )
    }

    @Test
    fun `hidden presence never invents a last-seen value`() = runTest {
        build()

        chatEvents.emit(ChatEvent.Presence("u-1", online = false, lastSeenAt = null))
        runCurrent()

        assertEquals(PeerPresence(false, null), repository.presences.value["u-1"])
    }

    @Test
    fun `a stale offline seed never demotes a peer already online`() = runTest {
        build()
        chatEvents.emit(ChatEvent.Presence("u-1", online = true, lastSeenAt = null))
        runCurrent()

        repository.seed(mapOf("u-1" to PeerPresence(online = false, lastSeenAtEpochMs = 5L)))

        assertEquals(PeerPresence(true, null), repository.presences.value["u-1"])
    }

    @Test
    fun `a seed promotes an offline peer to online`() = runTest {
        build()
        repository.seed(mapOf("u-1" to PeerPresence(online = false, lastSeenAtEpochMs = 5L)))
        assertEquals(PeerPresence(false, 5L), repository.presences.value["u-1"])

        repository.seed(mapOf("u-1" to PeerPresence(online = true, lastSeenAtEpochMs = null)))

        assertEquals(PeerPresence(true, null), repository.presences.value["u-1"])
    }

    @Test
    fun `logout clears every cached presence`() = runTest {
        build()
        chatEvents.emit(ChatEvent.Presence("u-1", online = true, lastSeenAt = null))
        runCurrent()
        assertTrue(repository.presences.value.isNotEmpty())

        sessionSignals.emit(SessionEvents.Event.LoggedOut.INSTANCE)
        runCurrent()

        assertTrue(repository.presences.value.isEmpty())
    }

    @Test
    fun `offline event is not overwritten by a stale inbox seed`() = runTest {
        val frames = buildWithProductionRealtime()
        repository.seed(mapOf("u-1" to PeerPresence(online = true, lastSeenAtEpochMs = null)))

        emitFrame(
            frames,
            """{"eventType":"USER_ONLINE","conversationId":null,"serverTs":"2026-11-05T14:46:58.000Z","payload":{"userId":"u-1","online":true,"lastSeenAt":null}}""",
        )
        emitFrame(
            frames,
            """{"eventType":"USER_OFFLINE","conversationId":null,"serverTs":"2026-11-05T14:47:15.000Z","payload":{"userId":"u-1","online":false,"lastSeenAt":"2026-11-05T14:47:10.000Z"}}""",
        )
        val offline = PeerPresence(
            online = false,
            lastSeenAtEpochMs = isoToEpochMsOrNull("2026-11-05T14:47:10.000Z"),
        )
        assertEquals(offline, repository.presences.value["u-1"])

        // This models an inbox request that started while the peer was online
        // and returned after the newer offline event had already been applied.
        repository.seed(mapOf("u-1" to PeerPresence(online = true, lastSeenAtEpochMs = null)))

        assertEquals(offline, repository.presences.value["u-1"])
    }

    @Test
    fun `out of order and duplicate frames retain the newest presence`() = runTest {
        val frames = buildWithProductionRealtime()
        val online = """{"eventType":"USER_ONLINE","conversationId":null,"serverTs":"2026-11-05T14:46:58.000Z","payload":{"userId":"u-1","online":true,"lastSeenAt":null}}"""
        val offline = """{"eventType":"USER_OFFLINE","conversationId":null,"serverTs":"2026-11-05T14:47:15.000Z","payload":{"userId":"u-1","online":false,"lastSeenAt":"2026-11-05T14:47:10.000Z"}}"""

        emitFrame(frames, online)
        emitFrame(frames, offline)
        emitFrame(frames, online) // delayed copy of the older online state

        val expectedOffline = PeerPresence(
            online = false,
            lastSeenAtEpochMs = isoToEpochMsOrNull("2026-11-05T14:47:10.000Z"),
        )
        assertEquals(expectedOffline, repository.presences.value["u-1"])

        emitFrame(frames, offline) // exact duplicate delivery

        assertEquals(expectedOffline, repository.presences.value["u-1"])
    }
}
