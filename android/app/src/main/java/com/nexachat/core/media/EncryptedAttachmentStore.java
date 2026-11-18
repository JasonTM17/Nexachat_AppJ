package com.nexachat.core.media;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * AES/GCM file store for downloaded attachments, keyed from AndroidKeyStore.
 *
 * <p>Wire format: {@code IV (12 bytes) || ciphertext+tag} — the same framing as
 * {@code AesGcmTokenCipher}. A fresh random IV is generated per file by
 * {@link Cipher} on encrypt and prepended to the output. GCM authenticates the
 * ciphertext, so a tampered or truncated file fails decryption instead of
 * yielding garbage.
 *
 * <p>The key lives in the (hardware-backed, where available) AndroidKeyStore
 * under {@link #KEY_ALIAS} and never leaves it. Encryption and decryption are
 * streaming, so a 25 MiB attachment never has to be held in memory.
 *
 * <p>The static {@code SecretKey} overloads exist so JVM unit tests can exercise
 * the real cipher path without the AndroidKeyStore provider.
 */
public final class EncryptedAttachmentStore {

    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final int KEY_SIZE_BITS = 256;
    private static final int READ_BUFFER = 64 * 1024;

    public static final String KEY_ALIAS = "NexaChatAttachmentKey";
    public static final String TRANSFORMATION = "AES/GCM/NoPadding";
    public static final int IV_LENGTH = 12;
    public static final int GCM_TAG_BITS = 128;

    @Nullable private final SecretKey fixedKey;

    /** Keystore-backed store; the key is resolved lazily on first use. */
    public EncryptedAttachmentStore() {
        this.fixedKey = null;
    }

    private EncryptedAttachmentStore(@NonNull SecretKey fixedKey) {
        this.fixedKey = Objects.requireNonNull(fixedKey, "fixedKey");
    }

    /** Test-only store backed by an explicit key instead of AndroidKeyStore. */
    @NonNull
    public static EncryptedAttachmentStore withSecretKey(@NonNull SecretKey key) {
        return new EncryptedAttachmentStore(key);
    }

    /**
     * Encrypts {@code plaintext} into {@code target}, aborting with
     * {@link OversizeException} once more than {@code maxBytes} plaintext bytes
     * have been read. On any failure the partial {@code target} is deleted.
     */
    public void writeEncrypted(@NonNull File target, @NonNull InputStream plaintext, long maxBytes)
            throws IOException {
        try {
            writeEncrypted(secretKey(), target, plaintext, maxBytes);
        } catch (GeneralSecurityException exception) {
            throw new IOException("attachment cipher unavailable", exception);
        }
    }

    /** Decrypts an {@link #writeEncrypted} file into {@code target}. */
    public void decryptTo(@NonNull File encrypted, @NonNull File target) throws IOException {
        try {
            decryptTo(secretKey(), encrypted, target);
        } catch (GeneralSecurityException exception) {
            throw new IOException("attachment decrypt failed", exception);
        }
    }

    public static void writeEncrypted(
            @NonNull SecretKey key,
            @NonNull File target,
            @NonNull InputStream plaintext,
            long maxBytes) throws IOException {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] iv = cipher.getIV();
            if (iv == null || iv.length != IV_LENGTH) {
                throw new IOException("cipher did not produce a " + IV_LENGTH + "-byte IV");
            }
            try (OutputStream raw = new FileOutputStream(target)) {
                raw.write(iv);
                try (CipherOutputStream out = new CipherOutputStream(raw, cipher)) {
                    byte[] chunk = new byte[READ_BUFFER];
                    long total = 0L;
                    int read;
                    while ((read = plaintext.read(chunk)) >= 0) {
                        total += read;
                        if (total > maxBytes) {
                            throw new OversizeException();
                        }
                        out.write(chunk, 0, read);
                    }
                }
            }
        } catch (GeneralSecurityException exception) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            throw new IOException("attachment encrypt failed", exception);
        } catch (IOException | RuntimeException exception) {
            // Never leave a partial ciphertext that a later decrypt would reject.
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            throw exception;
        }
    }

    public static void decryptTo(@NonNull SecretKey key, @NonNull File encrypted, @NonNull File target)
            throws IOException {
        try (InputStream raw = new FileInputStream(encrypted)) {
            byte[] iv = new byte[IV_LENGTH];
            int filled = 0;
            while (filled < IV_LENGTH) {
                int read = raw.read(iv, filled, IV_LENGTH - filled);
                if (read < 0) {
                    throw new IOException("encrypted attachment truncated: no IV");
                }
                filled += read;
            }
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            try (CipherInputStream in = new CipherInputStream(raw, cipher);
                    OutputStream out = new FileOutputStream(target)) {
                byte[] chunk = new byte[READ_BUFFER];
                int read;
                while ((read = in.read(chunk)) >= 0) {
                    out.write(chunk, 0, read);
                }
            }
        } catch (GeneralSecurityException exception) {
            // AEADBadTagException and friends are GeneralSecurityExceptions, not
            // IOExceptions; surface them as IOException so callers need one catch.
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            throw new IOException("attachment decrypt failed", exception);
        } catch (IOException | RuntimeException exception) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            throw exception;
        }
    }

    @NonNull
    private SecretKey secretKey() throws GeneralSecurityException, IOException {
        if (fixedKey != null) {
            return fixedKey;
        }
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
        keyStore.load(null);
        KeyStore.Entry entry = keyStore.getEntry(KEY_ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }
        KeyGenerator generator =
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        KeyGenParameterSpec spec =
                new KeyGenParameterSpec.Builder(
                                KEY_ALIAS,
                                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(KEY_SIZE_BITS)
                        .setUserAuthenticationRequired(false)
                        .build();
        generator.init(spec);
        return generator.generateKey();
    }

    /** Thrown when the plaintext stream exceeds the configured byte ceiling. */
    public static final class OversizeException extends IOException {
        private static final long serialVersionUID = 1L;
    }
}
