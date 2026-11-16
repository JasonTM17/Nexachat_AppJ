package com.nexachat.core.database;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

import androidx.sqlite.db.SupportSQLiteDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;
import org.mockito.InOrder;

/** Room 20 adds the message expiry column and the conversation timer; both are additive. */
public final class RoomV20MigrationUnitTest {

    @Test public void migrationAddsExpiryAndTimerColumns() {
        SupportSQLiteDatabase db = mock(SupportSQLiteDatabase.class);
        AppDatabase.MIGRATION_19_20.migrate(db);
        assertEquals(19, AppDatabase.MIGRATION_19_20.startVersion);
        assertEquals(20, AppDatabase.MIGRATION_19_20.endVersion);
        InOrder order = inOrder(db);
        order.verify(db).execSQL(
                "ALTER TABLE `messages` ADD COLUMN `expiresAtEpochMs` INTEGER");
        order.verify(db).execSQL(
                "ALTER TABLE `conversations` ADD COLUMN `disappearingSeconds` "
                        + "INTEGER NOT NULL DEFAULT 0");
    }

    @Test public void exportedSchemaContainsExpiryColumns() throws Exception {
        Path schema = Path.of("schemas/com.nexachat.core.database.AppDatabase/20.json");
        if (!Files.exists(schema)) schema = Path.of("android/app").resolve(schema);
        String json = new String(Files.readAllBytes(schema), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue("missing message expiry column", json.contains("expiresAtEpochMs"));
        assertTrue("missing conversation timer column", json.contains("disappearingSeconds"));
    }
}
