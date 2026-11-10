package com.nexachat.data;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

public final class ConversationPreferenceLanesTest {

    private ConversationPreferenceLanes lanes;

    @Before
    public void setUp() {
        lanes = new ConversationPreferenceLanes();
    }

    @Test
    public void archiveThenUndoThenArchiveLastVersionWins() {
        ConversationPreferenceLanes.Ticket first =
                lanes.begin("c-1", ConversationPreferenceLanes.Kind.ARCHIVE);
        ConversationPreferenceLanes.Ticket undo =
                lanes.begin("c-1", ConversationPreferenceLanes.Kind.ARCHIVE);
        ConversationPreferenceLanes.Ticket second =
                lanes.begin("c-1", ConversationPreferenceLanes.Kind.ARCHIVE);

        assertTrue(lanes.isStale("c-1", ConversationPreferenceLanes.Kind.ARCHIVE, first.version));
        assertTrue(lanes.isStale("c-1", ConversationPreferenceLanes.Kind.ARCHIVE, undo.version));
        assertFalse(lanes.isStale("c-1", ConversationPreferenceLanes.Kind.ARCHIVE, second.version));
    }

    @Test
    public void lanesAreIndependentPerConversation() {
        ConversationPreferenceLanes.Ticket a =
                lanes.begin("c-a", ConversationPreferenceLanes.Kind.ARCHIVE);
        ConversationPreferenceLanes.Ticket b =
                lanes.begin("c-b", ConversationPreferenceLanes.Kind.ARCHIVE);

        assertFalse(lanes.isStale("c-a", ConversationPreferenceLanes.Kind.ARCHIVE, a.version));
        assertFalse(lanes.isStale("c-b", ConversationPreferenceLanes.Kind.ARCHIVE, b.version));
    }

    @Test
    public void preserveLocalWhileInFlightClearsAfterFinish() {
        ConversationPreferenceLanes.Ticket ticket =
                lanes.begin("c-1", ConversationPreferenceLanes.Kind.MUTE);
        assertTrue(lanes.preserveLocalOverServer("c-1", ConversationPreferenceLanes.Kind.MUTE));
        lanes.finish("c-1", ConversationPreferenceLanes.Kind.MUTE, ticket.version);
        assertFalse(lanes.preserveLocalOverServer("c-1", ConversationPreferenceLanes.Kind.MUTE));
    }

    @Test
    public void staleFinishDoesNotClearNewerInFlight() {
        ConversationPreferenceLanes.Ticket first =
                lanes.begin("c-1", ConversationPreferenceLanes.Kind.ARCHIVE);
        lanes.begin("c-1", ConversationPreferenceLanes.Kind.ARCHIVE);
        lanes.finish("c-1", ConversationPreferenceLanes.Kind.ARCHIVE, first.version);
        assertTrue(lanes.preserveLocalOverServer("c-1", ConversationPreferenceLanes.Kind.ARCHIVE));
    }
}
