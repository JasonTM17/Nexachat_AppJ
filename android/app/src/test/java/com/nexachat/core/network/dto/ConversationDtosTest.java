package com.nexachat.core.network.dto;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import java.io.IOException;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public final class ConversationDtosTest {

    private final Moshi moshi = new Moshi.Builder().build();

    @Test
    public void decodesInboxPresenceTombstonesAndOptionalFields() throws IOException {
        JsonAdapter<ConversationInboxDto> adapter = moshi.adapter(ConversationInboxDto.class);
        ConversationInboxDto online = adapter.fromJson(
                "{\"id\":\"conv-online\",\"type\":\"DIRECT\","
                        + "\"members\":[{\"userId\":\"peer-1\",\"displayName\":\"Peer\"}],"
                        + "\"peerPresence\":{\"online\":true,\"lastSeenAt\":null},"
                        + "\"lastReadSeq\":7,"
                        + "\"futureField\":\"ignored\"}"
        );
        ConversationInboxDto hidden = adapter.fromJson(
                "{\"id\":\"conv-hidden\",\"type\":\"DIRECT\","
                        + "\"lastMessage\":{\"seq\":9,\"type\":\"TEXT\","
                        + "\"contentSnippet\":null,\"deletedAt\":\"2026-11-03T11:00:00Z\"}}"
        );

        assertNotNull(online);
        assertNotNull(online.getPeerPresence());
        assertEquals(Boolean.TRUE, online.getPeerPresence().getOnline());
        assertNull(online.getPeerPresence().getLastSeenAt());
        assertEquals(Long.valueOf(7L), online.getLastReadSeq());
        assertNotNull(hidden);
        assertNull(hidden.getPeerPresence());
        assertNotNull(hidden.getLastMessage());
        assertNull(hidden.getLastMessage().getContent());
        assertEquals("2026-11-03T11:00:00Z", hidden.getLastMessage().getDeletedAt());
    }

    @Test
    public void decodesCallerScopedMuteArchiveAndAiOptOutFlags() throws IOException {
        JsonAdapter<ConversationInboxDto> adapter = moshi.adapter(ConversationInboxDto.class);
        ConversationInboxDto row = adapter.fromJson(
                "{\"id\":\"conv-flags\",\"type\":\"DIRECT\",\"muted\":true,"
                        + "\"mutedUntil\":\"2026-11-10T01:00:00Z\",\"archived\":true,"
                        + "\"aiOptOut\":true}"
        );

        assertNotNull(row);
        assertEquals(Boolean.TRUE, row.getMuted());
        assertEquals("2026-11-10T01:00:00Z", row.getMutedUntil());
        assertEquals(Boolean.TRUE, row.getArchived());
        assertEquals(Boolean.TRUE, row.getAiOptOut());
    }

    @Test
    public void legacyRowsLeavePreferenceFlagsAbsent() throws IOException {
        ConversationInboxDto row = moshi.adapter(ConversationInboxDto.class)
                .fromJson("{\"id\":\"conv-legacy\"}");

        assertNotNull(row);
        assertNull(row.getMuted());
        assertNull(row.getMutedUntil());
        assertNull(row.getArchived());
        assertNull(row.getAiOptOut());
    }

    @Test
    public void decodesOpaqueCursorInLegacyPageEnvelope() throws IOException {
        ConversationsPageDto page = moshi.adapter(ConversationsPageDto.class).fromJson(
                "{\"items\":[{\"id\":\"conv-1\"}],\"nextCursor\":\"cursor-2\","
                        + "\"hasMore\":true,\"futureField\":1}"
        );

        assertNotNull(page);
        assertEquals("cursor-2", page.getNextCursor());
        assertEquals(true, page.getHasMore());
    }
}
