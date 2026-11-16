package com.nexachat.core.database;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import androidx.sqlite.db.SupportSQLiteDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

/** Room 19 adds the per-user message-star table; additive table + index only. */
public final class RoomV19MigrationUnitTest {

    @Test public void migrationCreatesStarsTable() {
        SupportSQLiteDatabase db = mock(SupportSQLiteDatabase.class);
        AppDatabase.MIGRATION_18_19.migrate(db);
        assertEquals(18, AppDatabase.MIGRATION_18_19.startVersion);
        assertEquals(19, AppDatabase.MIGRATION_18_19.endVersion);
        verify(db).execSQL("CREATE TABLE IF NOT EXISTS `message_stars` ("
                + "`messageId` TEXT NOT NULL, `conversationId` TEXT NOT NULL, "
                + "`starredAt` INTEGER NOT NULL, PRIMARY KEY(`messageId`))");
        verify(db).execSQL("CREATE INDEX IF NOT EXISTS `index_message_stars_conversationId` "
                + "ON `message_stars` (`conversationId`)");
        verifyNoMoreInteractions(db);
    }

    @Test public void exportedSchemaContainsStarsTable() throws Exception {
        Path schema = Path.of("schemas/com.nexachat.core.database.AppDatabase/19.json");
        if (!Files.exists(schema)) schema = Path.of("android/app").resolve(schema);
        String json = new String(Files.readAllBytes(schema), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue("missing message_stars table", json.contains("message_stars"));
        assertTrue("missing conversation index", json.contains("index_message_stars_conversationId"));
        assertTrue("missing starredAt column", json.contains("starredAt"));
        int table = json.indexOf("\"tableName\": \"message_stars\"");
        int indices = json.indexOf("\"indices\"", table);
        String indexList = json.substring(indices, json.indexOf(']', indices) + 1);
        // The entity declares one non-unique index, so the schema must carry
        // exactly it — Room compares the real table info with this file.
        assertTrue("message_stars must declare the conversationId index",
                indexList.contains("index_message_stars_conversationId"));
        assertEquals("message_stars must declare no extra indices",
                1, indexList.split("\"name\"").length - 1);
    }
}
