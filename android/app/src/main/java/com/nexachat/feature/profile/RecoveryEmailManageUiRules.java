package com.nexachat.feature.profile;

import androidx.annotation.Nullable;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.network.dto.UserDto;

/** View-state rules for the recovery-email manage screen. */
public final class RecoveryEmailManageUiRules {

    private RecoveryEmailManageUiRules() {}

    public static boolean showInitialLoading(
            boolean loading, @Nullable UserDto user, @Nullable ApiError error) {
        return loading && user == null && error == null;
    }
}
