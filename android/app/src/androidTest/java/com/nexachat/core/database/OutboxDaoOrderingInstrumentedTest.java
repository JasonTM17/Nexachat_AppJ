package com.nexachat.core.database;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Exercises pendingList ordering against Room's real SQLite query. */
@RunWith(AndroidJUnit4.class)
public final class OutboxDaoOrderingInstrumentedTest {

    @Test
    public void pendingListReturnsOldestFirstForOutOfOrderInserts() {
        Context context = ApplicationProvider.getApplicationContext();
        AppDatabase database = Room.inMemoryDatabaseBuilder(context, AppDatabase.class)
                .allowMainThreadQueries()
                .build();
        try {
            OutboxDao dao = database.outboxDao();
            dao.insert(row("newest", 300L)).blockingAwait();
            dao.insert(row("oldest", 100L)).blockingAwait();
            dao.insert(row("middle", 200L)).blockingAwait();

            List<String> orderedIds = dao.pendingList("user-1").blockingGet().stream()
                    .map(OutboxEntity::getClientMessageId)
                    .collect(Collectors.toList());
            assertEquals(List.of("oldest", "middle", "newest"), orderedIds);
        } finally {
            database.close();
        }
    }

    private static OutboxEntity row(String id, long createdAt) {
        return new OutboxEntity(
                id, "conversation", "TEXT", id, null, OutboxState.SENDING, createdAt,
                "", "[]", false, "user-1");
    }
}
