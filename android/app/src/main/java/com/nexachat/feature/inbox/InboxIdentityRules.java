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
        return needsProfileLoad(state, null, false);
    }

    /**
     * The shared profile state also survives a logout to login transition. When the cached
     * user belongs to a different account than the signed-in session, the previous identity
     * must be reloaded rather than rendered.
     *
     * <p>{@code alreadyAttempted} marks that this inbox entry already ran an automatic load
     * for this session id. A settled failure or a surviving mismatch then stays quiet — the
     * next inbox entry or a changed session id re-arms the retry, but inside one entry a dead
     * server cannot turn the state observer into a retry loop.
     */
    static boolean needsProfileLoad(
            @Nullable ProfileViewModel.UiState state,
            @Nullable String signedInUserId,
            boolean alreadyAttempted) {
        if (state == null || state.getUser() == null) {
            if (state != null && state.getError() != null) {
                return !alreadyAttempted;
            }
            return true;
        }
        return signedInUserId != null
                && !signedInUserId.equals(state.getUser().getId())
                && !alreadyAttempted;
    }

    /**
     * Only a user verified against the signed-in session may render. While the session id is
     * unresolved the avatar stays neutral, so a stale cached identity never paints in the gap
     * before the next load lands.
     */
    @Nullable
    static UserDto renderableUser(@Nullable UserDto user, @Nullable String signedInUserId) {
        return user != null && signedInUserId != null && signedInUserId.equals(user.getId())
                ? user
                : null;
    }

    /** Own avatar label. Blank (drawn as a neutral mark) until the user is known. */
    @NonNull
    static String ownAvatarName(@Nullable UserDto user) {
        String name = user == null ? null : user.getDisplayName();
        return name == null ? "" : name;
    }
}