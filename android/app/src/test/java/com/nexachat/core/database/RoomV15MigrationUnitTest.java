package com.nexachat.core.database;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import androidx.sqlite.db.SupportSQLiteDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

/** Room 15 adds additive E2EE columns only; no table rebuilds or outbox changes. */
public final class RoomV15MigrationUnitTest {
    @Test public void migrationIsAdditiveAndNeverTouchesQueuedSendsOrHistory() {
        SupportSQLiteDatabase db = mock(SupportSQLiteDatabase.class);
        AppDatabase.MIGRATION_14_15.migrate(db);
        assertEquals(14, AppDatabase.MIGRATION_14_15.startVersion);
        assertEquals(15, AppDatabase.MIGRATION_14_15.endVersion);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(db, times(5)).execSQL(sql.capture());
        List<String> statements = sql.getAllValues();
        assertEquals("ALTER TABLE `messages` ADD COLUMN `encrypted` INTEGER NOT NULL DEFAULT 0",
                statements.get(0));
        assertEquals("ALTER TABLE `messages` ADD COLUMN `decryptFailed` INTEGER NOT NULL DEFAULT 0",
                statements.get(1));
        assertEquals("ALTER TABLE `messages` ADD COLUMN `replyToEncrypted` INTEGER NOT NULL DEFAULT 0",
                statements.get(2));
        assertEquals("ALTER TABLE `conversations` ADD COLUMN `e2eeEnabled` INTEGER NOT NULL DEFAULT 0",
                statements.get(3));
        assertEquals(
                "ALTER TABLE `conversations` ADD COLUMN `lastMessageEncrypted` INTEGER NOT NULL DEFAULT 0",
                statements.get(4));
        for (String statement : statements) {
            assertFalse(statement.contains("outbox"));
            assertFalse(statement.toUpperCase(java.util.Locale.ROOT).contains("DROP TABLE"));
        }
    }

    @Test public void exportedSchemaContainsE2eeColumns() throws Exception {
        Path schema = Path.of("schemas/com.nexachat.core.database.AppDatabase/15.json");
        if (!Files.exists(schema)) schema = Path.of("android/app").resolve(schema);
        String json = new String(Files.readAllBytes(schema), java.nio.charset.StandardCharsets.UTF_8);
        for (String column : List.of("encrypted", "decryptFailed", "replyToEncrypted",
                "e2eeEnabled", "lastMessageEncrypted")) {
            assertTrue("missing Room15 field " + column, json.contains("\"" + column + "\""));
        }
        assertTrue(json.contains("`encrypted` INTEGER NOT NULL DEFAULT 0"));
        assertTrue(json.contains("`e2eeEnabled` INTEGER NOT NULL DEFAULT 0"));
    }
}
