package com.nexachat.core.media;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * JVM coverage for the AES/GCM attachment store: round-trip, IV framing and
 * uniqueness, the byte ceiling, and tamper/truncation rejection. Uses an explicit
 * {@link SecretKey} because AndroidKeyStore is not available off-device.
 */
public final class EncryptedAttachmentStoreTest {

    @Rule public final TemporaryFolder temp = new TemporaryFolder();

    private SecretKey key;

    @Before
    public void setUp() throws Exception {
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        key = generator.generateKey();
    }

    @Test
    public void encryptThenDecryptRoundTrips() throws Exception {
        byte[] plaintext = new byte[100_000];
        for (int i = 0; i < plaintext.length; i++) {
            plaintext[i] = (byte) (i * 31);
        }
        File encrypted = temp.newFile("a.enc");
        File decrypted = temp.newFile("a.bin");

        EncryptedAttachmentStore.writeEncrypted(key, encrypted,
                new ByteArrayInputStream(plaintext), plaintext.length);
        EncryptedAttachmentStore.decryptTo(key, encrypted, decrypted);

        assertArrayEquals(plaintext, Files.readAllBytes(decrypted.toPath()));
    }

    @Test
    public void ciphertextIsFramedAsIvPlusTaggedCiphertextAndDiffersFromPlaintext() throws Exception {
        byte[] plaintext = "hello attachment".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        File encrypted = temp.newFile("b.enc");

        EncryptedAttachmentStore.writeEncrypted(key, encrypted,
                new ByteArrayInputStream(plaintext), plaintext.length);

        byte[] stored = Files.readAllBytes(encrypted.toPath());
        // 12-byte IV + plaintext + 16-byte GCM tag.
        assertEquals(EncryptedAttachmentStore.IV_LENGTH + plaintext.length + 16, stored.length);
        byte[] body = Arrays.copyOfRange(stored, EncryptedAttachmentStore.IV_LENGTH, stored.length);
        assertFalse(Arrays.equals(plaintext, body));
    }

    @Test
    public void everyWriteUsesAFreshIv() throws Exception {
        byte[] plaintext = "same bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        File first = temp.newFile("c1.enc");
        File second = temp.newFile("c2.enc");

        EncryptedAttachmentStore.writeEncrypted(key, first,
                new ByteArrayInputStream(plaintext), plaintext.length);
        EncryptedAttachmentStore.writeEncrypted(key, second,
                new ByteArrayInputStream(plaintext), plaintext.length);

        assertNotEquals(
                Arrays.toString(Files.readAllBytes(first.toPath())),
                Arrays.toString(Files.readAllBytes(second.toPath())));
    }

    @Test
    public void streamThatOutgrowsTheCeilingAbortsAndDeletesThePartialFile() throws Exception {
        byte[] plaintext = new byte[1024];
        File encrypted = new File(temp.getRoot(), "d.enc");

        assertThrows(EncryptedAttachmentStore.OversizeException.class, () ->
                EncryptedAttachmentStore.writeEncrypted(key, encrypted,
                        new ByteArrayInputStream(plaintext), 512));

        assertFalse(encrypted.exists());
    }

    @Test
    public void tamperedCiphertextFailsDecryptionAndDeletesTheOutput() throws Exception {
        byte[] plaintext = "integrity matters".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        File encrypted = temp.newFile("e.enc");
        File decrypted = new File(temp.getRoot(), "e.bin");
        EncryptedAttachmentStore.writeEncrypted(key, encrypted,
                new ByteArrayInputStream(plaintext), plaintext.length);

        byte[] stored = Files.readAllBytes(encrypted.toPath());
        stored[stored.length - 1] ^= 0x01;
        Files.write(encrypted.toPath(), stored);

        assertThrows(IOException.class, () ->
                EncryptedAttachmentStore.decryptTo(key, encrypted, decrypted));
        assertFalse(decrypted.exists());
    }

    @Test
    public void truncatedFileWithoutIvIsRejected() throws Exception {
        File encrypted = temp.newFile("f.enc");
        Files.write(encrypted.toPath(), new byte[] {1, 2, 3});
        File decrypted = new File(temp.getRoot(), "f.bin");

        assertThrows(IOException.class, () ->
                EncryptedAttachmentStore.decryptTo(key, encrypted, decrypted));
        assertFalse(decrypted.exists());
    }

    @Test
    public void wrongKeyFailsDecryption() throws Exception {
        byte[] plaintext = "secret".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        File encrypted = temp.newFile("g.enc");
        File decrypted = new File(temp.getRoot(), "g.bin");
        EncryptedAttachmentStore.writeEncrypted(key, encrypted,
                new ByteArrayInputStream(plaintext), plaintext.length);

        KeyGenerator other = KeyGenerator.getInstance("AES");
        other.init(256);
        assertThrows(IOException.class, () ->
                EncryptedAttachmentStore.decryptTo(other.generateKey(), encrypted, decrypted));
        assertTrue(encrypted.exists());
    }
}
