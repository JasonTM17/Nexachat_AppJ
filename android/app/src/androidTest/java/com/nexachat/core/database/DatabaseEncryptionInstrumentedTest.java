package com.nexachat.core.database;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import androidx.room.Room;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.sqlite.db.SupportSQLiteOpenHelper;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.File;
import java.util.Arrays;
import net.zetetic.database.sqlcipher.SQLiteDatabase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Proves the SQLCipher wiring end to end on a real device: a database opened through
 * {@link DatabaseEncryption#encryptedFactory} is ciphertext on disk, and a legacy
 * plaintext file is converted in place with its rows intact — the two guarantees the
 * JVM suite cannot check because it has no native library.
 *
 * <p>The fixture reuses the production {@link DatabaseEncryption#DB_NAME} so the
 * migration trigger inside {@code encryptedFactory.create()} runs. The debug app's
 * own database file is disposable on a test emulator; the fixture deletes and
 * recreates it on every test.
 */
@RunWith(AndroidJUnit4.class)
public final class DatabaseEncryptionInstrumentedTest {

    private static final byte[] PASSPHRASE = new byte[32];
    private static final String PROBE_TABLE = "encryption_probe";

    static {
        Arrays.fill(PASSPHRASE, (byte) 0x2D);
    }

    private final Context context = ApplicationProvider.getApplicationContext();

    @Before
    public void loadLibrary() {
        context.deleteDatabase(DatabaseEncryption.DB_NAME);
        DatabaseEncryption.loadNativeLibs(context);
    }

    @After
    public void cleanUp() {
        context.deleteDatabase(DatabaseEncryption.DB_NAME);
        new File(context.getDatabasePath(DatabaseEncryption.DB_NAME).getParentFile(),
                DatabaseEncryption.DB_NAME + ".encrypting").delete();
    }

    @Test
    public void roomOpenedThroughTheFactoryWritesACiphertextFile() throws Exception {
        SupportSQLiteOpenHelper helper = Room
                .databaseBuilder(context, AppDatabase.class, DatabaseEncryption.DB_NAME)
                .openHelperFactory(DatabaseEncryption.encryptedFactory(context, PASSPHRASE))
                .build()
                .getOpenHelper();
        SupportSQLiteDatabase db = helper.getWritableDatabase();
        db.execSQL("CREATE TABLE " + PROBE_TABLE + " (id INTEGER PRIMARY KEY, body TEXT)");
        db.execSQL("INSERT INTO " + PROBE_TABLE + " (body) VALUES ('at rest must be ciphered')");
        db.close();
        helper.close();

        File dbFile = context.getDatabasePath(DatabaseEncryption.DB_NAME);
        assertTrue("the database file must exist after the write", dbFile.isFile());
        assertFalse("the file must not carry the plaintext SQLite header",
                DatabaseEncryption.hasPlaintextSqliteHeader(dbFile));
        assertThrows("the plaintext stack must not read the ciphered file",
                android.database.sqlite.SQLiteException.class, () -> {
                    android.database.sqlite.SQLiteDatabase fw =
                            android.database.sqlite.SQLiteDatabase.openDatabase(
                                    dbFile.getAbsolutePath(), null,
                                    android.database.sqlite.SQLiteDatabase.OPEN_READONLY);
                    try {
                        fw.rawQuery("SELECT count(*) FROM sqlite_master", null).close();
                    } finally {
                        fw.close();
                    }
                });
    }

    @Test
    public void plaintextDatabaseIsConvertedWithRowsIntact() {
        File dbFile = context.getDatabasePath(DatabaseEncryption.DB_NAME);
        android.database.sqlite.SQLiteDatabase plain = android.database.sqlite.SQLiteDatabase
                .openOrCreateDatabase(dbFile, null);
        plain.execSQL("CREATE TABLE " + PROBE_TABLE + " (id INTEGER PRIMARY KEY, body TEXT)");
        plain.execSQL("INSERT INTO " + PROBE_TABLE + " (body) VALUES ('legacy row one')");
        plain.execSQL("INSERT INTO " + PROBE_TABLE + " (body) VALUES ('legacy row two')");
        plain.close();
        assertTrue("fixture must start as a plaintext SQLite file",
                DatabaseEncryption.hasPlaintextSqliteHeader(dbFile));

        DatabaseEncryption.migratePlaintextDatabaseIfNeeded(context, PASSPHRASE);

        assertFalse("the migrated file must be ciphertext on disk",
                DatabaseEncryption.hasPlaintextSqliteHeader(dbFile));
        assertThrows("the plaintext stack must not read the migrated file",
                android.database.sqlite.SQLiteException.class, () -> {
                    android.database.sqlite.SQLiteDatabase fw =
                            android.database.sqlite.SQLiteDatabase.openDatabase(
                                    dbFile.getAbsolutePath(), null,
                                    android.database.sqlite.SQLiteDatabase.OPEN_READONLY);
                    try {
                        fw.rawQuery("SELECT count(*) FROM sqlite_master", null).close();
                    } finally {
                        fw.close();
                    }
                });

        // SQLCipher treats a "x'<64 hex chars>'" password string as a raw key.
        SQLiteDatabase encrypted = SQLiteDatabase.openDatabase(
                dbFile.getAbsolutePath(), "x'" + DatabaseEncryption.toHex(PASSPHRASE) + "'",
                null, SQLiteDatabase.OPEN_READONLY, null, null);
        android.database.Cursor cursor = encrypted.rawQuery(
                "SELECT body FROM " + PROBE_TABLE + " ORDER BY id", null);
        try {
            assertTrue(cursor.moveToFirst());
            assertEquals("legacy row one", cursor.getString(0));
            assertTrue(cursor.moveToNext());
            assertEquals("legacy row two", cursor.getString(0));
            assertFalse(cursor.moveToNext());
        } finally {
            cursor.close();
            encrypted.close();
        }
    }

    @Test
    public void theFactoryMigratesAPlaintextFileBeforeRoomOpensIt() throws Exception {
        File dbFile = context.getDatabasePath(DatabaseEncryption.DB_NAME);
        android.database.sqlite.SQLiteDatabase plain = android.database.sqlite.SQLiteDatabase
                .openOrCreateDatabase(dbFile, null);
        plain.execSQL("CREATE TABLE " + PROBE_TABLE + " (id INTEGER PRIMARY KEY, body TEXT)");
        plain.execSQL("INSERT INTO " + PROBE_TABLE + " (body) VALUES ('upgraded row')");
        plain.close();

        SupportSQLiteOpenHelper helper = Room
                .databaseBuilder(context, AppDatabase.class, DatabaseEncryption.DB_NAME)
                .openHelperFactory(DatabaseEncryption.encryptedFactory(context, PASSPHRASE))
                .build()
                .getOpenHelper();
        // create() runs the migration before the encrypted open, so the first
        // writable handle already sees the legacy rows through Room.
        SupportSQLiteDatabase db = helper.getWritableDatabase();
        android.database.Cursor cursor = db.query(
                "SELECT body FROM " + PROBE_TABLE + " ORDER BY id");
        try {
            assertTrue("the upgraded row must be readable through Room", cursor.moveToFirst());
            assertEquals("upgraded row", cursor.getString(0));
        } finally {
            cursor.close();
            db.close();
            helper.close();
        }
        assertFalse("the file on disk must be ciphertext after the factory open",
                DatabaseEncryption.hasPlaintextSqliteHeader(dbFile));
    }
}
