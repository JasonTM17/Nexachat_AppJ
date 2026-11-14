package com.nexachat.core.database;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import androidx.sqlite.db.SupportSQLiteDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

/** Room 16 adds one nullable preview-type column; existing rows keep null. */
public final class RoomV16MigrationUnitTest {

    @Test public void migrationIsAdditiveAndNullable() {
        SupportSQLiteDatabase db = mock(SupportSQLiteDatabase.class);
        AppDatabase.MIGRATION_15_16.migrate(db);
        assertEquals(15, AppDatabase.MIGRATION_15_16.startVersion);
        assertEquals(16, AppDatabase.MIGRATION_15_16.endVersion);
        verify(db).execSQL("ALTER TABLE `conversations` ADD COLUMN `lastMessageType` TEXT");
    }

    @Test public void exportedSchemaContainsLastMessageType() throws Exception {
        Path schema = Path.of("schemas/com.nexachat.core.database.AppDatabase/16.json");
        if (!Files.exists(schema)) schema = Path.of("android/app").resolve(schema);
        String json = new String(Files.readAllBytes(schema), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue("missing Room16 field lastMessageType", json.contains("\"lastMessageType\""));
        assertFalse(json.contains("`lastMessageType` TEXT NOT NULL"));
    }
}
