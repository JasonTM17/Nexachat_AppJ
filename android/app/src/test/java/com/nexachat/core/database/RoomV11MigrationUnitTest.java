package com.nexachat.core.database;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import androidx.sqlite.db.SupportSQLiteDatabase;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public final class RoomV11MigrationUnitTest {

    /** DDL smoke only; real row preservation is covered by RoomMigrationInstrumentedTest. */
    @Test
    public void migration10To11AddsDefaultedMentionColumns() {
        SupportSQLiteDatabase db = mock(SupportSQLiteDatabase.class);
        AppDatabase.MIGRATION_10_11.migrate(db);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(db, org.mockito.Mockito.times(4)).execSQL(sqlCaptor.capture());
        assertEquals(
                List.of(
                        "ALTER TABLE messages ADD COLUMN mentionsJson TEXT NOT NULL DEFAULT '[]'",
                        "ALTER TABLE messages ADD COLUMN mentionAll INTEGER NOT NULL DEFAULT 0",
                        "ALTER TABLE outbox ADD COLUMN mentionsJson TEXT NOT NULL DEFAULT '[]'",
                        "ALTER TABLE outbox ADD COLUMN mentionAll INTEGER NOT NULL DEFAULT 0"),
                sqlCaptor.getAllValues().stream().map(RoomV11MigrationUnitTest::normalizeSql).toList());
    }

    @Test
    public void exportedV11SchemaDeclaresMentionColumnsOnMessagesAndOutbox() throws IOException {
        String schema = readExportedSchema();

        assertTrue(
                "Room schema must advance to version 11",
                Pattern.compile("\\\"version\\\"\\s*:\\s*11").matcher(schema).find());
        assertEquals(2, occurrences(schema, "\"columnName\": \"mentionsJson\""));
        assertEquals(2, occurrences(schema, "\"columnName\": \"mentionAll\""));
    }

    private static String normalizeSql(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    private static String readExportedSchema() throws IOException {
        String resource = "schemas/com.nexachat.core.database.AppDatabase/11.json";
        try (java.io.InputStream input =
                RoomV11MigrationUnitTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (input != null) {
                return new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
        }

        Path modulePath = Path.of(
                System.getProperty("user.dir"), "schemas",
                "com.nexachat.core.database.AppDatabase", "11.json");
        Path repoRootPath = Path.of(
                System.getProperty("user.dir"), "android", "app", "schemas",
                "com.nexachat.core.database.AppDatabase", "11.json");
        Path schemaPath = Files.isRegularFile(modulePath) ? modulePath : repoRootPath;
        assertTrue("Exported Room schema not found at " + schemaPath, Files.isRegularFile(schemaPath));
        return new String(Files.readAllBytes(schemaPath), StandardCharsets.UTF_8);
    }
}
