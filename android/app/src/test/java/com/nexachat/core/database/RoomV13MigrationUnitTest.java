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

public final class RoomV13MigrationUnitTest {

    @Test
    public void migration12To13AddsUnknownOwnerAndOwnerScopedIndex() {
        SupportSQLiteDatabase database = mock(SupportSQLiteDatabase.class);

        AppDatabase.MIGRATION_12_13.migrate(database);

        assertEquals(12, AppDatabase.MIGRATION_12_13.startVersion);
        assertEquals(13, AppDatabase.MIGRATION_12_13.endVersion);
        verify(database).execSQL(
                "ALTER TABLE `outbox` ADD COLUMN `ownerUserId` TEXT NOT NULL DEFAULT ''");
        verify(database).execSQL(
                "ALTER TABLE `outbox` ADD COLUMN `ownerResolved` INTEGER NOT NULL DEFAULT 0");
        verify(database).execSQL(
                "CREATE INDEX IF NOT EXISTS `index_outbox_ownerUserId_state_createdAt` "
                        + "ON `outbox` (`ownerUserId`, `state`, `createdAt`)");
    }

    @Test
    public void exportedV13SchemaDeclaresOwnerDefaultAndIndex() throws IOException {
        String schema = readExportedSchema();

        assertTrue(schema.contains("\"columnName\": \"ownerUserId\""));
        assertTrue(schema.contains("\"defaultValue\": \"''\""));
        assertTrue(schema.contains("\"columnName\": \"ownerResolved\""));
        assertTrue(schema.contains("\"defaultValue\": \"0\""));
        assertTrue(schema.contains("`ownerResolved` INTEGER NOT NULL DEFAULT 0"));
        assertTrue(schema.contains("index_outbox_ownerUserId_state_createdAt"));
    }

    private static String readExportedSchema() throws IOException {
        String resource = "schemas/com.nexachat.core.database.AppDatabase/13.json";
        try (java.io.InputStream input = RoomV13MigrationUnitTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            if (input != null) {
                return new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
        }

        Path modulePath = Path.of(
                System.getProperty("user.dir"), "schemas",
                "com.nexachat.core.database.AppDatabase", "13.json");
        Path repoRootPath = Path.of(
                System.getProperty("user.dir"), "android", "app", "schemas",
                "com.nexachat.core.database.AppDatabase", "13.json");
        Path schemaPath = Files.isRegularFile(modulePath) ? modulePath : repoRootPath;
        assertTrue("Exported Room schema not found at " + schemaPath, Files.isRegularFile(schemaPath));
        return new String(Files.readAllBytes(schemaPath), StandardCharsets.UTF_8);
    }
}
