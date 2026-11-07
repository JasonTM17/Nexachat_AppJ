package com.nexachat.core.storage;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.datastore.preferences.core.MutablePreferences;
import androidx.datastore.preferences.core.Preferences;
import androidx.datastore.preferences.core.PreferencesKeys;
import androidx.datastore.rxjava3.RxDataStore;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Session token persistence. Preferences store Base64 of {@code IV || AES-GCM
 * ciphertext} for both opaque tokens; only the expiry timestamp remains plain.
 * The preference keys and encryption framing are kept stable so existing
 * encrypted values remain readable.
 *
 * <p>RxDataStore performs reads and writes asynchronously on its configured IO
 * scheduler. The API exposes RxJava 3 streams and never blocks the caller.
 */
@Singleton
public final class TokenStore {
    private static final Preferences.Key<String> ACCESS_TOKEN =
            PreferencesKeys.stringKey("access_token");
    private static final Preferences.Key<Long> ACCESS_EXPIRES_AT =
            PreferencesKeys.longKey("access_expires_at");
    private static final Preferences.Key<String> REFRESH_TOKEN =
            PreferencesKeys.stringKey("refresh_token");

    private final RxDataStore<Preferences> dataStore;
    private final TokenCipher cipher;

    @Inject
    public TokenStore(
            @TokenPreferences @NonNull RxDataStore<Preferences> dataStore,
            @NonNull TokenCipher cipher) {
        this.dataStore = Objects.requireNonNull(dataStore, "dataStore");
        this.cipher = Objects.requireNonNull(cipher, "cipher");
    }

    /** Persists all three token preferences atomically without blocking. */
    @NonNull
    public Completable saveTokens(
            @NonNull String access, long accessExpiresAtEpochMs, @NonNull String refresh) {
        return dataStore.updateDataAsync(
                        preferences ->
                                Single.fromCallable(
                                        () -> {
                                            MutablePreferences updated =
                                                    preferences.toMutablePreferences();
                                            updated.set(
                                                    ACCESS_TOKEN,
                                                    TokenEncoding.b64(
                                                            cipher.encrypt(
                                                                    access.getBytes(
                                                                            StandardCharsets.UTF_8))));
                                            updated.set(ACCESS_EXPIRES_AT, accessExpiresAtEpochMs);
                                            updated.set(
                                                    REFRESH_TOKEN,
                                                    TokenEncoding.b64(
                                                            cipher.encrypt(
                                                                    refresh.getBytes(
                                                                            StandardCharsets.UTF_8))));
                                            return (Preferences) updated;
                                        }))
                .ignoreElement();
    }

    /** Empty when tokens are absent or either encrypted token cannot decrypt. */
    @NonNull
    public Maybe<Tokens> readTokens() {
        return dataStore.data().firstElement().flatMap(preferences ->
                Maybe.fromCallable(() -> toTokens(preferences)));
    }

    /** Removes the entire session atomically without blocking. */
    @NonNull
    public Completable clear() {
        return dataStore.updateDataAsync(
                        preferences ->
                                Single.fromCallable(
                                        () -> {
                                            MutablePreferences updated =
                                                    preferences.toMutablePreferences();
                                            updated.remove(ACCESS_TOKEN);
                                            updated.remove(ACCESS_EXPIRES_AT);
                                            updated.remove(REFRESH_TOKEN);
                                            return (Preferences) updated;
                                        }))
                .ignoreElement();
    }

    /**
     * Emits true only while all three preferences exist and both token values
     * decrypt. Corrupt or device-inaccessible ciphertext is treated as logged
     * out; the server remains authoritative for token validity.
     */
    @NonNull
    public Flowable<Boolean> hasValidRefresh() {
        return dataStore.data()
                .map(preferences -> toTokens(preferences) != null)
                .distinctUntilChanged();
    }

    @NonNull
    public Flowable<Boolean> getHasValidRefresh() {
        return hasValidRefresh();
    }

    @Nullable
    private Tokens toTokens(@NonNull Preferences preferences) {
        String access = decryptString(preferences.get(ACCESS_TOKEN));
        Long expiresAt = preferences.get(ACCESS_EXPIRES_AT);
        String refresh = decryptString(preferences.get(REFRESH_TOKEN));
        if (access == null || expiresAt == null || refresh == null) return null;
        return new Tokens(access, expiresAt, refresh);
    }

    @Nullable
    private String decryptString(@Nullable String encoded) {
        if (encoded == null) return null;
        try {
            return new String(
                    cipher.decrypt(TokenEncoding.b64decode(encoded)), StandardCharsets.UTF_8);
        } catch (Exception invalidCiphertext) {
            return null;
        }
    }
}
