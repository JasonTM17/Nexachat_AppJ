package com.nexachat.core.ui;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ForwardFilterTest {

    @Test
    public void emptyQueryMatchesAnyConversation() {
        assertTrue(ForwardFilter.matchesQuery("NexaChat Team", ""));
        assertTrue(ForwardFilter.matchesQuery("NexaChat Team", "   "));
        assertTrue(ForwardFilter.matchesQuery("NexaChat Team", null));
    }

    @Test
    public void queryMatchesCaseAndDiacriticInsensitively() {
        assertTrue(ForwardFilter.matchesQuery("Nhóm Kỹ Thuật", "nhom"));
        assertTrue(ForwardFilter.matchesQuery("Nhóm Kỹ Thuật", "ky thuat"));
        assertTrue(ForwardFilter.matchesQuery("Lan Anh", "lan"));
        assertTrue(ForwardFilter.matchesQuery("Minh Khoa", "khoa"));
        assertFalse(ForwardFilter.matchesQuery("Minh Khoa", "dung"));
    }

    @Test
    public void attachmentsAndDeletedMessagesRemainNonForwardable() {
        assertTrue(ForwardFilter.canForward("TEXT", false));
        assertFalse("Deleted message cannot be forwarded", ForwardFilter.canForward("TEXT", true));
        assertFalse("Image cannot be forwarded", ForwardFilter.canForward("IMAGE", false));
        assertFalse("File cannot be forwarded", ForwardFilter.canForward("FILE", false));
        assertFalse("Video cannot be forwarded", ForwardFilter.canForward("VIDEO", false));
        assertFalse("Audio cannot be forwarded", ForwardFilter.canForward("AUDIO", false));
        assertFalse("Voice cannot be forwarded", ForwardFilter.canForward("VOICE", false));
        assertFalse("Unknown types cannot be forwarded", ForwardFilter.canForward(null, false));
    }
}
