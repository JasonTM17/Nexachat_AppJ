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
    private static final Preferences.Key<Long> RECALL_WINDOW_SECONDS =
            PreferencesKeys.longKey("recall_window_seconds");
    private static final Preferences.Key<Boolean> GROUP_ADMIN_RECALL_AFTER_WINDOW =
            PreferencesKeys.booleanKey("group_admin_recall_after_window");
    private static final Preferences.Key<Set<String>> LOCALLY_HIDDEN_MESSAGE_IDS =
            PreferencesKeys.stringSetKey("locally_hidden_message_ids");
    private static final Preferences.Key<String> FCM_DEVICE_TOKEN =
            PreferencesKeys.stringKey("fcm_device_token");
    private static final Preferences.Key<String> PENDING_DEVICE_UNREGISTER_TOKEN =
            PreferencesKeys.stringKey("pending_device_unregister_token");
    private static final Preferences.Key<String> PENDING_DEVICE_UNREGISTER_USER_ID =
            PreferencesKeys.stringKey("pending_device_unregister_user_id");
    private static final Preferences.Key<Set<String>> SYNC_CURSOR_USER_IDS =
            PreferencesKeys.stringSetKey("sync_server_time_user_ids");
    private static final String SYNC_CURSOR_PREFIX = "sync_server_time_";
    private static final Preferences.Key<Long> SESSION_EPOCH =
            PreferencesKeys.longKey("session_epoch");
    private static final Preferences.Key<String> SESSION_LIFECYCLE =
            PreferencesKeys.stringKey("session_lifecycle");
    private static final Preferences.Key<Long> PENDING_REFRESH_EPOCH =
            PreferencesKeys.longKey("pending_refresh_epoch");
    private static final Preferences.Key<String> PENDING_REFRESH_KEY =
            PreferencesKeys.stringKey("pending_refresh_key");
    private static final Preferences.Key<String> PENDING_PREDECESSOR =
            PreferencesKeys.stringKey("pending_refresh_predecessor");
    private static final String LIFECYCLE_ACTIVE = "active";
    private static final String LIFECYCLE_CLOSING = "closing";
    private static final String LIFECYCLE_CLOSED = "closed";

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
        return persistSession(access, accessExpiresAtEpochMs, refresh, null);
    }

    /** Atomically persists session credentials and the additive server recall configuration. */
    @NonNull
    public Completable saveSession(
            @NonNull String access,
            long accessExpiresAtEpochMs,
            @NonNull String refresh,
            @NonNull RecallWindowConfig recallWindowConfig) {
        return persistSession(access, accessExpiresAtEpochMs, refresh,
                Objects.requireNonNull(recallWindowConfig, "recallWindowConfig"));
    }

    /** Emits the durable session revision and lifecycle. */
    @NonNull
    public Single<SessionSnapshot> readSessionSnapshot() {
        return dataStore.data().firstOrError()
                .map(preferences -> snapshotFrom(preferences));
    }

    /**
     * Initializes session metadata for installs that stored tokens before epoch
     * tracking existed.
     */
    @NonNull
    public Single<Long> bootstrapLegacySessionIfNeeded() {
        return dataStore.updateDataAsync(preferences ->
                        Single.fromCallable(() -> {
                            MutablePreferences updated = preferences.toMutablePreferences();
                            if (updated.get(SESSION_EPOCH) != null) {
                                return preferences;
                            }
                            if (toTokens(preferences) == null) {
                                updated.set(SESSION_EPOCH, 0L);
                                updated.set(SESSION_LIFECYCLE, LIFECYCLE_CLOSED);
                                return (Preferences) updated;
                            }
                            updated.set(SESSION_EPOCH, 1L);
                            updated.set(SESSION_LIFECYCLE, LIFECYCLE_ACTIVE);
                            return (Preferences) updated;
                        }))
                .map(prefs -> Objects.requireNonNullElse(prefs.get(SESSION_EPOCH), 0L));
    }

    /**
     * Opens a newly authenticated session with a monotonic epoch and clears any
     * stale pending rotation from a prior account.
     */
    @NonNull
    public Single<Long> establishNewSession(
            @NonNull String access,
            long accessExpiresAtEpochMs,
            @NonNull String refresh,
            @NonNull RecallWindowConfig recallWindowConfig) {
        return dataStore.updateDataAsync(preferences ->
                        Single.fromCallable(() -> {
                            MutablePreferences updated = preferences.toMutablePreferences();
                            long nextEpoch = Objects.requireNonNullElse(
                                    updated.get(SESSION_EPOCH), 0L) + 1L;
                            writeTokens(
                                    updated,
                                    access,
                                    accessExpiresAtEpochMs,
                                    refresh,
                                    recallWindowConfig);
                            updated.set(SESSION_EPOCH, nextEpoch);
                            updated.set(SESSION_LIFECYCLE, LIFECYCLE_ACTIVE);
                            clearPendingRefresh(updated);
                            return (Preferences) updated;
                        }))
                .map(prefs -> Objects.requireNonNull(prefs.get(SESSION_EPOCH)));
    }

    /**
     * Marks the session as closing and advances the durable revision before slow
     * logout cleanup runs.
     */
    @NonNull
    public Single<Long> reserveSessionClosing(long expectedEpoch) {
        return dataStore.updateDataAsync(preferences ->
                        Single.fromCallable(() -> {
                            MutablePreferences updated = preferences.toMutablePreferences();
                            long current = Objects.requireNonNullElse(
                                    updated.get(SESSION_EPOCH), 0L);
                            if (current != expectedEpoch) {
                                return preferences;
                            }
                            long closingEpoch = expectedEpoch + 1L;
                            updated.set(SESSION_EPOCH, closingEpoch);
                            updated.set(SESSION_LIFECYCLE, LIFECYCLE_CLOSING);
                            return (Preferences) updated;
                        }))
                .map(prefs -> Objects.requireNonNullElse(prefs.get(SESSION_EPOCH), 0L));
    }

    /**
     * Keeps stored credentials but closes the session so refresh dispatch stops
     * after a terminal replay-recovery failure. Clears any pending rotation marker.
     */
    @NonNull
    public Completable markSessionReauthRequired(long expectedEpoch) {
        return dataStore.updateDataAsync(preferences ->
                        Single.fromCallable(() -> {
                            MutablePreferences updated = preferences.toMutablePreferences();
                            long current = Objects.requireNonNullElse(
                                    updated.get(SESSION_EPOCH), 0L);
                            if (current != expectedEpoch) {
                                return preferences;
                            }
                            updated.set(SESSION_LIFECYCLE, LIFECYCLE_CLOSED);
                            clearPendingRefresh(updated);
                            return (Preferences) updated;
                        }))
                .ignoreElement();
    }

    /** True when encrypted refresh credentials exist regardless of session lifecycle. */
    @NonNull
    public Single<Boolean> hasStoredRefreshCredential() {
        return dataStore.data()
                .firstElement()
                .map(preferences -> toTokens(preferences) != null)
                .defaultIfEmpty(false);
    }

    /** Finalizes durable closed state after local cleanup completes. */
    @NonNull
    public Completable markSessionClosed(long expectedEpoch) {
        return dataStore.updateDataAsync(preferences ->
                        Single.fromCallable(() -> {
                            MutablePreferences updated = preferences.toMutablePreferences();
                            long current = Objects.requireNonNullElse(
                                    updated.get(SESSION_EPOCH), 0L);
                            if (current != expectedEpoch) {
                                return preferences;
                            }
                            updated.set(SESSION_LIFECYCLE, LIFECYCLE_CLOSED);
                            clearPendingRefresh(updated);
                            return (Preferences) updated;
                        }))
                .ignoreElement();
    }

    /** Empty unless a quarantined rotation marker exists for the active revision. */
    @NonNull
    public Maybe<PendingRefresh> readPendingRefresh() {
        return dataStore.data().firstElement().flatMap(preferences ->
                Maybe.fromCallable(() -> pendingFrom(preferences)));
    }

    /**
     * Persists a request key before dispatch. Returns false when the expected epoch
     * is no longer active or another key is already quarantined.
     */
    @NonNull
    public Single<Boolean> reservePendingRefresh(
            long expectedEpoch,
            @NonNull String predecessorRefreshToken,
            @NonNull String requestKey) {
        Objects.requireNonNull(predecessorRefreshToken, "predecessorRefreshToken");
        Objects.requireNonNull(requestKey, "requestKey");
        return dataStore.updateDataAsync(preferences ->
                        Single.fromCallable(() -> {
                            MutablePreferences updated = preferences.toMutablePreferences();
                            if (!canReservePending(updated, expectedEpoch)) {
                                return preferences;
                            }
                            String existingKey = decryptString(updated.get(PENDING_REFRESH_KEY));
                            Long existingEpoch = updated.get(PENDING_REFRESH_EPOCH);
                            if (existingKey != null
                                    && (!existingKey.equals(requestKey)
                                            || !Long.valueOf(expectedEpoch).equals(existingEpoch))) {
                                return preferences;
                            }
                            updated.set(PENDING_REFRESH_EPOCH, expectedEpoch);
                            updated.set(PENDING_REFRESH_KEY, encryptString(requestKey));
                            updated.set(PENDING_PREDECESSOR, encryptString(predecessorRefreshToken));
                            return (Preferences) updated;
                        }))
                .map(this::pendingReservedFor)
                .map(reserved -> reserved != null
                        && reserved.getSessionEpoch() == expectedEpoch
                        && requestKey.equals(reserved.getRequestKey()));
    }

    /**
     * Saves successor credentials and removes the pending marker in one compare step.
     */
    @NonNull
    public Completable commitRefreshRotation(
            long expectedEpoch,
            @NonNull String expectedRequestKey,
            @NonNull String access,
            long accessExpiresAtEpochMs,
            @NonNull String refresh,
            @NonNull RecallWindowConfig recallWindowConfig) {
        Objects.requireNonNull(expectedRequestKey, "expectedRequestKey");
        return dataStore.updateDataAsync(preferences ->
                        Single.fromCallable(() -> {
                            MutablePreferences updated = preferences.toMutablePreferences();
                            PendingRefresh pending = pendingFrom(updated);
                            if (pending == null
                                    || pending.getSessionEpoch() != expectedEpoch
                                    || !pending.getRequestKey().equals(expectedRequestKey)) {
                                return preferences;
                            }
                            if (!Objects.requireNonNullElse(
                                            updated.get(SESSION_EPOCH), 0L)
                                    .equals(expectedEpoch)
                                    || !LIFECYCLE_ACTIVE.equals(
                                            updated.get(SESSION_LIFECYCLE))) {
                                return preferences;
                            }
                            writeTokens(
                                    updated,
                                    access,
                                    accessExpiresAtEpochMs,
                                    refresh,
                                    recallWindowConfig);
                            clearPendingRefresh(updated);
                            return (Preferences) updated;
                        }))
                .ignoreElement();
    }

    private Completable persistSession(
            @NonNull String access,
            long accessExpiresAtEpochMs,
            @NonNull String refresh,
            @Nullable RecallWindowConfig recallWindowConfig) {
        return dataStore.updateDataAsync(preferences ->
                        Single.fromCallable(() -> {
                            MutablePreferences updated = preferences.toMutablePreferences();
                            writeTokens(updated, access, accessExpiresAtEpochMs, refresh,
                                    recallWindowConfig);
                            return (Preferences) updated;
                        }))
                .ignoreElement();
    }

    private void writeTokens(
            @NonNull MutablePreferences updated,
            @NonNull String access,
            long accessExpiresAtEpochMs,
            @NonNull String refresh,
            @Nullable RecallWindowConfig recallWindowConfig)
            throws GeneralSecurityException, IOException {
        updated.set(
                ACCESS_TOKEN,
                TokenEncoding.b64(
                        cipher.encrypt(access.getBytes(StandardCharsets.UTF_8))));
        updated.set(ACCESS_EXPIRES_AT, accessExpiresAtEpochMs);
        updated.set(
                REFRESH_TOKEN,
                TokenEncoding.b64(
                        cipher.encrypt(refresh.getBytes(StandardCharsets.UTF_8))));
        if (recallWindowConfig != null) {
            updated.set(RECALL_WINDOW_SECONDS, recallWindowConfig.getRecallWindowSeconds());
            updated.set(
                    GROUP_ADMIN_RECALL_AFTER_WINDOW,
                    recallWindowConfig.mayGroupAdminRecallAfterWindow());
        }
    }

    /** Emits the persisted policy, or the documented 24-hour legacy fallback. */
    @NonNull
    public Flowable<RecallWindowConfig> observeRecallWindowConfig() {
        return dataStore.data()
                .map(preferences -> new RecallWindowConfig(
                        valueOrDefault(preferences.get(RECALL_WINDOW_SECONDS)),
                        Boolean.TRUE.equals(preferences.get(GROUP_ADMIN_RECALL_AFTER_WINDOW))))
                .distinctUntilChanged();
    }

    /** Persists a locally hidden message id; it is cleared when this session logs out. */
    @NonNull
    public Completable hideMessageForMe(@NonNull String messageId) {
        Objects.requireNonNull(messageId, "messageId");
        if (messageId.isBlank()) return Completable.complete();
        return dataStore.updateDataAsync(preferences -> Single.fromCallable(() -> {
                    MutablePreferences updated = preferences.toMutablePreferences();
                    Set<String> ids = updated.get(LOCALLY_HIDDEN_MESSAGE_IDS);
                    Set<String> next = new HashSet<>(ids == null ? Collections.emptySet() : ids);
                    next.add(messageId);
                    updated.set(LOCALLY_HIDDEN_MESSAGE_IDS, next);
                    return (Preferences) updated;
                }))
                .ignoreElement();
    }

    /** Emits the local hide set so chat and pin projections can filter it after relaunch. */
    @NonNull
    public Flowable<Set<String>> observeLocallyHiddenMessageIds() {
        return dataStore.data()
                .map(preferences -> {
                    Set<String> ids = preferences.get(LOCALLY_HIDDEN_MESSAGE_IDS);
                    return ids == null
                            ? Collections.<String>emptySet()
                            : Collections.unmodifiableSet(new HashSet<>(ids));
                })
                .distinctUntilChanged();
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
                                            updated.remove(RECALL_WINDOW_SECONDS);
                                            updated.remove(GROUP_ADMIN_RECALL_AFTER_WINDOW);
                                            updated.remove(LOCALLY_HIDDEN_MESSAGE_IDS);
                                            Set<String> userIds = updated.get(SYNC_CURSOR_USER_IDS);
                                            if (userIds != null) {
                                                for (String userId : userIds) {
                                                    updated.remove(syncCursorKey(userId));
                                                }
                                            }
                                            updated.remove(SYNC_CURSOR_USER_IDS);
                                            clearPendingRefresh(updated);
                                            Long epoch = updated.get(SESSION_EPOCH);
                                            if (epoch != null) {
                                                updated.set(SESSION_LIFECYCLE, LIFECYCLE_CLOSED);
                                            }
                                            return (Preferences) updated;
                                        }))
                .ignoreElement();
    }

    private static long valueOrDefault(@Nullable Long seconds) {
        return seconds == null ? RecallWindowConfig.DEFAULT_RECALL_WINDOW_SECONDS : seconds;
    }

    /**
     * Emits true only while all three preferences exist and both token values
     * decrypt. Corrupt or device-inaccessible ciphertext is treated as logged
     * out; the server remains authoritative for token validity.
     */
    @NonNull
    public Flowable<Boolean> hasValidRefresh() {
        return dataStore.data()
                .map(preferences -> {
                    if (toTokens(preferences) == null) {
                        return false;
                    }
                    String lifecycle = preferences.get(SESSION_LIFECYCLE);
                    return lifecycle == null || LIFECYCLE_ACTIVE.equals(lifecycle);
                })
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

    @NonNull
    private SessionSnapshot snapshotFrom(@NonNull Preferences preferences) {
        long epoch = Objects.requireNonNullElse(preferences.get(SESSION_EPOCH), 0L);
        String raw = preferences.get(SESSION_LIFECYCLE);
        SessionSnapshot.Lifecycle lifecycle = switch (raw == null ? "" : raw) {
            case LIFECYCLE_ACTIVE -> SessionSnapshot.Lifecycle.ACTIVE;
            case LIFECYCLE_CLOSING -> SessionSnapshot.Lifecycle.CLOSING;
            default -> SessionSnapshot.Lifecycle.CLOSED;
        };
        return new SessionSnapshot(epoch, lifecycle);
    }

    @Nullable
    private PendingRefresh pendingFrom(@NonNull Preferences preferences) {
        Long epoch = preferences.get(PENDING_REFRESH_EPOCH);
        String key = decryptString(preferences.get(PENDING_REFRESH_KEY));
        String predecessor = decryptString(preferences.get(PENDING_PREDECESSOR));
        if (epoch == null || key == null || predecessor == null) {
            return null;
        }
        return new PendingRefresh(key, predecessor, epoch);
    }

    @Nullable
    private PendingRefresh pendingReservedFor(@NonNull Preferences preferences) {
        String key = decryptString(preferences.get(PENDING_REFRESH_KEY));
        if (key == null) {
            return null;
        }
        return pendingFrom(preferences);
    }

    private static boolean canReservePending(
            @NonNull MutablePreferences updated, long expectedEpoch) {
        if (!Objects.requireNonNullElse(updated.get(SESSION_EPOCH), 0L).equals(expectedEpoch)) {
            return false;
        }
        return LIFECYCLE_ACTIVE.equals(updated.get(SESSION_LIFECYCLE));
    }

    private static void clearPendingRefresh(@NonNull MutablePreferences updated) {
        updated.remove(PENDING_REFRESH_EPOCH);
        updated.remove(PENDING_REFRESH_KEY);
        updated.remove(PENDING_PREDECESSOR);
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
