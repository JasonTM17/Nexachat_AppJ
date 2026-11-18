package com.nexachat.core.database;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import androidx.annotation.NonNull;
import com.nexachat.core.storage.TokenCipher;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Objects;

/**
 * The SQLCipher passphrase for the Room database, sealed at rest with the same
 * AndroidKeyStore AES/GCM {@link TokenCipher} that already protects session tokens and
 * the E2EE private key (see {@code AesGcmTokenCipher} / {@code DeviceKeyStore}). No new
 * keystore mechanism is introduced.
 *
 * <p>A random 32-byte raw key is generated once and persisted only in its encrypted
 * (sealed) form, Base64-wrapped in a private SharedPreferences file; the plaintext
 * passphrase never touches disk. SQLCipher consumes the 32 bytes directly as a raw key
 * ({@code PRAGMA key = "x'<64 hex chars>'"}), so no PBKDF2 passphrase stretching is
 * involved and the full 256 bits of entropy are used.
 *
 * <p>Failure mode: if the keystore key is unavailable (evicted/wiped), unsealing throws
 * and the app fails closed. A replacement passphrase is deliberately never minted
 * silently — that would orphan the existing encrypted history.
 */
public final class DatabasePassphraseStore {

    /** Raw SQLCipher key length in bytes (256-bit key, hex-encoded by SQLCipher). */
    static final int PASSPHRASE_BYTES = 32;

    private static final String PREFS_NAME = "nexachat_db_passphrase";
    private static final String KEY_SEALED_PASSPHRASE = "sealed_passphrase";

    private final Context context;
    private final TokenCipher cipher;

    public DatabasePassphraseStore(@NonNull Context context, @NonNull TokenCipher cipher) {
        this.context = Objects.requireNonNull(context, "context");
        this.cipher = Objects.requireNonNull(cipher, "cipher");
    }

    /**
     * The raw 32-byte database key, minting and sealing one on first call.
     *
     * @throws IllegalStateException if a stored passphrase cannot be unsealed (keystore
     *         key unavailable) or a fresh one cannot be sealed; never returns a
     *         wrong-length key.
     */
    @NonNull
    public byte[] passphrase() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String sealed = prefs.getString(KEY_SEALED_PASSPHRASE, null);
        if (sealed != null) {
            byte[] passphrase;
            try {
                passphrase = cipher.decrypt(Base64.decode(sealed, Base64.NO_WRAP));
            } catch (GeneralSecurityException | IOException | RuntimeException failure) {
                throw new IllegalStateException(
                        "Unable to unseal the database passphrase; the AndroidKeyStore key is "
                                + "unavailable, so the encrypted database cannot be opened",
                        failure);
            }
            if (passphrase.length != PASSPHRASE_BYTES) {
                throw new IllegalStateException(
                        "Stored database passphrase has unexpected length: " + passphrase.length);
            }
            return passphrase;
        }
        byte[] fresh = new byte[PASSPHRASE_BYTES];
        new SecureRandom().nextBytes(fresh);
        try {
            // commit() rather than apply(): the seal must be durable before the encrypted
            // database is created with this passphrase, or a crash in between could
            // orphan the database.
            boolean stored = prefs.edit()
                    .putString(KEY_SEALED_PASSPHRASE,
                            Base64.encodeToString(cipher.encrypt(fresh), Base64.NO_WRAP))
                    .commit();
            if (!stored) {
                throw new IllegalStateException(
                        "Failed to persist the sealed database passphrase");
            }
        } catch (GeneralSecurityException | IOException | RuntimeException failure) {
            throw new IllegalStateException("Unable to seal the database passphrase", failure);
        }
        return fresh;
    }
}
