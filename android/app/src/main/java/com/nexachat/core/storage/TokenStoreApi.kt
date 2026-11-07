package com.nexachat.core.storage

import kotlinx.coroutines.flow.Flow

/** Suspend session API. DataStore itself is a Kotlin library, so the Java store implements this. */
interface TokenStoreApi {
    val hasValidRefresh: Flow<Boolean>

    suspend fun saveTokens(access: String, accessExpiresAtEpochMs: Long, refresh: String)

    suspend fun readTokens(): Tokens?

    suspend fun clear()
}
