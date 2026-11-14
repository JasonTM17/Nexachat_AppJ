package com.nexachat.feature.inbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
    public void ownAvatarNeverBorrowsInitialsFromTheCaption() {
        assertEquals("", InboxIdentityRules.ownAvatarName(null));
        assertEquals("Lan Anh", InboxIdentityRules.ownAvatarName(
                new UserDto("u1", "+84900000201", "Lan Anh", null)));
    }
}