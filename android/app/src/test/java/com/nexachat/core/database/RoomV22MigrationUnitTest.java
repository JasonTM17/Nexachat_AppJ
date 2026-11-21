package com.nexachat.core.database;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import androidx.sqlite.db.SupportSQLiteDatabase;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

/** Room 22 adds local composer drafts; additive table only. */
public final class RoomV22MigrationUnitTest {

    @Test public void migrationCreatesComposerDraftsTable() {
        SupportSQLiteDatabase db = mock(SupportSQLiteDatabase.class);
        AppDatabase.MIGRATION_21_22.migrate(db);
        assertEquals(21, AppDatabase.MIGRATION_21_22.startVersion);
        assertEquals(22, AppDatabase.MIGRATION_21_22.endVersion);
        verify(db).execSQL("CREATE TABLE IF NOT EXISTS `composer_drafts` ("
                + "`conversationId` TEXT NOT NULL, `content` TEXT NOT NULL, "
                + "`mentionsJson` TEXT NOT NULL, `mentionAllOffset` INTEGER NOT NULL, "
                + "`mentionAllLength` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, "
                + "PRIMARY KEY(`conversationId`))");
        verifyNoMoreInteractions(db);
    }

    @Test public void exportedSchemaContainsComposerDrafts() throws Exception {
        Path schema = Path.of("schemas/com.nexachat.core.database.AppDatabase/22.json");
        if (!Files.exists(schema)) schema = Path.of("android/app").resolve(schema);
        String json = new String(Files.readAllBytes(schema), StandardCharsets.UTF_8);
        assertTrue("missing composer_drafts table", json.contains("composer_drafts"));
        assertTrue("missing mentionsJson column", json.contains("mentionsJson"));
        assertTrue("missing updatedAtEpochMs column", json.contains("updatedAtEpochMs"));
    }
}
