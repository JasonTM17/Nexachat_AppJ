package com.nexachat.feature.group;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.nexachat.R;
import com.nexachat.core.common.ApiError;
import com.nexachat.data.GroupMember;
import java.util.List;

/** Maps group-operation errors to localized UI strings. */
public final class GroupErrorText {
    private GroupErrorText() {}

    public static int resource(@NonNull ApiError error) {
        if (error instanceof ApiError.Network) return R.string.error_network;
        if (error instanceof ApiError.InvalidCredentials) return R.string.error_invalid_credentials;
        if (error instanceof ApiError.PhoneExists) return R.string.error_phone_exists;
        if (error instanceof ApiError.RateLimited) return R.string.error_rate_limited;
        return R.string.error_generic;
    }

    /**
     * @return the sole-admin explanation when the loaded roster shows that this
     * caller is the only admin and other members remain; otherwise {@code 0}
     */
    public static int soleAdminLeaveExplanation(
            boolean iAmAdmin,
            @Nullable String myUserId,
            @NonNull List<GroupMember> members) {
        if (!iAmAdmin || myUserId == null || members.size() <= 1) {
            return 0;
        }
        boolean seenSelf = false;
        for (GroupMember member : members) {
            if (!member.isAdmin()) {
                continue;
            }
            if (myUserId.equals(member.getUserId())) {
                seenSelf = true;
            } else {
                return 0;
            }
        }
        return seenSelf ? R.string.group_leave_only_admin : 0;
    }
}
