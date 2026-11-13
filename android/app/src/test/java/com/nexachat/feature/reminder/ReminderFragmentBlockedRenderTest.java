package com.nexachat.feature.reminder;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ReminderFragmentBlockedRenderTest {

    @Test
    public void notMemberRendersOnErrorSurfaceWithoutRetry() {
        ReminderRepository.Failure blocked = ReminderRepository.Failure.NOT_MEMBER;
        assertTrue(ReminderFragment.BoardListRenderRules.isBlocked(blocked));
        assertTrue(ReminderFragment.BoardListRenderRules.showError(blocked, false));
        assertFalse(ReminderFragment.BoardListRenderRules.offersRetry(blocked));
    }

    @Test
    public void recoverableLoadFailureStillOffersRetry() {
        ReminderRepository.Failure recoverable = ReminderRepository.Failure.OFFLINE;
        assertFalse(ReminderFragment.BoardListRenderRules.isBlocked(recoverable));
        assertTrue(ReminderFragment.BoardListRenderRules.showError(recoverable, false));
        assertTrue(ReminderFragment.BoardListRenderRules.offersRetry(recoverable));
    }

    @Test
    public void blockedLoadFailureIsNotTreatedAsEmptyList() {
        ReminderUiState blocked = new ReminderUiState(
                false,
                true,
                java.util.Collections.emptyList(),
                ReminderRepository.Failure.FORBIDDEN,
                null,
                false,
                null,
                0L);
        assertFalse(blocked.isEmpty());
        assertFalse(ReminderFragment.BoardListRenderRules.showEmpty(blocked.isEmpty()));
    }
}
