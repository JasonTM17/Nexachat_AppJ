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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public final class RoomV9MigrationUnitTest {

    /** DDL smoke only; real row-preservation behavior is covered by the migration instrumented tests. */
    @Test
    public void migration8To9AddsThePreferenceColumnsWithSafeDefaults() {
        SupportSQLiteDatabase db = mock(SupportSQLiteDatabase.class);
        AppDatabase.MIGRATION_8_9.migrate(db);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(db, org.mockito.Mockito.times(4)).execSQL(sqlCaptor.capture());
        List<String> executed = sqlCaptor.getAllValues();

        assertEquals(
                "ALTER TABLE conversations ADD COLUMN muted INTEGER NOT NULL DEFAULT 0",
                normalizeSql(executed.get(0)));
        assertEquals(
                "ALTER TABLE conversations ADD COLUMN mutedUntilEpochMs INTEGER",
                normalizeSql(executed.get(1)));
        assertEquals(
                "ALTER TABLE conversations ADD COLUMN archived INTEGER NOT NULL DEFAULT 0",
                normalizeSql(executed.get(2)));
        assertEquals(
                "ALTER TABLE conversations ADD COLUMN aiOptOut INTEGER NOT NULL DEFAULT 0",
                normalizeSql(executed.get(3)));
    }

    @Test
    public void exportedV9SchemaDeclaresThePreferenceColumns() throws IOException {
        String schemaJson = readExportedSchema("9.json");

        assertTrue(schemaJson.contains("\"columnName\": \"muted\""));
        assertTrue(schemaJson.contains("\"columnName\": \"mutedUntilEpochMs\""));
        assertTrue(schemaJson.contains("\"columnName\": \"archived\""));
        assertTrue(schemaJson.contains("\"columnName\": \"aiOptOut\""));
        assertTrue("muted must be a required integer",
                Pattern.compile("\\\"columnName\\\"\\s*:\\s*\\\"muted\\\""
                                + "\\s*,\\s*\\\"affinity\\\"\\s*:\\s*\\\"INTEGER\\\""
                                + "\\s*,\\s*\\\"notNull\\\"\\s*:\\s*true")
                        .matcher(schemaJson).find());
        assertTrue("mutedUntilEpochMs must be nullable",
                Pattern.compile("\\\"columnName\\\"\\s*:\\s*\\\"mutedUntilEpochMs\\\""
                                + "\\s*,\\s*\\\"affinity\\\"\\s*:\\s*\\\"INTEGER\\\""
                                + "\\s*,\\s*\\\"notNull\\\"\\s*:\\s*false")
                        .matcher(schemaJson).find());
    }

    @Test
    public void conversationEntityPreservesPreferenceFlagsAcrossCopies() {
        ConversationEntity row = new ConversationEntity(
                "c-1", "DIRECT", "Chat", 10L, "Hello", "Lan", 1_000L, 3,
                "Lan", null, null, 8L, null, "u-peer", false, 9L,
                true, 2_000L, true, true);

        assertTrue(row.isMuted());
        assertEquals(Long.valueOf(2_000L), row.getMutedUntilEpochMs());
        assertTrue(row.isArchived());
        assertTrue(row.isAiOptOut());

        ConversationEntity renamed = row.withTitle("Renamed");
        assertTrue(renamed.isMuted());
        assertTrue(renamed.isArchived());
        assertTrue(renamed.isAiOptOut());
        assertEquals(Long.valueOf(2_000L), renamed.getMutedUntilEpochMs());
    }

    private static String normalizeSql(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }

    private static String readExportedSchema(String filename) throws IOException {
        String resource = "schemas/com.nexachat.core.database.AppDatabase/" + filename;
        try (java.io.InputStream input =
                RoomV9MigrationUnitTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (input != null) {
                return new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
        }

        Path modulePath = Path.of(
                System.getProperty("user.dir"), "schemas",
                "com.nexachat.core.database.AppDatabase", filename);
        Path repoRootPath = Path.of(
                System.getProperty("user.dir"), "android", "app", "schemas",
                "com.nexachat.core.database.AppDatabase", filename);
        Path schemaPath = Files.isRegularFile(modulePath) ? modulePath : repoRootPath;
        assertTrue("Exported Room schema not found at " + schemaPath, Files.isRegularFile(schemaPath));
        return new String(Files.readAllBytes(schemaPath), StandardCharsets.UTF_8);
    }
}
