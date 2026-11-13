package com.nexachat.feature.poll;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PollFragmentBlockedRenderTest {

    @Test
    public void notAGroupRendersOnErrorSurfaceWithoutRetry() {
        PollRepository.Failure blocked = PollRepository.Failure.NOT_A_GROUP;
        assertTrue(PollFragment.BoardListRenderRules.isBlocked(blocked));
        assertTrue(PollFragment.BoardListRenderRules.showError(blocked, false));
        assertFalse(PollFragment.BoardListRenderRules.offersRetry(blocked));
    }

    @Test
    public void recoverableLoadFailureStillOffersRetry() {
        PollRepository.Failure recoverable = PollRepository.Failure.OFFLINE;
        assertFalse(PollFragment.BoardListRenderRules.isBlocked(recoverable));
        assertTrue(PollFragment.BoardListRenderRules.showError(recoverable, false));
        assertTrue(PollFragment.BoardListRenderRules.offersRetry(recoverable));
    }

    @Test
    public void blockedLoadFailureIsNotTreatedAsEmptyList() {
        PollUiState blocked = new PollUiState(
                false,
                true,
                java.util.Collections.emptyList(),
                PollRepository.Failure.NOT_MEMBER,
                null,
                false,
                false,
                null,
                false,
                null,
                null,
                null,
                java.util.Collections.emptyMap(),
                java.util.Collections.emptyMap(),
                null,
                false);
        assertFalse(blocked.isEmpty());
        assertFalse(PollFragment.BoardListRenderRules.showEmpty(blocked.isEmpty()));
    }
}
