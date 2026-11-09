package com.nexachat.core.database;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import androidx.sqlite.db.SupportSQLiteDatabase;
import org.junit.Test;

public final class RoomV9MigrationUnitTest {
    @Test
    public void migration8To9AddsDefaultedCallerLabelCache() {
        SupportSQLiteDatabase database = mock(SupportSQLiteDatabase.class);

        AppDatabase.MIGRATION_8_9.migrate(database);

        assertEquals(8, AppDatabase.MIGRATION_8_9.startVersion);
        assertEquals(9, AppDatabase.MIGRATION_8_9.endVersion);
        verify(database).execSQL(
                "ALTER TABLE `conversations` ADD COLUMN `labelIdsCsv` TEXT NOT NULL DEFAULT ''");
    }
}
