package com.nexachat.core.storage;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import androidx.datastore.preferences.core.Preferences;
import androidx.datastore.preferences.core.PreferencesKeys;
import androidx.datastore.preferences.core.MutablePreferences;
import androidx.datastore.rxjava3.RxDataStore;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.functions.Function;
import io.reactivex.rxjava3.processors.BehaviorProcessor;
import io.reactivex.rxjava3.observers.TestObserver;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
import java.security.KeyException;
import java.util.Base64;
import org.junit.Before;
import org.junit.Test;

/**
 * JVM coverage for token persistence. The fake cipher exercises the real GCM
 * framing without pretending to test AndroidKeyStore or Cipher.
 */
public final class TokenStoreTest {

    /** XOR cipher wearing the real GCM framing; exercises framing, not AES. */
    private static final class FakeTokenCipher implements TokenCipher {
        private static final int XOR = 0x5A;
        private final byte[] fakeIv = new byte[AesGcmTokenCipher.IV_LENGTH];

        FakeTokenCipher() {
            for (int i = 0; i < fakeIv.length; i++) {
                fakeIv[i] = (byte) (i + 1);
            }
        }

        @Override
        public byte[] encrypt(byte[] plaintext) {
            byte[] ciphertext = new byte[plaintext.length];
            for (int i = 0; i < plaintext.length; i++) {
                ciphertext[i] = (byte) (plaintext[i] ^ XOR);
            }
            return AesGcmTokenCipher.packGcm(fakeIv, ciphertext);
        }

        @Override
        public byte[] decrypt(byte[] packed) {
            byte[] ciphertext = AesGcmTokenCipher.unpackGcm(packed).getSecond();
            byte[] plaintext = new byte[ciphertext.length];
            for (int i = 0; i < ciphertext.length; i++) {
                plaintext[i] = (byte) (ciphertext[i] ^ XOR);
            }
            return plaintext;
        }
    }

    /** In-memory RxDataStore stub with the same update semantics as DataStore. */
    private static final class FakeDataStore {
        private final BehaviorProcessor<Preferences> state =
                BehaviorProcessor.createDefault(new MutablePreferences());
        private final RxDataStore<Preferences> dataStore = mockDataStore(state);

        private Flowable<Preferences> data() {
            return state;
        }

        private RxDataStore<Preferences> rxDataStore() {
            return dataStore;
        }
    }

    private BehaviorProcessor<Preferences> state;
    private FakeDataStore dataStore;
    private TokenStore store;

    @Before
    public void setUp() {
        dataStore = new FakeDataStore();
        state = (BehaviorProcessor<Preferences>) dataStore.data();
        store = new TokenStore(dataStore.rxDataStore(), new FakeTokenCipher());
    }

    // ---- GCM framing ----

    @Test
    public void packGcmPrefixesIvAndUnpackGcmRoundTrips() {
        byte[] iv = new byte[AesGcmTokenCipher.IV_LENGTH];
        for (int i = 0; i < iv.length; i++) {
            iv[i] = (byte) i;
        }
        byte[] ciphertext = new byte[] {10, 20, 30};

        byte[] packed = AesGcmTokenCipher.packGcm(iv, ciphertext);

        assertEquals(AesGcmTokenCipher.IV_LENGTH + ciphertext.length, packed.length);
        assertArrayEquals(iv, java.util.Arrays.copyOfRange(packed, 0, AesGcmTokenCipher.IV_LENGTH));
        kotlin.Pair<byte[], byte[]> unpacked = AesGcmTokenCipher.unpackGcm(packed);
        assertArrayEquals(iv, unpacked.getFirst());
        assertArrayEquals(ciphertext, unpacked.getSecond());
    }

    @Test
    public void packGcmRejectsNon12ByteIvAndUnpackGcmRejectsShortInput() {
        org.junit.Assert.assertThrows(
                IllegalArgumentException.class,
                () -> AesGcmTokenCipher.packGcm(new byte[8], new byte[] {1}));
        org.junit.Assert.assertThrows(
                IllegalArgumentException.class,
                () -> AesGcmTokenCipher.unpackGcm(new byte[AesGcmTokenCipher.IV_LENGTH]));
    }

    // ---- TokenStore over the fake store ----

    @Test
    public void readTokensReturnsEmptyOnEmptyStore() {
        store.readTokens().test().assertComplete().assertNoValues();
    }

    @Test
    public void saveTokensPersistsEncryptedValuesAndReadTokensRoundTrips() {
        TestObserver<Tokens> observer =
                store.saveTokens("access-jwt-123", 1_700_000_000_000L, "refresh-opaque-456")
                        .andThen(store.readTokens().toSingle())
                        .test();
        observer.assertComplete().assertValueCount(1);

        Preferences prefs = state.getValue();
        String rawAccess = prefs.get(PreferencesKeys.stringKey("access_token"));
        assertNotEquals(null, rawAccess);
        assertNotEquals("access-jwt-123", rawAccess);
        Base64.getDecoder().decode(rawAccess);

        Tokens tokens = observer.values().get(0);
        assertEquals("access-jwt-123", tokens.getAccessToken());
        assertEquals(1_700_000_000_000L, tokens.getAccessTokenExpiresAtEpochMs());
        assertEquals("refresh-opaque-456", tokens.getRefreshToken());
        assertFalse(tokens.isAccessExpired(1_600_000_000_000L));
    }

    @Test
    public void hasValidRefreshMapsStoredRefreshTokenPresence() {
        TestSubscriber<Boolean> observer = store.hasValidRefresh().test();
        observer.assertValueAt(0, false);

        store.saveTokens("a", 1L, "r").test().assertComplete();
        observer.assertValueAt(1, true);

        store.clear().test().assertComplete();
        observer.assertValueAt(2, false);
        observer.cancel();
    }

    /** Ciphertext survives a keystore reset; the key that could read it does not. */
    private static final class UndecryptableCipher implements TokenCipher {
        @Override
        public byte[] encrypt(byte[] plaintext) {
            return AesGcmTokenCipher.packGcm(
                    new byte[AesGcmTokenCipher.IV_LENGTH], plaintext);
        }

        @Override
        public byte[] decrypt(byte[] packed) throws KeyException {
            throw new KeyException("key permanently invalidated");
        }
    }

    @Test
    public void hasValidRefreshTreatsUndecryptableSessionAsLoggedOut() {
        TokenStore resetStore = new TokenStore(dataStore.rxDataStore(), new UndecryptableCipher());
        resetStore.saveTokens("a", 1L, "r").test().assertComplete();
        resetStore.readTokens().test().assertComplete().assertNoValues();

        resetStore.hasValidRefresh().test().assertValue(false);
    }

    @Test
    public void clearRemovesTheSession() {
        store.saveTokens("a", 1L, "r").andThen(store.clear()).test().assertComplete();
        store.readTokens().test().assertComplete().assertNoValues();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static RxDataStore<Preferences> mockDataStore(
            BehaviorProcessor<Preferences> state) {
        RxDataStore<Preferences> dataStore = mock(RxDataStore.class);
        when(dataStore.data()).thenReturn(state);
        try {
            when(dataStore.updateDataAsync(any(Function.class)))
                    .thenAnswer(
                            invocation -> {
                                Function<Preferences, Single<Preferences>> update =
                                        invocation.getArgument(0);
                                return update.apply(state.getValue()).doOnSuccess(state::onNext);
                            });
        } catch (Exception error) {
            throw new AssertionError(error);
        }
        return dataStore;
    }
}
