package com.nexachat.core.database;

import android.content.Context;
import androidx.room.Room;
import androidx.room.RoomDatabase;

/**
 * Test-only database builders for instrumented tests.
 *
 * <p>These deliberately use Room's default framework open helper, i.e. a PLAINTEXT
 * file database: the migration tests exercise the Room schema migration chain on
 * plaintext fixture databases, not the at-rest cipher. This plaintext path exists only
 * in {@code androidTest} — production builds ({@code main}) always open through
 * {@link DatabaseEncryption#encryptedFactory}, and nothing in {@code main} can fall
 * back to plaintext.
 */
public final class TestDatabases {

    private TestDatabases() {}

    /** Plaintext file-backed builder — test-only, see class javadoc. */
    public static RoomDatabase.Builder<AppDatabase> plaintextFile(Context context, String name) {
        return Room.databaseBuilder(context, AppDatabase.class, name);
    }
}
