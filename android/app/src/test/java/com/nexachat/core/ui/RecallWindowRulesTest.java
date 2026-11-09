package com.nexachat.core.ui;

import static org.junit.Assert.assertEquals;

import com.nexachat.core.network.dto.AuthResponse;
import com.nexachat.core.storage.RecallWindowConfig;
import org.junit.Test;

public final class RecallWindowRulesTest {
    private static final long CREATED_AT = 1_000_000L;
    private static final long WINDOW_SECONDS = 86_400L;
    private static final long WINDOW_MS = WINDOW_SECONDS * 1_000L;

    @Test
    public void showsRecallJustInsideTheStrictBoundary() {
        assertEquals(RecallWindowRules.DeleteAction.RECALL,
                action(true, false, CREATED_AT + WINDOW_MS - 1L, WINDOW_SECONDS));
    }

    @Test
    public void hidesRecallAtAndOutsideTheStrictBoundary() {
        assertEquals(RecallWindowRules.DeleteAction.DELETE_FOR_ME,
                action(true, false, CREATED_AT + WINDOW_MS, WINDOW_SECONDS));
        assertEquals(RecallWindowRules.DeleteAction.DELETE_FOR_ME,
                action(true, false, CREATED_AT + WINDOW_MS + 1L, WINDOW_SECONDS));
    }

    @Test
    public void usesTheSuppliedServerAdjustedInstantWhenDeviceClockIsSkewed() {
        long deviceNow = CREATED_AT + WINDOW_MS - 4L * 60L * 1_000L;
        long adjustedNow = deviceNow + 5L * 60L * 1_000L;

        assertEquals(RecallWindowRules.DeleteAction.RECALL,
                action(true, false, deviceNow, WINDOW_SECONDS));
        assertEquals(RecallWindowRules.DeleteAction.DELETE_FOR_ME,
                action(true, false, adjustedNow, WINDOW_SECONDS));
    }

    @Test
    public void expiredGroupAdminCanModerateButOtherMembersCannotDeleteForEveryone() {
        assertEquals(RecallWindowRules.DeleteAction.ADMIN_DELETE,
                RecallWindowRules.deleteAction(true, false, true, true, true,
                        CREATED_AT, CREATED_AT + WINDOW_MS, WINDOW_SECONDS));
        assertEquals(RecallWindowRules.DeleteAction.ADMIN_DELETE,
                RecallWindowRules.deleteAction(true, false, false, true, true,
                        CREATED_AT, CREATED_AT + WINDOW_MS, WINDOW_SECONDS));
        assertEquals(RecallWindowRules.DeleteAction.NONE,
                RecallWindowRules.deleteAction(true, false, false, false, true,
                        CREATED_AT, CREATED_AT + WINDOW_MS, WINDOW_SECONDS));
    }

    @Test
    public void missingSessionConfigFallsBackToTwentyFourHours() {
        AuthResponse legacy = new AuthResponse();
        RecallWindowConfig config = new RecallWindowConfig(
                legacy.getRecallWindowSeconds(), legacy.getGroupAdminMayRecallAfterWindow());

        assertEquals(RecallWindowConfig.DEFAULT_RECALL_WINDOW_SECONDS,
                config.getRecallWindowSeconds());
        assertEquals(RecallWindowRules.DeleteAction.RECALL,
                action(true, false, CREATED_AT + 86_400_000L - 1L,
                        config.getRecallWindowSeconds()));
        assertEquals(RecallWindowRules.DeleteAction.DELETE_FOR_ME,
                action(true, false, CREATED_AT + 86_400_000L,
                        config.getRecallWindowSeconds()));
    }

    @Test
    public void pendingOrDeletedMessagesHaveNoDeleteAction() {
        assertEquals(RecallWindowRules.DeleteAction.NONE,
                RecallWindowRules.deleteAction(false, false, true, false, true,
                        CREATED_AT, CREATED_AT, WINDOW_SECONDS));
        assertEquals(RecallWindowRules.DeleteAction.NONE,
                RecallWindowRules.deleteAction(true, true, true, false, true,
                        CREATED_AT, CREATED_AT, WINDOW_SECONDS));
    }

    private static RecallWindowRules.DeleteAction action(
            boolean mine, boolean groupAdmin, long now, long windowSeconds) {
        return RecallWindowRules.deleteAction(true, false, mine, groupAdmin, true,
                CREATED_AT, now, windowSeconds);
    }
}
