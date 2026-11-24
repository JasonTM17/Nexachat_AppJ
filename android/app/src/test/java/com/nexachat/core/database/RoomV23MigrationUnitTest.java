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

/** Room 23 adds the messages.replyToId index; additive index only. */
public final class RoomV23MigrationUnitTest {

    @Test public void migrationCreatesTheReplyTargetIndex() {
        SupportSQLiteDatabase db = mock(SupportSQLiteDatabase.class);
        AppDatabase.MIGRATION_22_23.migrate(db);
        assertEquals(22, AppDatabase.MIGRATION_22_23.startVersion);
        assertEquals(23, AppDatabase.MIGRATION_22_23.endVersion);
        verify(db).execSQL("CREATE INDEX IF NOT EXISTS `index_messages_replyToId` "
                + "ON `messages` (`replyToId`)");
        verifyNoMoreInteractions(db);
    }

    @Test public void exportedSchemaContainsTheReplyTargetIndex() throws Exception {
        Path schema = Path.of("schemas/com.nexachat.core.database.AppDatabase/23.json");
        if (!Files.exists(schema)) schema = Path.of("android/app").resolve(schema);
        String json = new String(Files.readAllBytes(schema), StandardCharsets.UTF_8);
        assertTrue("missing replyToId index", json.contains("index_messages_replyToId"));
    }
}
