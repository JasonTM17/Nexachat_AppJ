package com.nexachat.feature.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ArchiveNavigationArgsTest {

    @Test
    public void originalMessageNavigationCarriesConversationAndMessageIds() {
        ArchiveNavigationArgs args = ArchiveNavigationArgs.forOriginalMessage(
                "conversation-1", "message-9");

        assertEquals("conversation-1", args.getConversationId());
        assertEquals("message-9", args.getMessageId());
        assertTrue(args.hasMessageTarget());
    }

    @Test
    public void openingArchiveDoesNotInventAnOriginalMessageTarget() {
        ArchiveNavigationArgs args = ArchiveNavigationArgs.forConversation("conversation-1");

        assertEquals("conversation-1", args.getConversationId());
        assertFalse(args.hasMessageTarget());
    }
}
