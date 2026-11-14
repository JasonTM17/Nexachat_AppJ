package com.nexachat.core.crypto;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.nexachat.core.storage.TokenCipher;
import java.security.GeneralSecurityException;
import java.util.Objects;
import java.util.UUID;

/**
 * Per-user E2EE identity: a random UUID deviceId plus a raw 32-byte X25519 key
 * pair whose private half is sealed at rest with the Android-Keystore AES-GCM
 * cipher that already protects session tokens. The plaintext private key never
 * touches disk; on a decrypt failure the store wipes itself so the next
 * registration generates a fresh key pair.
 */
public final class DeviceKeyStore {

    private static final String PREFS_PREFIX = "nexachat_e2ee_device_";
    private static final String PREFS_CURRENT = "nexachat_e2ee_current";
    private static final String KEY_CURRENT_USER = "user_id";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_SEALED_PRIVATE = "sealed_private";
    private static final String KEY_PUBLIC = "public";

    private final Context context;
    private final TokenCipher cipher;

    public DeviceKeyStore(@NonNull Context context, @NonNull TokenCipher cipher) {
        this.context = Objects.requireNonNull(context, "context");
        this.cipher = Objects.requireNonNull(cipher, "cipher");
    }

    /**
     * The user whose identity this installation last loaded. Lets the sync
     * ingest path decrypt without an async token lookup; cleared on every
     * account boundary so a stale id can never decrypt for the wrong user.
     */
    @Nullable public String currentUserId() {
        return context.getSharedPreferences(PREFS_CURRENT, Context.MODE_PRIVATE)
                .getString(KEY_CURRENT_USER, null);
    }

    /** Remembers {@code userId} as the active identity owner. */
    public void rememberCurrentUser(@NonNull String userId) {
        context.getSharedPreferences(PREFS_CURRENT, Context.MODE_PRIVATE)
                .edit().putString(KEY_CURRENT_USER, userId).apply();
    }

    /** Forgets the active identity owner; called at every session end. */
    public void forgetCurrentUser() {
        context.getSharedPreferences(PREFS_CURRENT, Context.MODE_PRIVATE)
                .edit().clear().apply();
    }

    private SharedPreferences prefs(@NonNull String userId) {
        return context.getSharedPreferences(PREFS_PREFIX + userId, Context.MODE_PRIVATE);
    }

    /** The already-stored device id, or null; unlike {@link #deviceId} never mints one. */
    @Nullable public String storedDeviceId(@NonNull String userId) {
        return prefs(userId).getString(KEY_DEVICE_ID, null);
    }

    /** Stable per-installation device id for this user; generated once. */
    @NonNull public String deviceId(@NonNull String userId) {
        SharedPreferences prefs = prefs(userId);
        String existing = prefs.getString(KEY_DEVICE_ID, null);
        if (existing != null && !existing.isBlank()) return existing;
        String created = UUID.randomUUID().toString();
        prefs.edit().putString(KEY_DEVICE_ID, created).apply();
        return created;
    }

    /** Existing identity, or null when this user has never registered here. */
    @Nullable public Identity identity(@NonNull String userId) {
        SharedPreferences prefs = prefs(userId);
        String deviceId = prefs.getString(KEY_DEVICE_ID, null);
        String publicB64 = prefs.getString(KEY_PUBLIC, null);
        String sealedB64 = prefs.getString(KEY_SEALED_PRIVATE, null);
        if (deviceId == null || publicB64 == null || sealedB64 == null) return null;
        try {
            byte[] privateKey = cipher.decrypt(Base64.decode(sealedB64, Base64.NO_WRAP));
            byte[] publicKey = Base64.decode(publicB64, Base64.NO_WRAP);
            if (privateKey.length != E2eeCrypto.KEY_BYTES
                    || publicKey.length != E2eeCrypto.KEY_BYTES) {
                clear(userId);
                return null;
            }
            return new Identity(deviceId, publicKey, privateKey);
        } catch (GeneralSecurityException | java.io.IOException | RuntimeException failure) {
            // Corrupt or key-store-evicted blob: drop the identity so a fresh pair is issued.
            clear(userId);
            return null;
        }
    }

    /** Stores the key pair sealed; deviceId must already be assigned. */
    public void put(@NonNull String userId, @NonNull String deviceId,
            @NonNull E2eeCrypto.KeyPair keyPair) {
        try {
            prefs(userId).edit()
                    .putString(KEY_DEVICE_ID, deviceId)
                    .putString(KEY_PUBLIC, Base64.encodeToString(
                            keyPair.publicKey(), Base64.NO_WRAP))
                    .putString(KEY_SEALED_PRIVATE, Base64.encodeToString(
                            cipher.encrypt(keyPair.privateKey()), Base64.NO_WRAP))
                    .apply();
        } catch (GeneralSecurityException | java.io.IOException failure) {
            throw new IllegalStateException("Unable to seal the E2EE private key", failure);
        }
    }

    /** Full wipe; called by account-boundary cleanup and corrupt-blob recovery. */
    public void clear(@NonNull String userId) {
        prefs(userId).edit().clear().apply();
    }

    /** Loaded identity: stable device id plus raw X25519 key material. */
    public static final class Identity {
        @NonNull private final String deviceId;
        private final @NonNull byte[] publicKey;
        private final @NonNull byte[] privateKey;

        public Identity(@NonNull String deviceId, @NonNull byte[] publicKey,
                @NonNull byte[] privateKey) {
            this.deviceId = deviceId;
            this.publicKey = publicKey;
            this.privateKey = privateKey;
        }

        @NonNull public String getDeviceId() { return deviceId; }
        public @NonNull byte[] getPublicKey() { return publicKey; }
        public @NonNull byte[] getPrivateKey() { return privateKey; }

        /** Raw public key as unpadded base64url — the server registration shape. */
        @NonNull public String publicKeyBase64Url() {
            return E2eeCrypto.b64url(publicKey);
        }
    }
}
