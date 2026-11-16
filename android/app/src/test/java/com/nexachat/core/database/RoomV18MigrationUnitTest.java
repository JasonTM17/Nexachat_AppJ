package com.nexachat.core.database;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import androidx.sqlite.db.SupportSQLiteDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

/** Room 18 adds the local translation cache keyed by message revision. */
public final class RoomV18MigrationUnitTest {

    @Test public void migrationCreatesTranslationTable() {
        SupportSQLiteDatabase db = mock(SupportSQLiteDatabase.class);
        AppDatabase.MIGRATION_17_18.migrate(db);
        assertEquals(17, AppDatabase.MIGRATION_17_18.startVersion);
        assertEquals(18, AppDatabase.MIGRATION_17_18.endVersion);
        verify(db).execSQL("CREATE TABLE IF NOT EXISTS `message_translations` ("
                + "`messageId` TEXT NOT NULL, `conversationId` TEXT NOT NULL, "
                + "`changeVersion` INTEGER NOT NULL, `targetLang` TEXT NOT NULL, "
                + "`sourceLang` TEXT NOT NULL, `translatedText` TEXT NOT NULL, "
                + "`fetchedAt` INTEGER NOT NULL, "
                + "PRIMARY KEY(`messageId`, `changeVersion`, `targetLang`))");
        verify(db).execSQL("CREATE INDEX IF NOT EXISTS `index_message_translations_conversationId` "
                + "ON `message_translations` (`conversationId`)");
    }

    @Test public void exportedSchemaContainsTranslationTable() throws Exception {
        Path schema = Path.of("schemas/com.nexachat.core.database.AppDatabase/18.json");
        if (!Files.exists(schema)) schema = Path.of("android/app").resolve(schema);
        String json = new String(Files.readAllBytes(schema), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue("missing message_translations table", json.contains("message_translations"));
        assertTrue("missing revision key", json.contains("changeVersion"));
        assertTrue("missing language key", json.contains("targetLang"));
        assertTrue("missing translated text", json.contains("translatedText"));
    }
}
