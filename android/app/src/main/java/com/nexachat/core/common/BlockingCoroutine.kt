package com.nexachat.core.common

import com.nexachat.core.database.ConversationDao
import com.nexachat.core.database.ConversationEntity
import com.nexachat.core.network.TokenRefreshOutcome
import com.nexachat.core.network.TokenRefresher
import com.nexachat.core.storage.TokenStore
import com.nexachat.core.storage.Tokens
import kotlinx.coroutines.flow.first
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx3.await
import kotlinx.coroutines.rx3.awaitSingleOrNull

/**
 * Bridges OkHttp interceptors (Java, synchronous API) to suspend token/DAO
 * calls without using [kotlinx.coroutines.runBlocking], which would block the
 * thread on a private event loop and risks re-entrancy issues.
 */
object BlockingCoroutine {

    @JvmStatic
    @Throws(InterruptedException::class)
    fun <T> await(block: suspend CoroutineScope.() -> T): T {
        val done = CountDownLatch(1)
        var value: Any? = null
        var error: Throwable? = null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
        val job = scope.launch {
            try {
                value = block()
            } catch (e: CancellationException) {
                error = e
                throw e
            } catch (e: Throwable) {
                error = e
            } finally {
                done.countDown()
            }
        }
        try {
            done.await()
        } catch (e: InterruptedException) {
            job.cancel()
            Thread.currentThread().interrupt()
            throw e
        }
        error?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }
}

/** Java-facing entry points for [BlockingCoroutine.await] with suspend peers. */
object BlockingCalls {

    @JvmStatic
    @Throws(InterruptedException::class)
    fun readTokens(store: TokenStore): Tokens? =
        BlockingCoroutine.await { store.readTokens().awaitSingleOrNull() }

    @JvmStatic
    @Throws(InterruptedException::class)
    fun saveTokens(store: TokenStore, access: String, accessExpiresAtEpochMs: Long, refresh: String) {
        BlockingCoroutine.await { store.saveTokens(access, accessExpiresAtEpochMs, refresh) }
    }

    @JvmStatic
    @Throws(InterruptedException::class)
    fun clearTokens(store: TokenStore) {
        BlockingCoroutine.await { store.clear() }
    }

    /** Current session flag. Callers on OkHttp or reconnect threads may block here. */
    @JvmStatic
    @Throws(InterruptedException::class)
    fun hasValidRefresh(store: TokenStore): Boolean =
        BlockingCoroutine.await { store.hasValidRefresh.first() }

    /**
     * Synchronous 401 refresh for [com.nexachat.core.network.TokenAuthenticator].
     * The Rx worker runs on [io.reactivex.rxjava3.schedulers.Schedulers.io], so this
     * wait must not run on the Android main thread.
     */
    @JvmStatic
    @Throws(InterruptedException::class)
    fun refreshIfNeeded(refresher: TokenRefresher, failedAccessToken: String?): TokenRefreshOutcome =
        try {
            val token = refresher.refreshIfNeeded(failedAccessToken).blockingGet()
            if (token.isPresent) TokenRefreshOutcome.FreshToken(token.get())
            else TokenRefreshOutcome.NoRefreshToken.INSTANCE
        } catch (ex: RuntimeException) {
            val cause = ex.cause
            if (cause is InterruptedException) {
                throw cause
            }
            throw ex
        }

    @JvmStatic
    @Throws(InterruptedException::class)
    fun conversationById(dao: ConversationDao, conversationId: String): ConversationEntity? =
        BlockingCoroutine.await { dao.byId(conversationId).awaitSingleOrNull() }
}
