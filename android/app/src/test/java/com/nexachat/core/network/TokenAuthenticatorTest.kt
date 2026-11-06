package com.nexachat.core.network

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.nexachat.MainDispatcherRule
import com.nexachat.core.network.dto.AuthResponse
import com.nexachat.core.network.dto.RefreshRequest
import com.nexachat.core.network.dto.UserDto
import com.nexachat.core.storage.TokenCipher
import com.nexachat.core.storage.TokenStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Provider
import kotlin.concurrent.thread
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import retrofit2.Response as RetrofitResponse

/**
 * JVM coverage of [TokenAuthenticator]'s single-flight contract.
 *
 * No MockWebServer in the version catalog, so 401s are crafted directly with
 * [Response.Builder] (`priorResponse` chains drive `responseCount`) and
 * `authenticate()` is invoked synchronously — exactly what OkHttp does on its
 * dispatcher threads.
 *
 * [TokenStore] runs for real over an in-memory DataStore + pass-through cipher
 * (the same seam [com.nexachat.core.storage.TokenStoreTest] uses): the store's
 * save/read/clear contract is honored, only persistence is faked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TokenAuthenticatorTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // ------------------------------------------------------------- fakes

    /** Minimal in-memory [DataStore] — enough for edit()/data semantics. */
    private class FakeDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences = transform(state.value).also { state.value = it }
    }

    /** Identity cipher — the store only needs a reversible transform here. */
    private class PassThroughCipher : TokenCipher {
        override fun encrypt(plaintext: ByteArray): ByteArray = plaintext
        override fun decrypt(packed: ByteArray): ByteArray = packed
    }

    private fun newStore(): TokenStore = TokenStore(FakeDataStore(), PassThroughCipher())

    private fun authResponse(accessToken: String, refreshToken: String): AuthResponse =
        AuthResponse(
            UserDto("user-1", "+84905551122", "IT User"),
            accessToken,
            900,
            refreshToken,
        )

    /**
     * A synthetic 401 [Response] — the shape OkHttp hands the authenticator.
     * [priorResponses] > 0 links retry attempts so `responseCount` grows.
     */
    private fun unauthorizedResponse(
        url: String = "https://nexachat.test/api/v1/users/me",
        bearerToken: String? = STALE_ACCESS,
        priorResponses: Int = 0,
    ): Response {
        val request = Request.Builder()
            .url(url)
            .apply {
                if (bearerToken != null) {
                    header(
                        AuthInterceptor.HEADER_AUTHORIZATION,
                        AuthInterceptor.BEARER_PREFIX + bearerToken,
                    )
                }
            }
            .build()
        var response = responseFor(request, prior = null)
        repeat(priorResponses) { response = responseFor(request, prior = response) }
        return response
    }

    private fun responseFor(request: Request, prior: Response?): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .apply { if (prior != null) priorResponse(prior) }
            .build()

    /**
     * Blocks until [thread] is parked waiting for the refresher's mutex.
     *
     * The observable state replaces a fixed sleep: the test may only release
     * the in-flight refresh once the sibling is genuinely queued, so it can
     * neither race ahead (two refreshes) nor wait needlessly. The deadline is
     * a failure bound, not a pause.
     */
    private fun awaitQueuedOnRefreshLock(thread: Thread, timeoutMs: Long = 10_000) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (thread.isAlive &&
            thread.state != Thread.State.WAITING &&
            thread.state != Thread.State.TIMED_WAITING
        ) {
            assertTrue(
                "second caller never queued on the refresh lock",
                System.nanoTime() < deadline,
            )
            Thread.yield()
        }
    }

    // ------------------------------------------------------------- tests

    @Test
    fun `concurrent 401s sharing one token trigger exactly one refresh`() = runTest {
        val store = newStore()
        store.saveTokens(access = STALE_ACCESS, accessExpiresAtEpochMs = 0L, refresh = "rt-stale")

        val refreshEntered = CountDownLatch(1)
        val releaseRefresh = CountDownLatch(1)
        val api = mockk<AuthApi>()
        coEvery { api.refresh(any()) } coAnswers {
            // Hold the first refresh open so the second caller is forced to
            // queue on the authenticator's mutex — that is the overlap.
            refreshEntered.countDown()
            releaseRefresh.await(10, TimeUnit.SECONDS)
            RetrofitResponse.success(authResponse(NEW_ACCESS, "rt-new"))
        }
        val authenticator = TokenAuthenticator(TokenRefresher(store, SessionEvents(), Provider { api }))

        val results = ConcurrentLinkedQueue<Request?>()
        val t1 = thread { results += authenticator.authenticate(null, unauthorizedResponse()) }
        assertTrue("first caller never reached refresh", refreshEntered.await(10, TimeUnit.SECONDS))

        val t2 = thread { results += authenticator.authenticate(null, unauthorizedResponse()) }
        awaitQueuedOnRefreshLock(t2)
        releaseRefresh.countDown()
        t1.join(10_000)
        t2.join(10_000)

        coVerify(exactly = 1) { api.refresh(RefreshRequest("rt-stale")) }
        assertEquals(2, results.size)
        // Winner retried with the refreshed token; the queued sibling re-read the
        // store, saw the access token had already rotated, and reused it.
        results.forEach {
            assertEquals("Bearer $NEW_ACCESS", it?.header(AuthInterceptor.HEADER_AUTHORIZATION))
        }
        assertEquals("rt-new", store.readTokens()?.refreshToken)
    }

    @Test
    fun `stored token already rotated retries with it without calling refresh`() = runTest {
        val store = newStore()
        store.saveTokens(access = NEW_ACCESS, accessExpiresAtEpochMs = Long.MAX_VALUE, refresh = "rt-current")
        val api = mockk<AuthApi>()
        val authenticator = TokenAuthenticator(TokenRefresher(store, SessionEvents(), Provider { api }))

        // The failed request carries an older token than the store — a sibling
        // already refreshed; just retry with the current one.
        val retried = authenticator.authenticate(null, unauthorizedResponse(bearerToken = STALE_ACCESS))

        assertEquals("Bearer $NEW_ACCESS", retried?.header(AuthInterceptor.HEADER_AUTHORIZATION))
        coVerify(exactly = 0) { api.refresh(any()) }
    }

    @Test
    fun `refresh 401 clears tokens and emits LoggedOut`() = runTest {
        val store = newStore()
        store.saveTokens(access = STALE_ACCESS, accessExpiresAtEpochMs = 0L, refresh = "rt-dead")
        val api = mockk<AuthApi>()
        coEvery { api.refresh(any()) } returns RetrofitResponse.error<AuthResponse>(
            401,
            """{"error":{"code":"TOKEN_REUSED","message":"refresh token reused"}}"""
                .toResponseBody("application/json".toMediaType()),
        )
        val sessionEvents = SessionEvents()
        val authenticator = TokenAuthenticator(TokenRefresher(store, sessionEvents, Provider { api }))

        // Subscribe BEFORE authenticate() on an eager dispatcher — the SharedFlow
        // has replay=0, so an event emitted without an active collector is lost.
        val firstEvent = backgroundScope.async(UnconfinedTestDispatcher()) {
            sessionEvents.events.first()
        }

        val result = authenticator.authenticate(null, unauthorizedResponse(bearerToken = STALE_ACCESS))

        assertNull(result) // definitive rejection → request is not retried
        assertEquals(SessionEvents.Event.LoggedOut.INSTANCE, firstEvent.await())
        assertNull(store.readTokens())
    }

    @Test
    fun `401 on public auth path returns null and never touches the store`() = runTest {
        // A raw mock: any store access would throw, and the verifies pin down none happened.
        val store = mockk<TokenStore>()
        val api = mockk<AuthApi>()
        val authenticator = TokenAuthenticator(TokenRefresher(store, SessionEvents(), Provider { api }))

        val result = authenticator.authenticate(
            null,
            unauthorizedResponse(url = "https://nexachat.test/api/v1/auth/refresh"),
        )

        assertNull(result)
        coVerify(exactly = 0) { store.readTokens() }
        coVerify(exactly = 0) { store.clear() }
        coVerify(exactly = 0) { api.refresh(any()) }
    }

    @Test
    fun `third chained 401 gives up instead of storming`() = runTest {
        val store = newStore()
        store.saveTokens(access = STALE_ACCESS, accessExpiresAtEpochMs = 0L, refresh = "rt-1")
        val api = mockk<AuthApi>()
        val authenticator = TokenAuthenticator(TokenRefresher(store, SessionEvents(), Provider { api }))

        // response + 2 priors → responseCount == 3 == MAX_ATTEMPTS → null.
        val result = authenticator.authenticate(null, unauthorizedResponse(priorResponses = 2))

        assertNull(result)
        coVerify(exactly = 0) { api.refresh(any()) }
    }

    private companion object {
        const val STALE_ACCESS = "stale-access-token"
        const val NEW_ACCESS = "new-access-token"
    }
}
