package com.nexachat.core.database;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.sqlite.db.SupportSQLiteOpenHelper;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.sqlcipher.Cursor;
import net.sqlcipher.database.SQLiteDatabase;
import net.sqlcipher.database.SupportFactory;

/**
 * SQLCipher at-rest encryption for the Room database (security audit F-01: decrypted
 * message text previously sat in a plaintext SQLite file).
 *
 * <p><b>Fresh installs</b> need no special handling: Room opens through
 * {@link SupportFactory} with the sealed passphrase, so SQLCipher creates
 * {@link #DB_NAME} encrypted from the first write.
 *
 * <p><b>Upgrades</b>: an existing plaintext database is detected by its 16-byte
 * {@code "SQLite format 3\0"} header and converted before Room opens it:
 * the plaintext file is opened with SQLCipher without a key, its WAL is checkpointed,
 * a new file is ATTACHed keyed with the raw passphrase, {@code sqlcipher_export()}
 * copies schema and data, {@code user_version}/{@code application_id} are copied
 * explicitly, per-table row counts are verified, and only then is the encrypted file
 * renamed over the plaintext one. The plaintext file is never deleted before the
 * encrypted copy has verified, so a failure anywhere leaves the original database
 * untouched and the exception fails closed — history is never wiped.
 *
 * <p>The Room schema version is intentionally unchanged: a cipher change is not a
 * schema change, so no {@code @Database} version bump and no migration SQL is needed.
 */
public final class DatabaseEncryption {

    /** Stable database file name; debug inspection and backup rules point at it. */
    public static final String DB_NAME = "nexachat.db";

    private static final byte[] PLAINTEXT_SQLITE_HEADER =
            "SQLite format 3\0".getBytes(StandardCharsets.US_ASCII);
    private static final String TMP_SUFFIX = ".encrypting";
    private static final String ATTACHED_NAME = "encrypted";
    private static final String[] SIDECAR_SUFFIXES = {"-wal", "-shm", "-journal"};

    private DatabaseEncryption() {}

    /** Loads the SQLCipher native library. Idempotent; must precede any SQLCipher call. */
    public static void loadNativeLibs(@NonNull Context context) {
        SQLiteDatabase.loadLibs(context);
    }

    /**
     * Room open-helper factory backed by SQLCipher. The plaintext-to-encrypted upgrade
     * runs inside {@code create()} — i.e. on Room's database-open thread, immediately
     * before the encrypted open — and only for the production database name, so test
     * databases with other names are untouched. DAOs and repositories are unchanged.
     */
    public static SupportSQLiteOpenHelper.Factory encryptedFactory(
            @NonNull Context context, @NonNull byte[] passphrase) {
        SupportFactory delegate = new SupportFactory(passphrase);
        return configuration -> {
            if (DB_NAME.equals(configuration.name)) {
                loadNativeLibs(context);
                migratePlaintextDatabaseIfNeeded(context, passphrase);
            }
            return delegate.create(configuration);
        };
    }

    /**
     * Converts an existing plaintext {@link #DB_NAME} to SQLCipher in place. No-op for
     * fresh installs (missing/empty file) and for files that are already encrypted.
     *
     * @throws IllegalStateException on any failure; the original plaintext database is
     *         left in place in that case.
     */
    public static void migratePlaintextDatabaseIfNeeded(
            @NonNull Context context, @NonNull byte[] passphrase) {
        File dbFile = context.getDatabasePath(DB_NAME);
        if (!dbFile.isFile() || dbFile.length() == 0 || !hasPlaintextSqliteHeader(dbFile)) {
            return;
        }
        File tmp = new File(dbFile.getParentFile(), DB_NAME + TMP_SUFFIX);
        deleteWithSidecars(tmp);
        SQLiteDatabase plain = null;
        try {
            // No key: SQLCipher reads a plaintext file as-is when no PRAGMA key is set.
            plain = SQLiteDatabase.openDatabase(
                    dbFile.getAbsolutePath(), (String) null, null, SQLiteDatabase.OPEN_READWRITE);
            checkpointWal(plain);
            // Raw key form: x'<64 hex chars>' — the 32-byte passphrase used directly.
            plain.execSQL("ATTACH DATABASE '" + tmp.getAbsolutePath() + "' AS " + ATTACHED_NAME
                    + " KEY \"x'" + toHex(passphrase) + "'\"");
            plain.execSQL("SELECT sqlcipher_export('" + ATTACHED_NAME + "')");
            copyPragmaLong(plain, "user_version");
            copyPragmaLong(plain, "application_id");
            verifySameRowCounts(plain);
            plain.execSQL("DETACH DATABASE " + ATTACHED_NAME);
            plain.close();
            plain = null;
            // rename(2) replaces the target atomically on Linux; if it fails the
            // plaintext file is still there and we fail closed below.
            if (!tmp.renameTo(dbFile)) {
                throw new IOException("Could not move the encrypted database into place");
            }
            deleteSidecars(dbFile);
            deleteWithSidecars(tmp);
        } catch (Exception failure) {
            if (plain != null) {
                try {
                    plain.close();
                } catch (RuntimeException ignored) {
                    // original failure wins
                }
            }
            deleteWithSidecars(tmp);
            throw new IllegalStateException(
                    "SQLCipher migration of the plaintext " + DB_NAME
                            + " failed; the original plaintext database was left untouched",
                    failure);
        }
    }

    /** Folds any WAL content into the main file so the export sees every row. */
    private static void checkpointWal(SQLiteDatabase db) {
        Cursor cursor = db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null);
        try {
            cursor.moveToFirst();
        } finally {
            cursor.close();
        }
    }

    /** Copies a numeric PRAGMA (e.g. user_version) from main to the attached database. */
    private static void copyPragmaLong(SQLiteDatabase db, String pragma) {
        long value = queryLong(db, "PRAGMA main." + pragma);
        db.execSQL("PRAGMA " + ATTACHED_NAME + "." + pragma + " = " + value);
    }

    /** Compares row counts of every user table between main and the attached copy. */
    private static void verifySameRowCounts(SQLiteDatabase db) {
        List<String> tables = new ArrayList<>();
        Cursor cursor = db.rawQuery(
                "SELECT name FROM main.sqlite_master WHERE type = 'table'"
                        + " AND name NOT LIKE 'sqlite_%'",
                null);
        try {
            while (cursor.moveToNext()) {
                tables.add(cursor.getString(0));
            }
        } finally {
            cursor.close();
        }
        for (String table : tables) {
            String quoted = '"' + table.replace("\"", "\"\"") + '"';
            long expected = queryLong(db, "SELECT COUNT(*) FROM main." + quoted);
            long actual = queryLong(db, "SELECT COUNT(*) FROM " + ATTACHED_NAME + "." + quoted);
            if (expected != actual) {
                throw new IllegalStateException(
                        "Row count mismatch after export for table " + table + ": "
                                + expected + " != " + actual);
            }
        }
    }

    private static long queryLong(SQLiteDatabase db, String sql) {
        Cursor cursor = db.rawQuery(sql, null);
        try {
            if (!cursor.moveToFirst()) {
                throw new IllegalStateException("Query returned no row: " + sql);
            }
            return cursor.getLong(0);
        } finally {
            cursor.close();
        }
    }

    /** Package-private for JVM unit tests; the migration trigger. */
    static boolean hasPlaintextSqliteHeader(File dbFile) {
        byte[] header = new byte[PLAINTEXT_SQLITE_HEADER.length];
        try (FileInputStream in = new FileInputStream(dbFile)) {
            int read = 0;
            while (read < header.length) {
                int n = in.read(header, read, header.length - read);
                if (n < 0) {
                    return false;
                }
                read += n;
            }
        } catch (IOException e) {
            return false;
        }
        return Arrays.equals(header, PLAINTEXT_SQLITE_HEADER);
    }

    private static void deleteSidecars(File dbFile) {
        File dir = dbFile.getParentFile();
        for (String suffix : SIDECAR_SUFFIXES) {
            File sidecar = new File(dir, dbFile.getName() + suffix);
            if (sidecar.exists()) {
                sidecar.delete();
            }
        }
    }

    private static void deleteWithSidecars(File dbFile) {
        deleteSidecars(dbFile);
        if (dbFile.exists()) {
            dbFile.delete();
        }
    }

    /** Package-private for JVM unit tests. */
    static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
