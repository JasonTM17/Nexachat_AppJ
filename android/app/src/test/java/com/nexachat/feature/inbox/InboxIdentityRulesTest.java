package com.nexachat.feature.inbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.nexachat.core.common.ApiError;
import com.nexachat.core.network.dto.UserDto;
import com.nexachat.feature.profile.ProfileViewModel;
import org.junit.Test;

public final class InboxIdentityRulesTest {

    @Test
    public void restoredSessionWithoutUserLoadsTheProfileEvenThoughTheInitialStateReadsAsLoading() {
        assertTrue(InboxIdentityRules.needsProfileLoad(null));
        assertTrue(InboxIdentityRules.needsProfileLoad(new ProfileViewModel.UiState()));
    }

    @Test
    public void knownUserIsNotLoadedAgain() {
        ProfileViewModel.UiState known = new ProfileViewModel.UiState()
                .withUser(new UserDto("u1", "+84900000201", "Lan Anh", null));
        assertFalse(InboxIdentityRules.needsProfileLoad(known));
    }

    @Test
    public void cachedUserFromAnotherSessionIsReloaded() {
        ProfileViewModel.UiState stale = new ProfileViewModel.UiState()
                .withUser(new UserDto("user-a", "+84900000201", "Lan Anh", null));
        assertTrue(InboxIdentityRules.needsProfileLoad(stale, "user-b"));
    }

    @Test
    public void cachedUserMatchingTheSessionIsKept() {
        ProfileViewModel.UiState fresh = new ProfileViewModel.UiState()
                .withUser(new UserDto("user-b", "+84900000202", "Minh Khoa", null));
        assertFalse(InboxIdentityRules.needsProfileLoad(fresh, "user-b"));
        assertFalse(InboxIdentityRules.needsProfileLoad(fresh, null));
    }

    @Test
    public void missingUserStillLoadsRegardlessOfSessionId() {
        assertTrue(InboxIdentityRules.needsProfileLoad(null, "user-b"));
        assertTrue(InboxIdentityRules.needsProfileLoad(new ProfileViewModel.UiState(), "user-b"));
        assertTrue(InboxIdentityRules.needsProfileLoad(new ProfileViewModel.UiState(), null));
    }

    @Test
    public void failedLoadIsSettledAndDoesNotAutoRetry() {
        ProfileViewModel.UiState failed = new ProfileViewModel.UiState()
                .withLoading(false)
                .withError(new ApiError.Network("down"));
        assertFalse(InboxIdentityRules.needsProfileLoad(failed, "user-b"));
        assertFalse(InboxIdentityRules.needsProfileLoad(failed, null));
    }

    @Test
    public void ownAvatarNeverBorrowsInitialsFromTheCaption() {
        assertEquals("", InboxIdentityRules.ownAvatarName(null));
        assertEquals("Lan Anh", InboxIdentityRules.ownAvatarName(
                new UserDto("u1", "+84900000201", "Lan Anh", null)));
    }
}