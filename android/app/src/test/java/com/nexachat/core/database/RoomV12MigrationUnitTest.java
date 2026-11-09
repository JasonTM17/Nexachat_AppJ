package com.nexachat.core.database;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import androidx.sqlite.db.SupportSQLiteDatabase;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

public final class RoomV12MigrationUnitTest {

    @Test
    public void migration11To12AddsDefaultedCallerLabelCache() {
        SupportSQLiteDatabase database = mock(SupportSQLiteDatabase.class);

        AppDatabase.MIGRATION_11_12.migrate(database);

        assertEquals(11, AppDatabase.MIGRATION_11_12.startVersion);
        assertEquals(12, AppDatabase.MIGRATION_11_12.endVersion);
        verify(database).execSQL(
                "ALTER TABLE `conversations` ADD COLUMN `labelIdsCsv` TEXT NOT NULL DEFAULT ''");
    }

    @Test
    public void exportedV12SchemaDeclaresTheLabelCacheDefault() throws IOException {
        String schema = readExportedSchema();

        assertTrue(schema.contains("\"columnName\": \"labelIdsCsv\""));
        assertTrue(schema.contains("\"defaultValue\": \"''\""));
    }

    private static String readExportedSchema() throws IOException {
        String resource = "schemas/com.nexachat.core.database.AppDatabase/12.json";
        try (java.io.InputStream input = RoomV12MigrationUnitTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            if (input != null) {
                return new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
        }

        Path modulePath = Path.of(
                System.getProperty("user.dir"), "schemas",
                "com.nexachat.core.database.AppDatabase", "12.json");
        Path repoRootPath = Path.of(
                System.getProperty("user.dir"), "android", "app", "schemas",
                "com.nexachat.core.database.AppDatabase", "12.json");
        Path schemaPath = Files.isRegularFile(modulePath) ? modulePath : repoRootPath;
        assertTrue("Exported Room schema not found at " + schemaPath, Files.isRegularFile(schemaPath));
        return new String(Files.readAllBytes(schemaPath), StandardCharsets.UTF_8);
    }
}
