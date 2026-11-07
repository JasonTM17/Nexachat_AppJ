package com.nexachat.core.storage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** DataStore calls for [TokenStore]. Preferences stay ciphertext. */
internal object TokenStoreFlow {

    private val ACCESS_TOKEN = stringPreferencesKey("access_token")
    private val ACCESS_EXPIRES_AT = longPreferencesKey("access_expires_at")
    private val REFRESH_TOKEN = stringPreferencesKey("refresh_token")

    @JvmStatic
    fun hasValidRefresh(dataStore: DataStore<Preferences>, cipher: TokenCipher): Flow<Boolean> =
        dataStore.data.map { prefs -> prefs.toTokens(cipher) != null }

    @JvmStatic
    suspend fun saveTokens(
        dataStore: DataStore<Preferences>,
        cipher: TokenCipher,
        access: String,
        accessExpiresAtEpochMs: Long,
        refresh: String,
    ) {
        dataStore.edit { prefs ->
            prefs[ACCESS_TOKEN] = TokenEncoding.b64(cipher.encrypt(access.toByteArray(Charsets.UTF_8)))
            prefs[ACCESS_EXPIRES_AT] = accessExpiresAtEpochMs
            prefs[REFRESH_TOKEN] = TokenEncoding.b64(cipher.encrypt(refresh.toByteArray(Charsets.UTF_8)))
        }
    }

    @JvmStatic
    suspend fun readTokens(dataStore: DataStore<Preferences>, cipher: TokenCipher): Tokens? =
        dataStore.data.first().toTokens(cipher)

    @JvmStatic
    suspend fun clear(dataStore: DataStore<Preferences>) {
        dataStore.edit { prefs ->
            prefs.remove(ACCESS_TOKEN)
            prefs.remove(ACCESS_EXPIRES_AT)
            prefs.remove(REFRESH_TOKEN)
        }
    }

    private fun Preferences.toTokens(cipher: TokenCipher): Tokens? {
        val access = this[ACCESS_TOKEN]?.decryptString(cipher) ?: return null
        val expiresAt = this[ACCESS_EXPIRES_AT] ?: return null
        val refresh = this[REFRESH_TOKEN]?.decryptString(cipher) ?: return null
        return Tokens(access, expiresAt, refresh)
    }

    private fun String.decryptString(cipher: TokenCipher): String? = runCatching {
        String(cipher.decrypt(TokenEncoding.b64decode(this)), Charsets.UTF_8)
    }.getOrNull()
}
