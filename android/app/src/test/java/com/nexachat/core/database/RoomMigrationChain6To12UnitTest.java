package com.nexachat.core.database;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import androidx.sqlite.db.SupportSQLiteDatabase;
import java.util.List;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

/**
 * Sequentially exercises every shipped migration from v6 through v12 on a
 * mocked database so the chain stays runnable in JVM unit tests without Room.
 * Row preservation is covered by {@code RoomMigrationInstrumentedTest}.
 */
public final class RoomMigrationChain6To12UnitTest {

    @Test
    public void migrations6Through12ExecuteInOrderWithoutOverlap() {
        SupportSQLiteDatabase db = mock(SupportSQLiteDatabase.class);
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);

        AppDatabase.MIGRATION_6_7.migrate(db);
        AppDatabase.MIGRATION_7_8.migrate(db);
        AppDatabase.MIGRATION_8_9.migrate(db);
        AppDatabase.MIGRATION_9_10.migrate(db);
        AppDatabase.MIGRATION_10_11.migrate(db);
        AppDatabase.MIGRATION_11_12.migrate(db);

        org.mockito.Mockito.verify(db, org.mockito.Mockito.atLeastOnce()).execSQL(sqlCaptor.capture());
        List<String> executed = sqlCaptor.getAllValues();
        assertEquals(22, executed.size());
        assertTrue(executed.stream().anyMatch(sql -> sql.contains("editedAtEpochMs")));
        assertTrue(executed.stream().anyMatch(sql -> sql.contains("offline_outbox")));
        assertTrue(executed.stream().anyMatch(sql -> sql.contains("labelIdsCsv")));
        assertEquals(
                8,
                executed.stream().filter(sql -> sql.contains("ALTER TABLE conversations")).count());
    }
}
