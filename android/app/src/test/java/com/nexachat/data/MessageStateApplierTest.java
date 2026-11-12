package com.nexachat.data;

import static org.junit.Assert.*;
import com.nexachat.core.database.MessageEntity;
import com.nexachat.core.network.dto.MessageDto;
import com.nexachat.core.network.stomp.dto.MessageDeletedDto;
import com.squareup.moshi.Moshi;
import org.junit.Test;

public final class MessageStateApplierTest {
    @Test public void revisionsAndGenerationAreComparedWithoutTimestampAuthority() {
        MessageEntity current = row().withSyncVersion(3, 1);
        assertFalse(MessageStateApplier.accepts(current, row().withSyncVersion(1, 1)));
        assertFalse(MessageStateApplier.accepts(current, row().withSyncVersion(3, 1)));
        assertFalse(MessageStateApplier.accepts(current, row().withSyncVersion(9, 2)));
        assertFalse(MessageStateApplier.accepts(current, row()));
        assertTrue(MessageStateApplier.accepts(current, row().withSyncVersion(4, 1)));
    }

    @Test public void delayedCreationCannotResurrectTombstone() {
        MessageEntity deleted = row().withSyncVersion(4, 1).asDeleted(100L);
        assertFalse(MessageStateApplier.accepts(deleted, row().withSyncVersion(2, 1)));
        assertFalse(MessageStateApplier.accepts(deleted, row()));
        assertFalse(MessageStateApplier.accepts(deleted, row().withSyncVersion(2, 2)));
    }

    @Test public void higherAuthoritativeRevisionCanSupersedeAnOlderOptimisticDelete() {
        MessageEntity optimistic = row().withSyncVersion(3, 1).asDeleted(100L);
        assertTrue(MessageStateApplier.accepts(optimistic, row().withSyncVersion(4, 1)));
        // The optimistic mutation retains the last acknowledged revision, so rollback cannot
        // undo a higher server revision that arrived while its REST request was in flight.
        assertFalse(optimistic.equals(row().withSyncVersion(4, 1)));
    }

    @Test public void legacyTimestampRuleIsMonotonicAndDeletionWins() {
        MessageEntity current = row().withEditedAtEpochMs(20L);
        assertFalse(MessageStateApplier.accepts(current, row()));
        assertFalse(MessageStateApplier.accepts(current, row().withEditedAtEpochMs(10L)));
        assertFalse(MessageStateApplier.accepts(current, row().withEditedAtEpochMs(20L)));
        assertTrue(MessageStateApplier.accepts(current, row().withEditedAtEpochMs(21L)));
        assertTrue(MessageStateApplier.accepts(current, row().asDeleted(30L)));
    }

    @Test public void rowCopiesKeepVersionMetadata() {
        MessageEntity current = row().withSyncVersion(3, 2);
        for (MessageEntity copy : new MessageEntity[] {current.withEditedAtEpochMs(10L),
                current.withContentAndEditedAt("edit", 11L), current.asDeleted(12L), current.withReplyToSnippet(null)}) {
            assertEquals(3, copy.getChangeVersion());
            assertEquals(2, copy.getGeneration());
        }
    }

    @Test public void additiveWireFieldsAndCopiesKeepFullLongPrecision() throws Exception {
        Moshi moshi = new Moshi.Builder().build();
        MessageDto dto = moshi.adapter(MessageDto.class).fromJson(
                "{\"id\":\"m\",\"changeVersion\":9007199254740993,\"generation\":2}");
        assertEquals(Long.valueOf(9_007_199_254_740_993L), dto.withContent("new").getChangeVersion());
        assertEquals(Long.valueOf(2), dto.withDeletedAt("2026-11-12T00:00:00Z", java.util.List.of()).getGeneration());
        MessageDeletedDto deleted = moshi.adapter(MessageDeletedDto.class).fromJson(
                "{\"messageId\":\"m\",\"changeVersion\":7,\"generation\":2}");
        assertEquals(Long.valueOf(7), deleted.getChangeVersion());
        assertEquals(Long.valueOf(2), deleted.getGeneration());
        assertNull(moshi.adapter(MessageDto.class).fromJson("{\"id\":\"legacy\"}").getChangeVersion());
    }

    private static MessageEntity row() {
        return new MessageEntity("m", "c", 1, "peer", "peer", "TEXT", "body",
                null, null, null, "quoted", 0, null, null, null);
    }
}
