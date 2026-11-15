package com.nexachat.feature.inbox;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.nexachat.core.network.dto.UserDto;
import com.nexachat.feature.profile.ProfileViewModel;

/** Decides how the inbox shows and loads the signed-in user's own identity. */
final class InboxIdentityRules {

    private InboxIdentityRules() {}

    /**
     * The inbox reads the user from the shared profile state, which is empty after the app
     * process restarts with a restored session. Its initial state already reads as loading although
     * no request was made, so a missing user is the only reliable signal. A repeated request is
     * an idempotent read.
     */
    static boolean needsProfileLoad(@Nullable ProfileViewModel.UiState state) {
        return needsProfileLoad(state, null);
    }

    /**
     * The shared profile state also survives a logout to login transition. When the cached
     * user belongs to a different account than the signed-in session, the previous identity
     * must be reloaded rather than rendered.
     */
    static boolean needsProfileLoad(
            @Nullable ProfileViewModel.UiState state,
            @Nullable String signedInUserId) {
        if (state == null || state.getUser() == null) {
            // A settled failure stays quiet; auto-retrying would loop on a dead network.
            return state == null || state.getError() == null;
        }
        return signedInUserId != null && !signedInUserId.equals(state.getUser().getId());
    }

    /** Own avatar label. Blank (drawn as a neutral mark) until the user is known. */
    @NonNull
    static String ownAvatarName(@Nullable UserDto user) {
        String name = user == null ? null : user.getDisplayName();
        return name == null ? "" : name;
    }
}