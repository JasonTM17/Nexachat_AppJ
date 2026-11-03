package com.nexachat.core.network.stomp

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.nexachat.core.network.NetworkMonitor
import com.nexachat.core.storage.TokenCipher
import com.nexachat.core.storage.TokenStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Guards the connectivity-regain re-arm: after the reconnect loop exhausts
 * [ReconnectPolicy.MAX_ATTEMPTS] and reports OFFLINE, a validated network
 * returning must start a fresh loop — the outbox should flush without the
 * user having to navigate or retry manually.
 *
 * [TokenStore] runs for real over an in-memory DataStore + pass-through
 * cipher — the same seam TokenStoreTest/TokenAuthenticatorTest use — so the
 * `hasValidRefresh` gating behaves exactly as in production.
 */
class RealtimeSessionReArmTest {

    private class FakeDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences = transform(state.value).also { state.value = it }
    }

    private class PassThroughCipher : TokenCipher {
        override fun encrypt(plaintext: ByteArray): ByteArray = plaintext
        override fun decrypt(packed: ByteArray): ByteArray = packed
    }

    private suspend fun loggedInStore(): TokenStore =
        TokenStore(FakeDataStore(), PassThroughCipher()).also {
            it.saveTokens("access", Long.MAX_VALUE, "refresh")
        }

    private fun session(
        stompClient: StompClient,
        tokenStore: TokenStore,
        monitor: NetworkMonitor,
        scope: CoroutineScope,
    ) = RealtimeSession(
        stompClient = stompClient,
        tokenStore = tokenStore,
        tokenRefresher = mockk(relaxed = true),
        moshi = mockk(relaxed = true),
        reconnectPolicy = ReconnectPolicy(),
        networkMonitor = monitor,
        scope = scope,
    )

    /**
     * Relaxed-mock helper: `frames` must be a REAL MutableSharedFlow — the
     * relaxed default completes `collect`'s `Nothing`-typed suspend and kills
     * the whole injected scope with KotlinNothingValueException.
     */
    private fun stompClientStub(connectFlow: () -> Flow<StompClient.State>): StompClient =
        mockk(relaxed = true) {
            every { frames } returns MutableSharedFlow()
            every { connect() } answers { connectFlow() }
        }

    @Test
    fun `network regain re-arms a parked connection loop`() = runTest {
        val regain = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val monitor = mockk<NetworkMonitor> { every { regained } returns regain }
        // Every attempt fails fast; ReconnectPolicy budget = 5.
        val stompClient = stompClientStub { flowOf(StompClient.State.Failed("boom")) }
        session(stompClient, loggedInStore(), monitor, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        advanceUntilIdle()
        verify(exactly = ReconnectPolicy.MAX_ATTEMPTS) { stompClient.connect() }

        regain.emit(Unit)
        advanceUntilIdle()
        // Regain wakes the parked loop — another full retry cycle runs.
        verify(atLeast = ReconnectPolicy.MAX_ATTEMPTS + 1) { stompClient.connect() }
    }

    @Test
    fun `ensureConnected wakes a parked loop without network regain`() = runTest {
        // The defect found on the release build: socket died while the app
        // sat backgrounded — network never toggled, so no regain callback.
        // ensureConnected (user action / navigation) must still re-arm.
        val monitor = mockk<NetworkMonitor> {
            every { regained } returns MutableSharedFlow()
        }
        val stompClient = stompClientStub { flowOf(StompClient.State.Failed("boom")) }
        val s = session(stompClient, loggedInStore(), monitor, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        advanceUntilIdle()
        verify(exactly = ReconnectPolicy.MAX_ATTEMPTS) { stompClient.connect() }

        s.ensureConnected()
        advanceUntilIdle()
        verify(exactly = 2 * ReconnectPolicy.MAX_ATTEMPTS) { stompClient.connect() }
    }

    @Test
    fun `onAppForeground wakes a parked loop while logged in`() = runTest {
        val monitor = mockk<NetworkMonitor> {
            every { regained } returns MutableSharedFlow()
        }
        val stompClient = stompClientStub { flowOf(StompClient.State.Failed("boom")) }
        val s = session(stompClient, loggedInStore(), monitor, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        advanceUntilIdle()
        verify(exactly = ReconnectPolicy.MAX_ATTEMPTS) { stompClient.connect() }

        s.onAppForeground()
        advanceUntilIdle()
        verify(exactly = 2 * ReconnectPolicy.MAX_ATTEMPTS) { stompClient.connect() }
    }

    @Test
    fun `onAppForeground while logged out does nothing`() = runTest {
        val monitor = mockk<NetworkMonitor> {
            every { regained } returns MutableSharedFlow()
        }
        val stompClient = stompClientStub { flowOf(StompClient.State.Failed("boom")) }
        val s = session(
            stompClient,
            TokenStore(FakeDataStore(), PassThroughCipher()),
            monitor,
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
        advanceUntilIdle()

        s.onAppForeground()
        advanceUntilIdle()
        verify(exactly = 0) { stompClient.connect() }
    }

    @Test
    fun `regain while logged out does not spin a loop`() = runTest {
        val regain = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val monitor = mockk<NetworkMonitor> { every { regained } returns regain }
        val stompClient = stompClientStub { flowOf(StompClient.State.Failed("boom")) }
        session(
            stompClient,
            TokenStore(FakeDataStore(), PassThroughCipher()),
            monitor,
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
        advanceUntilIdle()

        regain.emit(Unit)
        advanceUntilIdle()
        verify(exactly = 0) { stompClient.connect() }
    }

    @Test
    fun `probe - init launches run`() = runTest {
        val regain = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val monitor = mockk<NetworkMonitor> { every { regained } returns regain }
        val stompClient = stompClientStub { flowOf(StompClient.State.Failed("boom")) }
        val s = session(stompClient, loggedInStore(), monitor, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        advanceUntilIdle()
        println("isOpen=${s.isOpen.value}")
        verify { stompClient.frames }
        verify { monitor.regained }
        verify { stompClient.connect() }
    }

    /**
     * The check-and-launch in `launchConnectionLoop` must be atomic: token
     * restore, a regain callback, and a user action can all fire at once on
     * different threads. Without the lock, two callers can both pass the
     * `isActive` check and orphan a second loop (two sockets, duplicate
     * subscriptions). Hammering `ensureConnected` from real dispatcher
     * threads after the loop dies must start exactly ONE new retry cycle.
     * Every backoff delay is >0 so the winning job stays parked-active on
     * the test scheduler while the losers arrive — deterministic.
     */
    @Test
    fun `concurrent re-arm calls launch a single connection loop`() = runTest {
        val regain = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val monitor = mockk<NetworkMonitor> { every { regained } returns regain }
        val stompClient = stompClientStub { flowOf(StompClient.State.Failed("boom")) }
        val s = session(stompClient, loggedInStore(), monitor, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        advanceUntilIdle()
        verify(exactly = ReconnectPolicy.MAX_ATTEMPTS) { stompClient.connect() }

        coroutineScope {
            repeat(50) {
                launch(Dispatchers.Default) { s.ensureConnected() }
            }
        }
        advanceUntilIdle()
        verify(exactly = 2 * ReconnectPolicy.MAX_ATTEMPTS) { stompClient.connect() }
    }

    @Test
    fun `regain while socket open is a no-op`() = runTest {
        val regain = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val monitor = mockk<NetworkMonitor> { every { regained } returns regain }
        // Open and hang — the socket stays open (collect never completes).
        val stompClient = stompClientStub {
            flow {
                emit(StompClient.State.Open)
                kotlinx.coroutines.awaitCancellation()
            }
        }
        session(stompClient, loggedInStore(), monitor, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        advanceUntilIdle()
        verify(exactly = 1) { stompClient.connect() }

        regain.emit(Unit)
        advanceUntilIdle()
        verify(exactly = 1) { stompClient.connect() }
    }
}
