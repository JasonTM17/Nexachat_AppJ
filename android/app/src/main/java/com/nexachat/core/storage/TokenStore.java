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
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
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
    private static final Preferences.Key<String> FCM_DEVICE_TOKEN =
            PreferencesKeys.stringKey("fcm_device_token");
    private static final Preferences.Key<String> PENDING_DEVICE_UNREGISTER_TOKEN =
            PreferencesKeys.stringKey("pending_device_unregister_token");
    private static final Preferences.Key<String> PENDING_DEVICE_UNREGISTER_USER_ID =
            PreferencesKeys.stringKey("pending_device_unregister_user_id");
    private static final Preferences.Key<Set<String>> SYNC_CURSOR_USER_IDS =
            PreferencesKeys.stringSetKey("sync_server_time_user_ids");
    private static final String SYNC_CURSOR_PREFIX = "sync_server_time_";

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

    /** Persists the FCM registration token encrypted with the session token cipher. */
    @NonNull
    public Completable saveFcmDeviceToken(@NonNull String token) {
        Objects.requireNonNull(token, "token");
        return dataStore.updateDataAsync(preferences ->
                        Single.fromCallable(() -> {
                            MutablePreferences updated = preferences.toMutablePreferences();
                            updated.set(FCM_DEVICE_TOKEN, encryptString(token));
                            return (Preferences) updated;
                        }))
                .ignoreElement();
    }

    /** Empty when no FCM registration token has been received on this install. */
    @NonNull
    public Maybe<String> readFcmDeviceToken() {
        return dataStore.data().firstElement().flatMap(preferences ->
                Maybe.fromCallable(() -> decryptString(preferences.get(FCM_DEVICE_TOKEN))));
    }

    /** Retains a failed logout unregister request for a later authenticated retry. */
    @NonNull
    public Completable savePendingDeviceTokenUnregister(
            @NonNull String token, @Nullable String ownerUserId) {
        Objects.requireNonNull(token, "token");
        return dataStore.updateDataAsync(preferences ->
                        Single.fromCallable(() -> {
                            MutablePreferences updated = preferences.toMutablePreferences();
                            updated.set(PENDING_DEVICE_UNREGISTER_TOKEN, encryptString(token));
                            if (ownerUserId == null || ownerUserId.isBlank()) {
                                updated.remove(PENDING_DEVICE_UNREGISTER_USER_ID);
                            } else {
                                updated.set(PENDING_DEVICE_UNREGISTER_USER_ID, ownerUserId);
                            }
                            return (Preferences) updated;
                        }))
                .ignoreElement();
    }

    @NonNull
    public Maybe<PendingDeviceTokenUnregister> readPendingDeviceTokenUnregister() {
        return dataStore.data().firstElement().flatMap(preferences ->
                Maybe.fromCallable(() -> {
                    String token = decryptString(preferences.get(PENDING_DEVICE_UNREGISTER_TOKEN));
                    return token == null
                            ? null
                            : new PendingDeviceTokenUnregister(
                                    token, preferences.get(PENDING_DEVICE_UNREGISTER_USER_ID));
                }));
    }

    /** Clears only the request that completed, preserving a newer logout request. */
    @NonNull
    public Completable clearPendingDeviceTokenUnregister(
            @NonNull String expectedToken, @Nullable String expectedOwnerUserId) {
        Objects.requireNonNull(expectedToken, "expectedToken");
        return dataStore.updateDataAsync(preferences ->
                        Single.fromCallable(() -> {
                            MutablePreferences updated = preferences.toMutablePreferences();
                            String currentToken = decryptString(
                                    updated.get(PENDING_DEVICE_UNREGISTER_TOKEN));
                            String currentOwner = updated.get(PENDING_DEVICE_UNREGISTER_USER_ID);
                            if (expectedToken.equals(currentToken)
                                    && Objects.equals(expectedOwnerUserId, currentOwner)) {
                                updated.remove(PENDING_DEVICE_UNREGISTER_TOKEN);
                                updated.remove(PENDING_DEVICE_UNREGISTER_USER_ID);
                            }
                            return (Preferences) updated;
                        }))
                .ignoreElement();
    }

    /** Reads the last successful delta-sync server time for one authenticated user. */
    @NonNull
    public Maybe<String> readSyncServerTime(@NonNull String userId) {
        Preferences.Key<String> key = syncCursorKey(userId);
        return dataStore.data().firstElement().flatMap(preferences ->
                Maybe.fromCallable(() -> preferences.get(key)));
    }

    /** Persists a delta-sync cursor in a user-namespaced preference. */
    @NonNull
    public Completable saveSyncServerTime(@NonNull String userId, @NonNull String serverTime) {
        Preferences.Key<String> key = syncCursorKey(userId);
        Objects.requireNonNull(serverTime, "serverTime");
        return dataStore.updateDataAsync(preferences ->
                        Single.fromCallable(() -> {
                            MutablePreferences updated = preferences.toMutablePreferences();
                            updated.set(key, serverTime);
                            Set<String> userIds = new HashSet<>(
                                    updated.get(SYNC_CURSOR_USER_IDS) == null
                                            ? Collections.emptySet()
                                            : updated.get(SYNC_CURSOR_USER_IDS));
                            userIds.add(userId);
                            updated.set(SYNC_CURSOR_USER_IDS, userIds);
                            return (Preferences) updated;
                        }))
                .ignoreElement();
    }

    @NonNull
    public Completable clearSyncServerTime(@NonNull String userId) {
        Preferences.Key<String> key = syncCursorKey(userId);
        return dataStore.updateDataAsync(preferences ->
                        Single.fromCallable(() -> {
                            MutablePreferences updated = preferences.toMutablePreferences();
                            updated.remove(key);
                            Set<String> userIds = new HashSet<>(
                                    updated.get(SYNC_CURSOR_USER_IDS) == null
                                            ? Collections.emptySet()
                                            : updated.get(SYNC_CURSOR_USER_IDS));
                            userIds.remove(userId);
                            if (userIds.isEmpty()) {
                                updated.remove(SYNC_CURSOR_USER_IDS);
                            } else {
                                updated.set(SYNC_CURSOR_USER_IDS, userIds);
                            }
                            return (Preferences) updated;
                        }))
                .ignoreElement();
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
                                            Set<String> userIds = updated.get(SYNC_CURSOR_USER_IDS);
                                            if (userIds != null) {
                                                for (String userId : userIds) {
                                                    updated.remove(syncCursorKey(userId));
                                                }
                                            }
                                            updated.remove(SYNC_CURSOR_USER_IDS);
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

    @NonNull
    private String encryptString(@NonNull String value)
            throws GeneralSecurityException, IOException {
        return TokenEncoding.b64(cipher.encrypt(value.getBytes(StandardCharsets.UTF_8)));
    }

    @NonNull
    private static Preferences.Key<String> syncCursorKey(@NonNull String userId) {
        if (userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        return PreferencesKeys.stringKey(SYNC_CURSOR_PREFIX + userId);
    }

    /** Pending unregister data is kept encrypted and omitted from string representations. */
    public static final class PendingDeviceTokenUnregister {
        @NonNull private final String token;
        @Nullable private final String ownerUserId;

        private PendingDeviceTokenUnregister(
                @NonNull String token, @Nullable String ownerUserId) {
            this.token = token;
            this.ownerUserId = ownerUserId;
        }

        @NonNull public String getToken() {
            return token;
        }

        @Nullable public String getOwnerUserId() {
            return ownerUserId;
        }
    }
}
