package com.nexachat.core.database;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import androidx.sqlite.db.SupportSQLiteDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

/** Matches the existing Room migration unit-test style; device execution remains a separate gate. */
public final class RoomV14MigrationUnitTest {
    @Test public void migrationIsAdditiveAndNeverTouchesQueuedSendsOrHistory() {
        SupportSQLiteDatabase db = mock(SupportSQLiteDatabase.class);
        AppDatabase.MIGRATION_13_14.migrate(db);
        assertEquals(13, AppDatabase.MIGRATION_13_14.startVersion);
        assertEquals(14, AppDatabase.MIGRATION_13_14.endVersion);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(db, times(6)).execSQL(sql.capture());
        List<String> statements = sql.getAllValues();
        assertEquals("ALTER TABLE `messages` ADD COLUMN `changeVersion` INTEGER NOT NULL DEFAULT 0", statements.get(0));
        assertEquals("ALTER TABLE `messages` ADD COLUMN `generation` INTEGER NOT NULL DEFAULT 0", statements.get(1));
        assertTrue(statements.get(2).contains("PRIMARY KEY(`ownerUserId`, `conversationId`, `generation`)"));
        assertTrue(statements.get(3).startsWith("CREATE UNIQUE INDEX"));
        assertTrue(statements.get(4).contains("AFTER DELETE ON `messages`"));
        assertTrue(statements.get(5).contains("AFTER DELETE ON `conversations`"));
        for (String statement : statements) {
            assertFalse(statement.contains("outbox"));
            assertFalse(statement.toUpperCase(java.util.Locale.ROOT).contains("DROP TABLE"));
        }
    }

    @Test public void exportedSchemaContainsBothCheckpointsAndRowVersions() throws Exception {
        Path schema = Path.of("schemas/com.nexachat.core.database.AppDatabase/14.json");
        if (!Files.exists(schema)) schema = Path.of("android/app").resolve(schema);
        String json = new String(Files.readAllBytes(schema), java.nio.charset.StandardCharsets.UTF_8);
        for (String column : List.of("changeVersion", "generation", "sync_cursors", "reactionSince",
                "reactionCursor", "reactionBaseline", "rebuildingReactions")) {
            assertTrue("missing Room14 field " + column, json.contains("\"" + column + "\""));
        }
        assertTrue(json.contains("index_sync_cursors_ownerUserId_conversationId"));
        assertTrue(json.contains("`changeVersion` INTEGER NOT NULL DEFAULT 0"));
        assertTrue(json.contains("`generation` INTEGER NOT NULL DEFAULT 0"));
    }
}
