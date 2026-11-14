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
        return state == null || state.getUser() == null;
    }

    /** Own avatar label. Blank (drawn as a neutral mark) until the user is known. */
    @NonNull
    static String ownAvatarName(@Nullable UserDto user) {
        String name = user == null ? null : user.getDisplayName();
        return name == null ? "" : name;
    }
}