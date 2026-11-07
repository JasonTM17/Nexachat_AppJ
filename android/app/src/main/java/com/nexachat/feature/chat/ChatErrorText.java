package com.nexachat.feature.chat;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import com.nexachat.R;
import com.nexachat.core.common.ApiError;

/** Resolves chat API failures to localized resources for Android views. */
public final class ChatErrorText {
    private ChatErrorText() {}

    @StringRes
    public static int messageId(@NonNull ApiError error) {
        return ChatErrorIds.messageId(error);
    }
}
