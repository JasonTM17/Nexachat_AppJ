package com.nexachat.core.database;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import org.junit.Test;

/**
 * JVM coverage for the plaintext-detection trigger of the SQLCipher migration
 * ({@link DatabaseEncryption#hasPlaintextSqliteHeader}) and the raw-key hex encoding.
 * The export/verify/rename path itself needs a device (SQLCipher native lib) and is
 * covered by the instrumented path, not here.
 */
public final class DatabaseEncryptionUnitTest {

    @Test
    public void detectsPlaintextSqliteHeader() throws IOException {
        File db = tempDb();
        try (FileOutputStream out = new FileOutputStream(db)) {
            out.write("SQLite format 3\0".getBytes(StandardCharsets.US_ASCII));
            out.write(new byte[4096 - 16]);
        }
        assertTrue(DatabaseEncryption.hasPlaintextSqliteHeader(db));
    }

    @Test
    public void rejectsEncryptedLookingHeader() throws IOException {
        File db = tempDb();
        byte[] randomPage = new byte[4096];
        new SecureRandom().nextBytes(randomPage);
        try (FileOutputStream out = new FileOutputStream(db)) {
            out.write(randomPage);
        }
        assertFalse(DatabaseEncryption.hasPlaintextSqliteHeader(db));
    }

    @Test
    public void rejectsEmptyAndTruncatedFiles() throws IOException {
        File empty = tempDb();
        assertFalse(DatabaseEncryption.hasPlaintextSqliteHeader(empty));
        File shortFile = tempDb();
        try (FileOutputStream out = new FileOutputStream(shortFile)) {
            out.write("SQLi".getBytes(StandardCharsets.US_ASCII));
        }
        assertFalse(DatabaseEncryption.hasPlaintextSqliteHeader(shortFile));
    }

    @Test
    public void hexEncodesRawKeyAsLowercasePairs() {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) i;
        }
        String hex = DatabaseEncryption.toHex(key);
        assertEquals(64, hex.length());
        assertEquals("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f", hex);
    }

    private static File tempDb() throws IOException {
        File f = Files.createTempFile("nexachat-test", ".db").toFile();
        f.deleteOnExit();
        return f;
    }
}
