package com.nexachat.core.database;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

import androidx.sqlite.db.SupportSQLiteDatabase;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;
import org.mockito.InOrder;

/** Room 21 adds the cached streak badge columns to the conversation row; both are additive. */
public final class RoomV21MigrationUnitTest {

    @Test public void migrationAddsStreakColumns() {
        SupportSQLiteDatabase db = mock(SupportSQLiteDatabase.class);
        AppDatabase.MIGRATION_20_21.migrate(db);
        assertEquals(20, AppDatabase.MIGRATION_20_21.startVersion);
        assertEquals(21, AppDatabase.MIGRATION_20_21.endVersion);
        InOrder order = inOrder(db);
        order.verify(db).execSQL(
                "ALTER TABLE `conversations` ADD COLUMN `streakCount` "
                        + "INTEGER NOT NULL DEFAULT 0");
        order.verify(db).execSQL(
                "ALTER TABLE `conversations` ADD COLUMN `streakAtRisk` "
                        + "INTEGER NOT NULL DEFAULT 0");
    }

    @Test public void exportedSchemaContainsStreakColumns() throws Exception {
        Path schema = Path.of("schemas/com.nexachat.core.database.AppDatabase/21.json");
        if (!Files.exists(schema)) schema = Path.of("android/app").resolve(schema);
        String json = new String(Files.readAllBytes(schema), StandardCharsets.UTF_8);
        assertTrue("missing cached streak count", json.contains("streakCount"));
        assertTrue("missing cached streak risk flag", json.contains("streakAtRisk"));
    }
}
