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
import com.nexachat.data.TimeAndJwt;
import com.squareup.moshi.Moshi;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Owns the device's E2EE identity for the signed-in user and the per-
 * conversation member-device list used by the encrypted send path.
 *
 * <p>The private key never leaves {@link DeviceKeyStore} unsealed; registration
 * on session-available is idempotent (PUT upsert), and the member-device list
 * is cached briefly and invalidated on conversation-updated events and on the
 * server's {@code E2EE_STALE_DEVICES} send error.
 */
@Singleton
public final class E2eeSession {

    /** How long a cached member-device list is reused before a re-fetch. */
    static final long DEVICES_TTL_MS = TimeUnit.MINUTES.toMillis(5);
    private static final int REGISTER_MAX_ATTEMPTS = 3;
    private static final long REGISTER_BACKOFF_MS = 2_000L;

    private final E2eeApi api;
    private final DeviceKeyStore keyStore;
    private final TokenStore tokenStore;
    private final Moshi moshi;
    private final E2eeCrypto crypto;
    private final Map<String, CachedDevices> deviceCache = new ConcurrentHashMap<>();

    @Inject
    public E2eeSession(@NonNull E2eeApi api, @NonNull DeviceKeyStore keyStore,
            @NonNull TokenStore tokenStore, @NonNull Moshi moshi) {
        this(api, keyStore, tokenStore, moshi, new E2eeCrypto());
    }

    E2eeSession(@NonNull E2eeApi api, @NonNull DeviceKeyStore keyStore,
            @NonNull TokenStore tokenStore, @NonNull Moshi moshi,
            @NonNull E2eeCrypto crypto) {
        this.api = Objects.requireNonNull(api, "api");
        this.keyStore = Objects.requireNonNull(keyStore, "keyStore");
        this.tokenStore = Objects.requireNonNull(tokenStore, "tokenStore");
        this.moshi = Objects.requireNonNull(moshi, "moshi");
        this.crypto = Objects.requireNonNull(crypto, "crypto");
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
                    keyStore.rememberCurrentUser(userId);
                    return ensureIdentity(userId)
                            .flatMapCompletable(identity -> register(userId, identity, false));
                })
                .retryWhen(errors -> errors.zipWith(
                                Flowable.range(1, REGISTER_MAX_ATTEMPTS),
                                (error, attempt) -> attempt)
                        .flatMap(attempt -> Flowable.timer(
                                REGISTER_BACKOFF_MS * attempt, TimeUnit.MILLISECONDS)))
                .subscribeOn(Schedulers.io());
    }

    /**
     * Registers the stored identity; a {@code E2EE_DEVICE_CONFLICT} (this
     * deviceId already bound to another account's key) triggers exactly one
     * regeneration with a fresh deviceId, never an unbounded loop.
     */
    @NonNull
    private Completable register(@NonNull String userId, @NonNull Identity identity,
            boolean retried) {
        return ApiResult.safeApiCall(api.registerDevice(identity.getDeviceId(),
                        new RegisterDeviceRequest(identity.publicKeyBase64Url())))
                .flatMapCompletable(result -> {
                    if (result instanceof ApiResult.HttpError<?> http
                            && "E2EE_DEVICE_CONFLICT".equals(http.getErrorCode())
                            && !retried) {
                        // clear() drops the deviceId + key material, so
                        // ensureIdentity mints a fresh pair and id.
                        keyStore.clear(userId);
                        return ensureIdentity(userId)
                                .flatMapCompletable(fresh -> register(userId, fresh, true));
                    }
                    return Completable.complete();
                });
    }

    /** Loads the stored identity or generates, seals, and returns a new one. */
    @NonNull
    public Single<Identity> ensureIdentity(@NonNull String userId) {
        return Single.fromCallable(() -> {
            Identity existing = keyStore.identity(userId);
            if (existing != null) return existing;
            E2eeCrypto.KeyPair pair = E2eeCrypto.generateDeviceKeyPair();
            String deviceId = keyStore.deviceId(userId);
            keyStore.put(userId, deviceId, pair);
            return new Identity(deviceId, pair.publicKey(), pair.privateKey());
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
                .andThen(Completable.fromAction(() -> {
                    keyStore.clear(userId);
                    keyStore.forgetCurrentUser();
                }))
                .subscribeOn(Schedulers.io());
    }

    /** Wipes whichever identity is current; safe no-op when none exists. */
    public void wipeCurrent() {
        String userId = keyStore.currentUserId();
        if (userId != null) wipeLocal(userId);
        deviceCache.clear();
    }

    /** Local-only wipe for the account-deletion / clear-all boundary. */
    public void wipeLocal(@NonNull String userId) {
        keyStore.clear(userId);
        keyStore.forgetCurrentUser();
        deviceCache.clear();
    }

    /** This device's stable id for the signed-in user, or null when no identity exists yet. */
    @Nullable
    public String currentDeviceId() {
        String userId = keyStore.currentUserId();
        return userId == null ? null : keyStore.deviceId(userId);
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
     * {@code conversationId}, including this device. Caller must have checked
     * the conversation's e2ee flag already.
     */
    @NonNull
    public Single<E2eeCrypto.EncryptedMessage> encryptFor(
            @NonNull String conversationId, @NonNull String plaintext,
            @NonNull String senderId, @NonNull String clientMessageId) {
        return e2eeState(conversationId).map(state -> {
            List<DevicePublicKey> recipients = new ArrayList<>(state.getDevices().size());
            for (MemberDeviceDto device : state.getDevices()) {
                if (device.getDeviceId() == null || device.getPublicKey() == null) continue;
                recipients.add(new DevicePublicKey(device.getDeviceId(),
                        E2eeCrypto.b64urlDecode(device.getPublicKey())));
            }
            if (recipients.isEmpty()) {
                throw new E2eeException("E2EE_STALE_DEVICES");
            }
            try {
                return crypto.encrypt(plaintext, conversationId, senderId, clientMessageId,
                        recipients);
            } catch (GeneralSecurityException failure) {
                throw new E2eeException("E2EE_CRYPTO");
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
                if (envelope.getDeviceId() != null && envelope.getWrappedKey() != null) {
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
                            if (envelope.getDeviceId() != null && envelope.getWrappedKey() != null) {
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
