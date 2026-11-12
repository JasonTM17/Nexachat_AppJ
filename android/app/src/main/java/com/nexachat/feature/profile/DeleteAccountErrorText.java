package com.nexachat.feature.profile;

import android.content.Context;
import androidx.annotation.NonNull;
import com.nexachat.R;
import com.nexachat.core.common.ApiError;
import com.nexachat.feature.auth.ApiErrorText;

public final class DeleteAccountErrorText {
    private DeleteAccountErrorText() {}

    @NonNull
    public static CharSequence text(@NonNull Context context, @NonNull ApiError error) {
        if (error instanceof ApiError.WrongPassword) {
            return context.getText(R.string.error_wrong_password);
        }
        if (error instanceof ApiError.InvalidOrExpiredCode) {
            return context.getText(R.string.delete_account_code_invalid);
        }
        return ApiErrorText.text(context, error);
    }
}
