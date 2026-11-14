package com.nexachat.core.crypto;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.nexachat.core.crypto.DeviceKeyStore.Identity;
import com.nexachat.core.crypto.E2eeCrypto.DevicePublicKey;
import com.nexachat.core.crypto.E2eeCrypto.Envelope;
import com.nexachat.core.network.ApiResult;
import com.nexachat.core.network.E2eeApi;
import com.nexachat.core.network.dto.E2eeDeviceDtos.ConversationE2eeDto;
import com.nexachat.core.network.dto.E2eeDeviceDtos.MemberDeviceDto;
import com.nexachat.core.network.dto.E2eeDeviceDtos.RegisterDeviceRequest;
import com.nexachat.core.network.dto.E2eeDeviceDtos.SetE2eeRequest;
import com.nexachat.core.network.dto.E2eeEnvelopeDto;
import com.nexachat.core.storage.TokenStore;
import com.nexachat.core.storage.Tokens;
import com.nexachat.data.TimeAndJwt;
import com.squareup.moshi.Moshi;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Owns the device's E2EE identity for the signed-in user and the per-
 * conversation member-device list used by the encrypted send path.
 *
 * <p>The private key never leaves {@link DeviceKeyStore} unsealed. Registration
 * is required before any encrypted send: only an HTTP success marks this
 * device ready, transient transport and 5xx failures retry at most three
 * total calls (2s then 4s, then a terminal error), a device-id conflict
 * regenerates key material exactly once, and any other 4xx is terminal.
 * Readiness is memoized per user+device+key for the session so steady-state
 * sends never re-register (the server budgets registrations per hour).
 *
 * <p>A monotonically increasing session-generation fence retires every
 * in-flight operation on wipe, revocation, or a new session boundary, so a
 * delayed retry can never recreate keys or mark readiness for a signed-out
 * account. The member-device list is cached briefly and invalidated on
 * conversation-updated events, on {@code E2EE_STALE_DEVICES}, and whenever a
 * fresh registration lands.
 */
@Singleton
public final class E2eeSession {

    /** How long a cached member-device list is reused before a re-fetch. */
    static final long DEVICES_TTL_MS = TimeUnit.MINUTES.toMillis(5);
    private static final int REGISTER_MAX_ATTEMPTS = 3;
    private static final long REGISTER_BACKOFF_MS = 2_000L;
    /** Fence value meaning "no session boundary applies to this operation". */
    private static final int NO_FENCE = -1;

    private final E2eeApi api;
    private final DeviceKeyStore keyStore;
    private final TokenStore tokenStore;
    private final Moshi moshi;
    private final E2eeCrypto crypto;
    private final Scheduler delayScheduler;
    private final Object identityLock = new Object();
    private final AtomicInteger sessionGeneration = new AtomicInteger();
    private final AtomicReference<String> registrationProof = new AtomicReference<>();
    private final Map<String, CachedDevices> deviceCache = new ConcurrentHashMap<>();

    @Inject
    public E2eeSession(@NonNull E2eeApi api, @NonNull DeviceKeyStore keyStore,
            @NonNull TokenStore tokenStore, @NonNull Moshi moshi) {
        this(api, keyStore, tokenStore, moshi, new E2eeCrypto());
    }

    E2eeSession(@NonNull E2eeApi api, @NonNull DeviceKeyStore keyStore,
            @NonNull TokenStore tokenStore, @NonNull Moshi moshi,
            @NonNull E2eeCrypto crypto) {
        this(api, keyStore, tokenStore, moshi, crypto, Schedulers.computation());
    }

    /** Test seam: registration backoff runs on the supplied scheduler. */
    E2eeSession(@NonNull E2eeApi api, @NonNull DeviceKeyStore keyStore,
            @NonNull TokenStore tokenStore, @NonNull Moshi moshi,
            @NonNull E2eeCrypto crypto, @NonNull Scheduler delayScheduler) {
        this.api = Objects.requireNonNull(api, "api");
        this.keyStore = Objects.requireNonNull(keyStore, "keyStore");
        this.tokenStore = Objects.requireNonNull(tokenStore, "tokenStore");
        this.moshi = Objects.requireNonNull(moshi, "moshi");
        this.crypto = Objects.requireNonNull(crypto, "crypto");
        this.delayScheduler = Objects.requireNonNull(delayScheduler, "delayScheduler");
    }

    /**
     * Ensures this installation has an identity and registers it with the
     * server. Called once per session boundary; errors are the caller's to
     * swallow (registration retries on the next boundary or first send).
     */
    @NonNull
    public Completable onSessionAvailable() {
        return tokenStore.readTokens()
                .flatMapCompletable(tokens -> {
                    String userId = TimeAndJwt.userIdFromJwt(tokens.getAccessToken(), moshi);
                    if (userId == null) {
                        return Completable.error(new E2eeException("E2EE_NO_SESSION"));
                    }
                    // A new session boundary retires every in-flight operation.
                    int fence = sessionGeneration.incrementAndGet();
                    String previous = keyStore.currentUserId();
                    if (previous != null && !previous.equals(userId)) {
                        registrationProof.set(null);
                        deviceCache.clear();
                    }
                    keyStore.rememberCurrentUser(userId);
                    return ensureRegisteredIdentity(userId, fence).ignoreElement();
                })
                .subscribeOn(Schedulers.io());
    }

    /**
     * Resolves the stored identity (generating one when absent) and confirms
     * it with the server. Success is memoized for the session so callers can
     * invoke this before every encrypted send without paying the server's
     * registration rate budget again.
     */
    @NonNull
    private Single<Identity> ensureRegisteredIdentity(@NonNull String userId, int fence) {
        return ensureIdentity(userId, fence)
                .flatMap(identity -> {
                    if (isStale(fence)) {
                        return Single.error(staleSessionError());
                    }
                    if (registrationMarker(userId, identity).equals(registrationProof.get())) {
                        return Single.just(identity);
                    }
                    return registerDeviceAttempts(userId, identity, false, 1, fence)
                            .map(confirmed -> {
                                if (isStale(fence)) {
                                    throw staleSessionError();
                                }
                                // New or regenerated key material just landed on
                                // the directory: cached member lists may predate
                                // this device, so drop them all.
                                deviceCache.clear();
                                registrationProof.set(registrationMarker(userId, confirmed));
                                return confirmed;
                            });
                });
    }

    /**
     * Performs one logical device registration. Only {@code ApiResult.Success}
     * confirms the identity. {@code E2EE_DEVICE_CONFLICT} regenerates the key
     * pair and id exactly once (the second conflict is terminal). Transport
     * failures and 5xx retry with 2s/4s backoff up to
     * {@value #REGISTER_MAX_ATTEMPTS} total calls; every other 4xx is a
     * terminal {@link E2eeException} carrying the server error code.
     */
    @NonNull
    private Single<Identity> registerDeviceAttempts(@NonNull String userId,
            @NonNull Identity identity, boolean regenerated, int attempt, int fence) {
        if (isStale(fence)) {
            return Single.error(staleSessionError());
        }
        // Each attempt builds its own Call inside defer so a retry never
        // replays an already-executed request object.
        return Single.defer(() -> ApiResult.safeApiCall(api.registerDevice(
                        identity.getDeviceId(),
                        new RegisterDeviceRequest(identity.publicKeyBase64Url()))))
                .flatMap(result -> {
                    if (isStale(fence)) {
                        return Single.error(staleSessionError());
                    }
                    if (result instanceof ApiResult.Success) {
                        return Single.just(identity);
                    }
                    if (result instanceof ApiResult.HttpError<?> http) {
                        if ("E2EE_DEVICE_CONFLICT".equals(http.getErrorCode())
                                && !regenerated) {
                            return regenerateIdentity(userId, fence)
                                    .flatMap(fresh -> registerDeviceAttempts(
                                            userId, fresh, true, 1, fence));
                        }
                        if (http.getStatus() >= 500 && attempt < REGISTER_MAX_ATTEMPTS) {
                            return delayFor(attempt).andThen(registerDeviceAttempts(
                                    userId, identity, regenerated, attempt + 1, fence));
                        }
                        return Single.error(new E2eeException(http.getErrorCode() != null
                                ? http.getErrorCode() : "E2EE_HTTP_" + http.getStatus()));
                    }
                    // ApiResult.NetworkError: transient transport failure.
                    if (attempt < REGISTER_MAX_ATTEMPTS) {
                        return delayFor(attempt).andThen(registerDeviceAttempts(
                                userId, identity, regenerated, attempt + 1, fence));
                    }
                    return Single.error(new E2eeException("E2EE_NETWORK"));
                });
    }

    @NonNull
    private Completable delayFor(int attempt) {
        return Completable.timer(REGISTER_BACKOFF_MS * attempt, TimeUnit.MILLISECONDS,
                delayScheduler);
    }

    /** Loads the stored identity or generates, seals, and returns a new one. */
    @NonNull
    public Single<Identity> ensureIdentity(@NonNull String userId) {
        return ensureIdentity(userId, NO_FENCE);
    }

    /**
     * Fenced variant of {@link #ensureIdentity(String)}: read, generate, and
     * persist happen inside one lock so concurrent first uses mint exactly one
     * key pair, and a stale session never recreates keys after a wipe.
     */
    @NonNull
    private Single<Identity> ensureIdentity(@NonNull String userId, int fence) {
        return Single.fromCallable(() -> {
            synchronized (identityLock) {
                if (isStale(fence)) {
                    throw staleSessionError();
                }
                return ensureIdentityLocked(userId);
            }
        });
    }

    @NonNull
    private Identity ensureIdentityLocked(@NonNull String userId)
            throws GeneralSecurityException {
        Identity existing = keyStore.identity(userId);
        if (existing != null) return existing;
        E2eeCrypto.KeyPair pair = E2eeCrypto.generateDeviceKeyPair();
        String deviceId = keyStore.deviceId(userId);
        keyStore.put(userId, deviceId, pair);
        return new Identity(deviceId, pair.publicKey(), pair.privateKey());
    }

    /** Drops the stored pair and mints a fresh one under the identity lock. */
    @NonNull
    private Single<Identity> regenerateIdentity(@NonNull String userId, int fence) {
        return Single.fromCallable(() -> {
            synchronized (identityLock) {
                if (isStale(fence)) {
                    throw staleSessionError();
                }
                keyStore.clear(userId);
                return ensureIdentityLocked(userId);
            }
        });
    }

    /** Best-effort server revocation plus local wipe; used at plain sign-out. */
    @NonNull
    public Completable revokeCurrentDevice(@NonNull String userId) {
        return Maybe.fromCallable(() -> keyStore.identity(userId))
                .flatMapCompletable(identity -> ApiResult.safeApiCall(
                                api.revokeDevice(identity.getDeviceId()))
                        .ignoreElement()
                        .onErrorComplete())
                .andThen(Completable.fromAction(() -> wipeIdentity(userId)))
                .subscribeOn(Schedulers.io());
    }

    /** Wipes whichever identity is current; safe no-op when none exists. */
    public void wipeCurrent() {
        String userId = keyStore.currentUserId();
        if (userId != null) {
            wipeIdentity(userId);
        } else {
            sessionGeneration.incrementAndGet();
            registrationProof.set(null);
            deviceCache.clear();
        }
    }

    /** Local-only wipe for the account-deletion / clear-all boundary. */
    public void wipeLocal(@NonNull String userId) {
        wipeIdentity(userId);
    }

    /**
     * Session-boundary wipe: bumps the generation fence so in-flight work
     * dies, clears readiness proof and cached device lists, then deletes the
     * sealed identity under the same lock that serializes minting.
     */
    private void wipeIdentity(@NonNull String userId) {
        sessionGeneration.incrementAndGet();
        registrationProof.set(null);
        synchronized (identityLock) {
            keyStore.clear(userId);
        }
        keyStore.forgetCurrentUser();
        deviceCache.clear();
    }

    /** This device's stable id for the signed-in user, or null when no identity exists yet. */
    @Nullable
    public String currentDeviceId() {
        String userId = keyStore.currentUserId();
        return userId == null ? null : keyStore.storedDeviceId(userId);
    }

    /** The signed-in account id backing the device identity, or null before first registration. */
    @Nullable
    public String currentUserId() {
        return keyStore.currentUserId();
    }

    /** Drops the cached member-device list for one conversation. */
    public void invalidateDevices(@NonNull String conversationId) {
        deviceCache.remove(conversationId);
    }

    /** Drops all cached member-device lists (account boundary). */
    public void invalidateAllDevices() {
        deviceCache.clear();
    }

    /**
     * Fetches (or returns the cached) E2EE state + member devices for one
     * conversation. Cache misses and expired entries hit the network.
     */
    @NonNull
    public Single<ConversationE2eeDto> e2eeState(@NonNull String conversationId) {
        CachedDevices cached = deviceCache.get(conversationId);
        if (cached != null && cached.expiresAtMs > System.currentTimeMillis()) {
            return Single.just(cached.state);
        }
        return ApiResult.safeApiCall(api.getE2ee(conversationId))
                .flatMap(result -> {
                    if (result instanceof ApiResult.Success<ConversationE2eeDto> success) {
                        deviceCache.put(conversationId, new CachedDevices(success.getBody(),
                                System.currentTimeMillis() + DEVICES_TTL_MS));
                        return Single.just(success.getBody());
                    }
                    if (result instanceof ApiResult.HttpError<?> http) {
                        return Single.error(new E2eeException(
                                http.getErrorCode() != null ? http.getErrorCode() : "E2EE_HTTP_"
                                        + http.getStatus()));
                    }
                    return Single.error(new E2eeException("E2EE_NETWORK"));
                })
                .subscribeOn(Schedulers.io());
    }

    /** Server toggle for the conversation screen; refreshes the cache on success. */
    @NonNull
    public Single<ConversationE2eeDto> setE2ee(@NonNull String conversationId, boolean enabled) {
        return ApiResult.safeApiCall(api.setE2ee(conversationId, new SetE2eeRequest(enabled)))
                .flatMap(result -> {
                    if (result instanceof ApiResult.Success<ConversationE2eeDto> success) {
                        invalidateDevices(conversationId);
                        return Single.just(success.getBody());
                    }
                    if (result instanceof ApiResult.HttpError<?> http) {
                        return Single.error(new E2eeException(
                                http.getErrorCode() != null ? http.getErrorCode() : "E2EE_HTTP_"
                                        + http.getStatus()));
                    }
                    return Single.error(new E2eeException("E2EE_NETWORK"));
                })
                .subscribeOn(Schedulers.io());
    }

    /**
     * Encrypts {@code plaintext} for every active member device of
     * {@code conversationId}, including this device. The token's user must
     * equal {@code senderId} and this device must be registered and listed in
     * the member device set; otherwise the send fails with an
     * {@link E2eeException} and never falls back to plaintext.
     */
    @NonNull
    public Single<E2eeCrypto.EncryptedMessage> encryptFor(
            @NonNull String conversationId, @NonNull String plaintext,
            @NonNull String senderId, @NonNull String clientMessageId) {
        return tokenStore.readTokens()
                .switchIfEmpty(Single.<Tokens>error(new E2eeException("E2EE_NO_SESSION")))
                .flatMap(tokens -> {
                    String userId = TimeAndJwt.userIdFromJwt(tokens.getAccessToken(), moshi);
                    if (userId == null) {
                        return Single.error(new E2eeException("E2EE_NO_SESSION"));
                    }
                    if (!userId.equals(senderId)) {
                        return Single.error(new E2eeException("E2EE_SENDER_MISMATCH"));
                    }
                    int fence = sessionGeneration.get();
                    return ensureRegisteredIdentity(userId, fence)
                            .flatMap(identity -> encryptForConversation(conversationId,
                                    plaintext, senderId, clientMessageId, userId,
                                    identity, false));
                })
                .subscribeOn(Schedulers.io());
    }

    /**
     * Encrypts against the member-device snapshot, failing closed:
     * {@code E2EE_NOT_ENABLED} when the conversation is off,
     * {@code E2EE_STALE_DEVICES} when the snapshot is malformed, lacks this
     * device's exact key, or lists no peer device. A snapshot missing the
     * just-registered own device is refetched once before erroring.
     */
    @NonNull
    private Single<E2eeCrypto.EncryptedMessage> encryptForConversation(
            @NonNull String conversationId, @NonNull String plaintext,
            @NonNull String senderId, @NonNull String clientMessageId,
            @NonNull String selfUserId, @NonNull Identity identity, boolean refetched) {
        return e2eeState(conversationId).flatMap(state -> {
            if (!state.isEnabled()) {
                return Single.error(new E2eeException("E2EE_NOT_ENABLED"));
            }
            List<DevicePublicKey> recipients = new ArrayList<>(state.getDevices().size());
            boolean ownListed = false;
            boolean peerListed = false;
            for (MemberDeviceDto device : state.getDevices()) {
                if (device.getUserId() == null || device.getDeviceId() == null
                        || device.getPublicKey() == null) {
                    return Single.<E2eeCrypto.EncryptedMessage>error(
                            new E2eeException("E2EE_STALE_DEVICES"));
                }
                byte[] publicKey;
                try {
                    publicKey = E2eeCrypto.b64urlDecode(device.getPublicKey());
                } catch (RuntimeException malformed) {
                    return Single.<E2eeCrypto.EncryptedMessage>error(
                            new E2eeException("E2EE_STALE_DEVICES"));
                }
                if (publicKey.length != E2eeCrypto.KEY_BYTES) {
                    return Single.<E2eeCrypto.EncryptedMessage>error(
                            new E2eeException("E2EE_STALE_DEVICES"));
                }
                if (device.getDeviceId().equals(identity.getDeviceId())) {
                    ownListed = Arrays.equals(publicKey, identity.getPublicKey());
                }
                if (!selfUserId.equals(device.getUserId())) {
                    peerListed = true;
                }
                recipients.add(new DevicePublicKey(device.getDeviceId(), publicKey));
            }
            if (!ownListed || !peerListed) {
                if (!refetched) {
                    invalidateDevices(conversationId);
                    return encryptForConversation(conversationId, plaintext, senderId,
                            clientMessageId, selfUserId, identity, true);
                }
                return Single.<E2eeCrypto.EncryptedMessage>error(
                        new E2eeException("E2EE_STALE_DEVICES"));
            }
            try {
                return Single.just(crypto.encrypt(plaintext, conversationId, senderId,
                        clientMessageId, recipients));
            } catch (GeneralSecurityException failure) {
                return Single.<E2eeCrypto.EncryptedMessage>error(
                        new E2eeException("E2EE_CRYPTO"));
            }
        });
    }

    /**
     * Synchronous decrypt for the Room-mapping ingest points, where suspending
     * on a token read would block the caller. Uses the identity owner recorded
     * by {@link #onSessionAvailable}; returns empty when no usable identity or
     * any crypto failure — callers then persist the placeholder state.
     */
    @NonNull
    public Optional<String> decryptNow(@NonNull String conversationId,
            @NonNull String senderId, @NonNull String clientMessageId,
            @Nullable String content, @Nullable List<E2eeEnvelopeDto> envelopes) {
        String userId = keyStore.currentUserId();
        if (userId == null) return Optional.empty();
        Identity identity = keyStore.identity(userId);
        if (identity == null) return Optional.empty();
        List<Envelope> parsed = new ArrayList<>(envelopes == null ? 0 : envelopes.size());
        if (envelopes != null) {
            for (E2eeEnvelopeDto envelope : envelopes) {
                if (envelope != null && envelope.getDeviceId() != null
                        && envelope.getWrappedKey() != null) {
                    parsed.add(new Envelope(envelope.getDeviceId(), envelope.getWrappedKey()));
                }
            }
        }
        return crypto.decrypt(content, parsed, identity.getDeviceId(),
                identity.getPrivateKey(), conversationId, senderId, clientMessageId);
    }

    /**
     * Decrypts a server row for the current device. Returns empty when the
     * identity is missing or decryption fails — callers render the placeholder.
     */
    @NonNull
    public Maybe<String> decryptMessage(@NonNull String conversationId,
            @NonNull String senderId, @NonNull String clientMessageId,
            @Nullable String content, @Nullable List<E2eeEnvelopeDto> envelopes) {
        return tokenStore.readTokens()
                .flatMap(tokens -> Maybe.fromCallable(() -> {
                    String userId = TimeAndJwt.userIdFromJwt(tokens.getAccessToken(), moshi);
                    Identity identity = keyStore.identity(userId);
                    if (identity == null) return null;
                    List<Envelope> parsed = new ArrayList<>(
                            envelopes == null ? 0 : envelopes.size());
                    if (envelopes != null) {
                        for (E2eeEnvelopeDto envelope : envelopes) {
                            if (envelope != null && envelope.getDeviceId() != null
                                    && envelope.getWrappedKey() != null) {
                                parsed.add(new Envelope(envelope.getDeviceId(),
                                        envelope.getWrappedKey()));
                            }
                        }
                    }
                    Optional<String> plain = crypto.decrypt(content, parsed,
                            identity.getDeviceId(), identity.getPrivateKey(),
                            conversationId, senderId, clientMessageId);
                    return plain.orElse(null);
                }))
                .subscribeOn(Schedulers.io());
    }

    private boolean isStale(int fence) {
        return fence != NO_FENCE && sessionGeneration.get() != fence;
    }

    @NonNull
    private static E2eeException staleSessionError() {
        return new E2eeException("E2EE_SESSION_RESET");
    }

    @NonNull
    private static String registrationMarker(@NonNull String userId,
            @NonNull Identity identity) {
        return userId + '|' + identity.getDeviceId() + '|' + identity.publicKeyBase64Url();
    }

    /** Error whose message is the server error code, so UI can branch on it. */
    public static final class E2eeException extends RuntimeException {
        public E2eeException(@NonNull String code) {
            super(code);
        }
    }

    private static final class CachedDevices {
        @NonNull final ConversationE2eeDto state;
        final long expiresAtMs;

        CachedDevices(@NonNull ConversationE2eeDto state, long expiresAtMs) {
            this.state = state;
            this.expiresAtMs = expiresAtMs;
        }
    }
}
