package com.nexachat.core.network

import com.nexachat.core.storage.TokenStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds `Authorization: Bearer <access>` to every request except the public
 * auth endpoints (`register`/`login`/`refresh` — see [AuthApi.isPublicAuthPath];
 * refresh authenticates via its body, not a header).
 *
 * `runBlocking` is deliberate: OkHttp interceptors are synchronous and run on
 * OkHttp's dispatcher pool, never on the main thread; DataStore reads are
 * memory-cached after first load so the block is short.
 */
@Singleton
class AuthInterceptor @Inject constructor(
    private val tokenStore: TokenStore,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (AuthApi.isPublicAuthPath(request.url.encodedPath)) {
            return chain.proceed(request)
        }
        val accessToken = runBlocking { tokenStore.readTokens() }?.accessToken
        val authenticated = if (accessToken != null) {
            request.newBuilder()
                .header(HEADER_AUTHORIZATION, "$BEARER_PREFIX$accessToken")
                .build()
        } else {
            request
        }
        return chain.proceed(authenticated)
    }

    companion object {
        const val HEADER_AUTHORIZATION = "Authorization"
        const val BEARER_PREFIX = "Bearer "
    }
}
