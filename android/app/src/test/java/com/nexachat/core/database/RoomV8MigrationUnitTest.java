package com.nexachat.core.database;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import androidx.sqlite.db.SupportSQLiteDatabase;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public final class RoomV8MigrationUnitTest {

    @Test
    public void migration7To8ExecutesExpectedDdlStatements() {
        SupportSQLiteDatabase db = mock(SupportSQLiteDatabase.class);
        AppDatabase.MIGRATION_7_8.migrate(db);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(db, org.mockito.Mockito.atLeast(5)).execSQL(sqlCaptor.capture());

        java.util.List<String> executed = sqlCaptor.getAllValues();

        boolean createdUsersTable = executed.stream().anyMatch(sql ->
                sql.contains("CREATE TABLE IF NOT EXISTS `users`")
                        && sql.contains("`id` TEXT NOT NULL")
                        && sql.contains("`displayName` TEXT"));
        boolean createdUsersIndex = executed.stream().anyMatch(sql ->
                sql.contains("CREATE INDEX IF NOT EXISTS `index_users_displayName`"));

        boolean createdCallLogsTable = executed.stream().anyMatch(sql ->
                sql.contains("CREATE TABLE IF NOT EXISTS `call_logs`")
                        && sql.contains("`callId` TEXT NOT NULL")
                        && sql.contains("`startedAt` INTEGER NOT NULL"));
        boolean createdCallLogsIndex = executed.stream().anyMatch(sql ->
                sql.contains("CREATE INDEX IF NOT EXISTS `index_call_logs_startedAt`"));

        boolean createdOfflineOutboxTable = executed.stream().anyMatch(sql ->
                sql.contains("CREATE TABLE IF NOT EXISTS `offline_outbox`")
                        && sql.contains("`id` TEXT NOT NULL")
                        && sql.contains("`idempotencyKey` TEXT NOT NULL")
                        && sql.contains("`actionType` TEXT NOT NULL"));
        boolean createdOfflineOutboxIndex = executed.stream().anyMatch(sql ->
                sql.contains("CREATE UNIQUE INDEX IF NOT EXISTS `index_offline_outbox_idempotencyKey`"));

        boolean addedPeerDeliveredSeq = executed.stream().anyMatch(sql ->
                sql.contains("ALTER TABLE `conversations` ADD COLUMN `peerDeliveredSeq`"));

        assertTrue("Should create users table", createdUsersTable);
        assertTrue("Should create users displayName index", createdUsersIndex);
        assertTrue("Should create call_logs table", createdCallLogsTable);
        assertTrue("Should create call_logs startedAt index", createdCallLogsIndex);
        assertTrue("Should create offline_outbox table", createdOfflineOutboxTable);
        assertTrue("Should create offline_outbox idempotencyKey unique index", createdOfflineOutboxIndex);
        assertTrue("Should add peerDeliveredSeq to conversations", addedPeerDeliveredSeq);
    }

    @Test
    public void userEntityPropertiesAndContract() {
        UserEntity user1 = new UserEntity(
                "user-1", "alice", "Alice Wonder", "+84901234567",
                "http://avatar.jpg", "Bio text", 1000L, 2000L);
        UserEntity user2 = new UserEntity(
                "user-1", "alice", "Alice Wonder", "+84901234567",
                "http://avatar.jpg", "Bio text", 1000L, 2000L);
        UserEntity userDiff = new UserEntity(
                "user-2", "bob", "Bob Dylan", null,
                null, null, null, null);

        assertEquals("user-1", user1.getId());
        assertEquals("alice", user1.getUsername());
        assertEquals("Alice Wonder", user1.getDisplayName());
        assertEquals("+84901234567", user1.getPhoneNumber());
        assertEquals("http://avatar.jpg", user1.getAvatarUrl());
        assertEquals("Bio text", user1.getBio());
        assertEquals(Long.valueOf(1000L), user1.getLastSeenAt());
        assertEquals(Long.valueOf(2000L), user1.getUpdatedAt());

        assertEquals(user1, user2);
        assertEquals(user1.hashCode(), user2.hashCode());
        assertNotEquals(user1, userDiff);
        assertNotNull(user1.toString());
    }

    @Test
    public void callLogEntityPropertiesAndContract() {
        CallLogEntity log1 = new CallLogEntity(
                "call-100", "conv-200", "peer-300", "Alice",
                "http://avatar.jpg", "INCOMING", "AUDIO", 5000L, 120L, "COMPLETED");
        CallLogEntity log2 = new CallLogEntity(
                "call-100", "conv-200", "peer-300", "Alice",
                "http://avatar.jpg", "INCOMING", "AUDIO", 5000L, 120L, "COMPLETED");
        CallLogEntity logDiff = new CallLogEntity(
                "call-101", "conv-200", "peer-300", "Alice",
                null, "OUTGOING", "VIDEO", 6000L, 0L, "MISSED");

        assertEquals("call-100", log1.getCallId());
        assertEquals("conv-200", log1.getConversationId());
        assertEquals("peer-300", log1.getPeerId());
        assertEquals("Alice", log1.getPeerDisplayName());
        assertEquals("http://avatar.jpg", log1.getPeerAvatarUrl());
        assertEquals("INCOMING", log1.getDirection());
        assertEquals("AUDIO", log1.getCallType());
        assertEquals(5000L, log1.getStartedAt());
        assertEquals(120L, log1.getDurationSeconds());
        assertEquals("COMPLETED", log1.getEndReason());

        assertEquals(log1, log2);
        assertEquals(log1.hashCode(), log2.hashCode());
        assertNotEquals(log1, logDiff);
        assertNotNull(log1.toString());
    }

    @Test
    public void offlineOutboxEntityPropertiesAndTransitions() {
        OfflineOutboxEntity item = new OfflineOutboxEntity(
                "item-1", "idemp-key-1", "SEND_MESSAGE", "{\"text\":\"hi\"}",
                OutboxState.PENDING, 0, 1000L, 0L);

        assertEquals("item-1", item.getId());
        assertEquals("idemp-key-1", item.getIdempotencyKey());
        assertEquals("SEND_MESSAGE", item.getActionType());
        assertEquals("{\"text\":\"hi\"}", item.getPayloadJson());
        assertEquals(OutboxState.PENDING, item.getState());
        assertEquals(0, item.getRetryCount());
        assertEquals(1000L, item.getCreatedAtEpochMs());
        assertEquals(0L, item.getLastAttemptEpochMs());

        OfflineOutboxEntity inProgress = item.withStateAndAttempt(OutboxState.IN_PROGRESS, 1, 1050L);
        assertEquals(OutboxState.IN_PROGRESS, inProgress.getState());
        assertEquals(1, inProgress.getRetryCount());
        assertEquals(1050L, inProgress.getLastAttemptEpochMs());

        assertNotNull(item.toString());
    }

    @Test
    public void conversationEntityPreservesPeerDeliveredSeq() {
        ConversationEntity conv = new ConversationEntity(
                "c-1", "DIRECT", "Chat", 10L, "Hello", "Lan",
                1000L, 0, "Lan", null, null, 8L, null, "u-peer", false, 9L);

        assertEquals(8L, conv.getPeerReadSeq());
        assertEquals(9L, conv.getPeerDeliveredSeq());

        ConversationEntity updated = conv.withPeerDeliveredSeq(10L);
        assertEquals(10L, updated.getPeerDeliveredSeq());
        assertEquals(8L, updated.getPeerReadSeq());
    }
}
