package com.nexachat.core.database;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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

    @Test
    public void pendingUploadBlocksTheNextSendAndPromotionKeepsItsOwnerAndPosition() {
        Context context = ApplicationProvider.getApplicationContext();
        AppDatabase database = Room.inMemoryDatabaseBuilder(context, AppDatabase.class)
                .allowMainThreadQueries().build();
        try {
            OutboxDao dao = database.outboxDao();
            dao.insertNew(new OutboxEntity("file-id", "conversation", "FILE", "file.pdf",
                    null, OutboxState.WAITING_UPLOAD, 100L, "{\"mime\":\"application/pdf\"}",
                    "[]", false, "user-1"));
            dao.insert(row("text-id", 200L)).blockingAwait();

            assertTrue(dao.hasEarlierUpload("user-1", "conversation", 200L).blockingGet());
            assertFalse(dao.hasEarlierUpload("user-2", "conversation", 200L).blockingGet());
            assertEquals(List.of(new OutboxDao.StagedUploadRef("user-1", "file-id")), dao.stagedUploadRefs());
            assertEquals(0, dao.markUploadReady("file-id", "user-2", "FILE", "[]"));
            assertEquals(1, dao.markUploadReady("file-id", "user-1", "VIDEO", "[]"));
            assertEquals(0, dao.markUploadReady("file-id", "user-1", "FILE", "[]"));
            assertTrue(dao.stagedUploadRefs().isEmpty());

            List<OutboxEntity> pending = dao.pendingList("user-1").blockingGet();
            assertEquals("file-id", pending.get(0).getClientMessageId());
            assertEquals(OutboxState.UPLOAD_READY, pending.get(0).getState());
            assertEquals("VIDEO", pending.get(0).getType());
            assertEquals("text-id", pending.get(1).getClientMessageId());
            assertTrue(dao.hasEarlierUpload("user-1", "conversation", 200L).blockingGet());
            dao.delete("file-id", "user-1").blockingAwait();
            assertFalse(dao.hasEarlierUpload("user-1", "conversation", 200L).blockingGet());
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
