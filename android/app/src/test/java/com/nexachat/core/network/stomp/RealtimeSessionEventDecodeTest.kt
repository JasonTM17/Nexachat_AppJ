package com.nexachat.core.network.stomp

import app.cash.turbine.test
import com.nexachat.core.network.AppForegroundState
import com.nexachat.core.network.NetworkMonitor
import com.nexachat.core.network.TokenRefresher
import com.nexachat.core.storage.TokenStore
import com.squareup.moshi.Moshi
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Envelope decoding for the feature events (F1/F2/F3) and the forward-compat
 * rule: an unknown `eventType` decodes to [ChatEvent.Unknown] (the
 * repositories ignore it) instead of throwing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RealtimeSessionEventDecodeTest {

    private val frames = MutableSharedFlow<StompFrame>(extraBufferCapacity = 8)

    private fun TestScopeJvm.session(errorSubscriptionId: String? = null): RealtimeSession {
        val tokenStore = mockk<TokenStore>()
        every { tokenStore.hasValidRefresh } returns MutableStateFlow(false)
        coEvery { tokenStore.readTokens() } returns null
        val stompClient = mockk<StompClient>(relaxed = true) {
            every { frames } returns this@RealtimeSessionEventDecodeTest.frames
            every { subscriptionIdFor(any()) } returns null
        }
        if (errorSubscriptionId != null) {
            every {
                stompClient.subscriptionIdFor(RealtimeSession.USER_ERRORS_DESTINATION)
            } returns errorSubscriptionId
        }
        val monitor = mockk<NetworkMonitor> {
            every { regained } returns MutableSharedFlow()
        }
        val foreground = object : AppForegroundState {
            override val isForeground = flowOf(false)
        }
        return RealtimeSession(
            stompClient = stompClient,
            tokenStore = tokenStore,
            tokenRefresher = mockk<TokenRefresher>(relaxed = true),
            moshi = Moshi.Builder().build(),
            reconnectPolicy = ReconnectPolicy(),
            networkMonitor = monitor,
            foreground = foreground,
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
    }

    @Test
    fun `MESSAGE_CREATED decodes the full message payload`() = runTest {
        val session = session()
        session.events.test {
            frames.emit(
                messageFrame(
                    """
                    {"eventType":"MESSAGE_CREATED","conversationId":"c-1",
                     "serverTs":"2026-11-03T09:12:04Z",
                     "payload":{"id":"m-1","seq":1,"conversationId":"c-1",
                     "senderId":"u-1","type":"TEXT","content":"hello",
                     "createdAt":"2026-11-03T09:12:04Z"}}
                    """.trimIndent(),
                ),
            )

            val event = awaitItem() as ChatEvent.MessageCreated
            assertEquals("c-1", event.conversationId)
            assertEquals("m-1", event.message.id)
            assertEquals(1L, event.message.seq)
            assertEquals("hello", event.message.content)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun messageFrame(json: String) = StompFrame(
        StompCommand.MESSAGE,
        linkedMapOf("subscription" to "sub-1"),
        json,
    )

    @Test
    fun `every open re-subscribes the presence user queue`() = runTest {
        val tokenStore = mockk<TokenStore>()
        every { tokenStore.hasValidRefresh } returns MutableStateFlow(true)
        coEvery { tokenStore.readTokens() } returns
            TokenStore.Tokens("access", Long.MAX_VALUE, "refresh")
        val stompClient = mockk<StompClient>(relaxed = true) {
            every { frames } returns this@RealtimeSessionEventDecodeTest.frames
            every { subscriptionIdFor(any()) } returns null
            // Open then hang: a flow that COMPLETES right after Open would
            // make the reconnect loop treat it as a failure and reopen at
            // once (an endless 1 s cycle on the virtual clock).
            every { connect() } returns flow {
                emit(StompClient.State.Open)
                kotlinx.coroutines.awaitCancellation()
            }
        }
        val monitor = mockk<NetworkMonitor> {
            every { regained } returns MutableSharedFlow()
        }
        val foreground = object : AppForegroundState {
            override val isForeground = flowOf(false)
        }
        RealtimeSession(
            stompClient = stompClient,
            tokenStore = tokenStore,
            tokenRefresher = mockk<TokenRefresher>(relaxed = true),
            moshi = Moshi.Builder().build(),
            reconnectPolicy = ReconnectPolicy(),
            networkMonitor = monitor,
            foreground = foreground,
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
        advanceUntilIdle()

        // Present in `wantedSubscriptions`, so the reconnect path re-arms it.
        verify { stompClient.subscribe("/user/queue/presence") }
    }

    @Test
    fun `MESSAGE_UPDATED decodes the full message payload`() = runTest {
        val session = session()
        session.events.test {
            frames.emit(
                messageFrame(
                    """
                    {"eventType":"MESSAGE_UPDATED","conversationId":"c-1",
                     "serverTs":"2026-11-03T10:00:00Z",
                     "payload":{"id":"m-1","seq":1,"conversationId":"c-1",
                     "senderId":"u-1","type":"TEXT","content":"edited",
                     "editedAt":"2026-11-03T10:00:00Z"}}
                    """.trimIndent(),
                ),
            )

            val event = awaitItem() as ChatEvent.MessageUpdated
            assertEquals("c-1", event.conversationId)
            assertEquals("m-1", event.message.id)
            assertEquals("edited", event.message.content)
            assertEquals("2026-11-03T10:00:00Z", event.message.editedAt)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `MESSAGE_DELETED decodes messageId and deletedAt`() = runTest {
        val session = session()
        session.events.test {
            frames.emit(
                messageFrame(
                    """
                    {"eventType":"MESSAGE_DELETED","conversationId":"c-1",
                     "payload":{"messageId":"m-1","deletedAt":"2026-11-03T11:00:00Z"}}
                    """.trimIndent(),
                ),
            )

            val event = awaitItem() as ChatEvent.MessageDeleted
            assertEquals("c-1", event.conversationId)
            assertEquals("m-1", event.messageId)
            assertEquals("2026-11-03T11:00:00Z", event.deletedAt)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `USER_ONLINE and USER_OFFLINE decode presence with last seen`() = runTest {
        val session = session()
        session.events.test {
            frames.emit(
                messageFrame(
                    """
                    {"eventType":"USER_ONLINE","conversationId":null,
                     "serverTs":"2026-11-03T11:59:00.000Z","payload":{"userId":"u-2","online":true,
                     "lastSeenAt":null}}
                    """.trimIndent(),
                ),
            )
            val online = awaitItem() as ChatEvent.Presence
            assertEquals("u-2", online.userId)
            assertTrue(online.online)
            assertEquals(null, online.lastSeenAt)
            assertEquals("2026-11-03T11:59:00.000Z", online.serverTs)

            frames.emit(
                messageFrame(
                    """
                    {"eventType":"USER_OFFLINE","conversationId":null,
                     "serverTs":"2026-11-03T12:00:05.000Z","payload":{"userId":"u-2","online":false,
                     "lastSeenAt":"2026-11-03T12:00:00Z"}}
                    """.trimIndent(),
                ),
            )
            val offline = awaitItem() as ChatEvent.Presence
            assertFalse(offline.online)
            assertEquals("2026-11-03T12:00:00Z", offline.lastSeenAt)
            assertEquals("2026-11-03T12:00:05.000Z", offline.serverTs)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `error queue frames decode RATE_LIMITED and INTERNAL_ERROR`() = runTest {
        val session = session(errorSubscriptionId = "errors-sub")
        session.events.test {
            frames.emit(
                StompFrame(
                    StompCommand.MESSAGE,
                    linkedMapOf("subscription" to "errors-sub"),
                    """{"code":"RATE_LIMITED","clientMessageId":"client-1"}""",
                ),
            )
            val limited = awaitItem() as ChatEvent.SendRejected
            assertEquals("RATE_LIMITED", limited.code)
            assertEquals("client-1", limited.clientMessageId)

            frames.emit(
                StompFrame(
                    StompCommand.MESSAGE,
                    linkedMapOf("subscription" to "errors-sub"),
                    """{"code":"INTERNAL_ERROR"}""",
                ),
            )
            val internal = awaitItem() as ChatEvent.SendRejected
            assertEquals("INTERNAL_ERROR", internal.code)
            assertEquals(null, internal.clientMessageId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `unknown event types decode to Unknown and never throw`() = runTest {
        val session = session()
        session.events.test {
            frames.emit(
                messageFrame(
                    """
                    {"eventType":"FUTURE_EVENT","conversationId":"c-1","payload":{"x":1}}
                    """.trimIndent(),
                ),
            )

            val event = awaitItem() as ChatEvent.Unknown
            assertEquals("FUTURE_EVENT", event.eventType)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `malformed frames are ignored without killing the stream`() = runTest {
        val session = session()
        session.events.test {
            frames.emit(
                StompFrame(
                    StompCommand.MESSAGE,
                    linkedMapOf("subscription" to "sub-1"),
                    "not json",
                ),
            )
            frames.emit(
                messageFrame("""{"eventType":"USER_ONLINE","payload":{"userId":"u-3","online":true}}"""),
            )

            val event = awaitItem() as ChatEvent.Presence
            assertEquals("u-3", event.userId)
            cancelAndIgnoreRemainingEvents()
        }
    }
}

/** Alias so the helper reads as a plain factory (keeps the test body terse). */
private typealias TestScopeJvm = kotlinx.coroutines.test.TestScope
