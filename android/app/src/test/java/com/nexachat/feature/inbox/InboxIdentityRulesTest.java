package com.nexachat.feature.inbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
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
        assertTrue(InboxIdentityRules.needsProfileLoad(stale, "user-b", false));
    }

    @Test
    public void mismatchedUserDoesNotRetryAgainInTheSameEntry() {
        ProfileViewModel.UiState stale = new ProfileViewModel.UiState()
                .withUser(new UserDto("user-a", "+84900000201", "Lan Anh", null));
        assertFalse(InboxIdentityRules.needsProfileLoad(stale, "user-b", true));
    }

    @Test
    public void cachedUserMatchingTheSessionIsKept() {
        ProfileViewModel.UiState fresh = new ProfileViewModel.UiState()
                .withUser(new UserDto("user-b", "+84900000202", "Minh Khoa", null));
        assertFalse(InboxIdentityRules.needsProfileLoad(fresh, "user-b", false));
        assertFalse(InboxIdentityRules.needsProfileLoad(fresh, null, false));
    }

    @Test
    public void missingUserStillLoadsRegardlessOfSessionId() {
        assertTrue(InboxIdentityRules.needsProfileLoad(null, "user-b", false));
        assertTrue(InboxIdentityRules.needsProfileLoad(
                new ProfileViewModel.UiState(), "user-b", false));
        assertTrue(InboxIdentityRules.needsProfileLoad(
                new ProfileViewModel.UiState(), null, false));
    }

    @Test
    public void failedLoadRetriesOncePerEntry() {
        ProfileViewModel.UiState failed = new ProfileViewModel.UiState()
                .withLoading(false)
                .withError(new ApiError.Network("down"));
        // A new inbox entry re-arms the retry; a repeated check inside the same
        // entry stays quiet so a dead server cannot loop the observer.
        assertTrue(InboxIdentityRules.needsProfileLoad(failed, "user-b", false));
        assertFalse(InboxIdentityRules.needsProfileLoad(failed, "user-b", true));
        assertFalse(InboxIdentityRules.needsProfileLoad(failed, null, true));
    }

    @Test
    public void onlyTheSessionVerifiedUserMayRender() {
        UserDto signedIn = new UserDto("user-b", "+84900000202", "Minh Khoa", null);
        assertEquals(signedIn, InboxIdentityRules.renderableUser(signedIn, "user-b"));
        assertNull(InboxIdentityRules.renderableUser(signedIn, "user-a"));
        assertNull(InboxIdentityRules.renderableUser(signedIn, null));
        assertNull(InboxIdentityRules.renderableUser(null, "user-b"));
    }

    @Test
    public void ownAvatarNeverBorrowsInitialsFromTheCaption() {
        assertEquals("", InboxIdentityRules.ownAvatarName(null));
        assertEquals("Lan Anh", InboxIdentityRules.ownAvatarName(
                new UserDto("u1", "+84900000201", "Lan Anh", null)));
    }
}