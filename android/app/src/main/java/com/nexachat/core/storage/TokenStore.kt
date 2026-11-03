package com.nexachat.core.storage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Session token persistence.
 *
 * Security model (plan §13 "JWT theft"): the AES/GCM key is generated inside
 * AndroidKeyStore (`NexaChatTokenKey`) and **never leaves the hardware-backed
 * keystore** — this class only ever sees ciphertext. What lands in DataStore
 * Preferences is therefore `Base64(IV || AES-GCM ciphertext)` per token, i.e.
 * reading `preferences_pb` (root, backup, log leak) yields nothing usable.
 *
 * Three keys are stored:
 *  - `access_token`       → encrypted access JWT (String)
 *  - `access_expires_at`  → plaintext expiry, epoch ms (Long — not secret)
 *  - `refresh_token`      → encrypted opaque refresh token (String)
 *
 * `java.util.Base64` (not `android.util.Base64`) so the store logic stays
 * unit-testable on the JVM — API 26+ ships it.
 */
@Singleton
class TokenStore @Inject constructor(
    @TokenPreferences private val dataStore: DataStore<Preferences>,
    private val cipher: TokenCipher,
) {

    data class Tokens(
        val accessToken: String,
        val accessTokenExpiresAtEpochMs: Long,
        val refreshToken: String,
    ) {
        fun isAccessExpired(nowEpochMs: Long = System.currentTimeMillis()): Boolean =
            nowEpochMs >= accessTokenExpiresAtEpochMs
    }

    private object Keys {
        val ACCESS_TOKEN = stringPreferencesKey("access_token")
        val ACCESS_EXPIRES_AT = longPreferencesKey("access_expires_at")
        val REFRESH_TOKEN = stringPreferencesKey("refresh_token")
    }

    suspend fun saveTokens(access: String, accessExpiresAtEpochMs: Long, refresh: String) {
        dataStore.edit { prefs ->
            prefs[Keys.ACCESS_TOKEN] = cipher.encrypt(access.toByteArray(Charsets.UTF_8)).b64()
            prefs[Keys.ACCESS_EXPIRES_AT] = accessExpiresAtEpochMs
            prefs[Keys.REFRESH_TOKEN] = cipher.encrypt(refresh.toByteArray(Charsets.UTF_8)).b64()
        }
    }

    /** `null` when absent or undecryptable (corruption is treated as logged-out). */
    suspend fun readTokens(): Tokens? = dataStore.data.first().toTokens()

    suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.remove(Keys.ACCESS_TOKEN)
            prefs.remove(Keys.ACCESS_EXPIRES_AT)
            prefs.remove(Keys.REFRESH_TOKEN)
        }
    }

    /**
     * `true` while a refresh token is stored locally. This is a *presence*
     * check only — the server is the real arbiter of validity (rotation/reuse
     * detection); callers must attempt `refresh()` and handle its 401.
     */
    val hasValidRefresh: Flow<Boolean> =
        dataStore.data.map { prefs -> !prefs[Keys.REFRESH_TOKEN].isNullOrBlank() }

    private fun Preferences.toTokens(): Tokens? {
        val access = this[Keys.ACCESS_TOKEN]?.decryptString() ?: return null
        val expiresAt = this[Keys.ACCESS_EXPIRES_AT] ?: return null
        val refresh = this[Keys.REFRESH_TOKEN]?.decryptString() ?: return null
        return Tokens(access, expiresAt, refresh)
    }

    private fun String.decryptString(): String? = runCatching {
        String(cipher.decrypt(b64decode()), Charsets.UTF_8)
    }.getOrNull()

    private fun ByteArray.b64(): String = Base64.getEncoder().encodeToString(this)

    private fun String.b64decode(): ByteArray = Base64.getDecoder().decode(this)
}
