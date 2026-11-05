package com.nexachat.core.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MessageUiRulesTest {

    @Test
    public void tombstoneRuleIdentifiesDeletedMessages() {
        assertTrue(MessageUiRules.isTombstone(true, null));
        assertTrue(MessageUiRules.isTombstone(false, 1730000000000L));
        assertTrue(MessageUiRules.isTombstone(true, 1730000000000L));
        assertFalse(MessageUiRules.isTombstone(false, null));
        assertFalse(MessageUiRules.isTombstone(false, 0L));
    }

    @Test
    public void isEditedRuleShowsLabelOnlyForNonDeletedEditedMessages() {
        // Edited and not deleted -> true
        assertTrue(MessageUiRules.isEdited(1730000000000L, null));
        assertTrue(MessageUiRules.isEdited(1730000000000L, 0L));

        // Never edited -> false
        assertFalse(MessageUiRules.isEdited(null, null));
        assertFalse(MessageUiRules.isEdited(0L, null));

        // Edited but subsequently deleted -> false (tombstone supersedes edit label)
        assertFalse(MessageUiRules.isEdited(1730000000000L, 1730000005000L));
    }

    @Test
    public void canEditRuleAllowsOnlyAuthorTextNonDeleted() {
        assertTrue(MessageUiRules.canEdit(true, "TEXT", false));
        assertFalse("Cannot edit peer message", MessageUiRules.canEdit(false, "TEXT", false));
        assertFalse("Cannot edit image", MessageUiRules.canEdit(true, "IMAGE", false));
        assertFalse("Cannot edit file", MessageUiRules.canEdit(true, "FILE", false));
        assertFalse("Cannot edit deleted message", MessageUiRules.canEdit(true, "TEXT", true));
    }

    @Test
    public void canDeleteRuleAllowsOnlyAuthorNonDeleted() {
        assertTrue(MessageUiRules.canDelete(true, false));
        assertFalse("Cannot delete peer message", MessageUiRules.canDelete(false, false));
        assertFalse("Cannot delete already deleted message", MessageUiRules.canDelete(true, true));
    }

    @Test
    public void canCopyRuleAllowsOnlyNonDeletedText() {
        assertTrue(MessageUiRules.canCopy("TEXT", false));
        assertFalse("Cannot copy deleted", MessageUiRules.canCopy("TEXT", true));
        assertFalse("Cannot copy image content", MessageUiRules.canCopy("IMAGE", false));
    }

    @Test
    public void canReplyRuleAllowsOnlyNonDeleted() {
        assertTrue(MessageUiRules.canReply(false));
        assertFalse(MessageUiRules.canReply(true));
    }

    @Test
    public void extractInitialsHandlesUserAndGroupNames() {
        // Group names fallback
        assertEquals("NG", MessageUiRules.extractInitials("NexaChat Group"));
        assertEquals("NA", MessageUiRules.extractInitials("Nhom Do An"));
        assertEquals("CS", MessageUiRules.extractInitials("Computer Science"));

        // User names
        assertEquals("NA", MessageUiRules.extractInitials("Nguyen An"));
        assertEquals("B", MessageUiRules.extractInitials("Binh"));
        assertEquals("LK", MessageUiRules.extractInitials("Le Minh Khoa"));

        // Edge cases
        assertEquals("?", MessageUiRules.extractInitials(null));
        assertEquals("?", MessageUiRules.extractInitials(""));
        assertEquals("?", MessageUiRules.extractInitials("   "));
    }
}
