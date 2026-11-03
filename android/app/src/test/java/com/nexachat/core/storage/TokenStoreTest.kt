package com.nexachat.core.storage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import app.cash.turbine.test
import java.util.Base64
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM coverage for the honest parts of token persistence:
 *  - [packGcm]/[unpackGcm] — the IV||ciphertext framing itself;
 *  - [TokenStore] — serialization + DataStore flow mapping, using a fake
 *    cipher (XOR, but framed by the *real* pack/unpack) and an in-memory
 *    DataStore. AndroidKeyStore/Cipher stay untested here by design — the
 *    fake exists precisely so no Keystore is faked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TokenStoreTest {

    /** XOR-cipher wearing the real GCM framing — exercises framing, not AES. */
    private class FakeTokenCipher : TokenCipher {
        private val fakeIv = ByteArray(AesGcmTokenCipher.IV_LENGTH) { (it + 1).toByte() }

        override fun encrypt(plaintext: ByteArray): ByteArray =
            packGcm(fakeIv, plaintext.map { (it.toInt() xor XOR).toByte() }.toByteArray())

        override fun decrypt(packed: ByteArray): ByteArray {
            val (_, ciphertext) = unpackGcm(packed)
            return ciphertext.map { (it.toInt() xor XOR).toByte() }.toByteArray()
        }

        private companion object {
            const val XOR = 0x5A
        }
    }

    /** Minimal in-memory [DataStore] — enough for edit()/data semantics. */
    private class FakeDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences = transform(state.value).also { state.value = it }
    }

    private val dataStore = FakeDataStore()
    private val store = TokenStore(dataStore, FakeTokenCipher())

    // ---- GCM framing ----

    @Test
    fun `packGcm prefixes iv and unpackGcm round-trips`() {
        val iv = ByteArray(AesGcmTokenCipher.IV_LENGTH) { it.toByte() }
        val ciphertext = byteArrayOf(10, 20, 30)

        val packed = packGcm(iv, ciphertext)

        assertEquals(AesGcmTokenCipher.IV_LENGTH + ciphertext.size, packed.size)
        assertArrayEquals(iv, packed.copyOfRange(0, AesGcmTokenCipher.IV_LENGTH))
        val (unpackedIv, unpackedCt) = unpackGcm(packed)
        assertArrayEquals(iv, unpackedIv)
        assertArrayEquals(ciphertext, unpackedCt)
    }

    @Test
    fun `packGcm rejects non-12-byte iv and unpackGcm rejects short input`() {
        assertThrows(IllegalArgumentException::class.java) {
            packGcm(ByteArray(8), byteArrayOf(1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            unpackGcm(ByteArray(AesGcmTokenCipher.IV_LENGTH))
        }
    }

    // ---- TokenStore over fake store ----

    @Test
    fun `readTokens returns null on empty store`() = runTest {
        assertNull(store.readTokens())
    }

    @Test
    fun `saveTokens persists encrypted values and readTokens round-trips`() = runTest {
        store.saveTokens(
            access = "access-jwt-123",
            accessExpiresAtEpochMs = 1_700_000_000_000L,
            refresh = "refresh-opaque-456",
        )

        // Stored prefs hold Base64 ciphertext — never the plaintext token.
        val prefs = dataStore.data.first()
        val rawAccess = prefs[stringPreferencesKey("access_token")]
        assertNotEquals(null, rawAccess)
        assertNotEquals("access-jwt-123", rawAccess)
        Base64.getDecoder().decode(rawAccess!!) // throws if not valid Base64

        val tokens = store.readTokens()
        assertEquals("access-jwt-123", tokens?.accessToken)
        assertEquals(1_700_000_000_000L, tokens?.accessTokenExpiresAtEpochMs)
        assertEquals("refresh-opaque-456", tokens?.refreshToken)
        assertFalse(tokens?.isAccessExpired(nowEpochMs = 1_600_000_000_000L) ?: true)
    }

    @Test
    fun `hasValidRefresh maps stored refresh-token presence`() = runTest {
        store.hasValidRefresh.test {
            assertFalse(awaitItem())

            store.saveTokens("a", 1L, "r")
            assertTrue(awaitItem())

            store.clear()
            assertFalse(awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `clear removes the session`() = runTest {
        store.saveTokens("a", 1L, "r")
        store.clear()
        assertNull(store.readTokens())
    }
}
