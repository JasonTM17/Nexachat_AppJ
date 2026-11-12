package com.nexachat.feature.chat;

import android.widget.ImageButton;
import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import com.nexachat.R;

/** Send vs save affordance while the composer is in edit mode. */
public final class ChatComposerEditPresentation {

    private ChatComposerEditPresentation() {}

    public static int sendIconRes(boolean editing) {
        return editing ? R.drawable.ic_check : R.drawable.ic_send;
    }

    @StringRes
    public static int sendContentDescriptionRes(boolean editing) {
        return editing ? R.string.action_save_changes : R.string.cd_send;
    }

    public static void applySendButton(@NonNull ImageButton sendButton, boolean editing) {
        sendButton.setImageResource(sendIconRes(editing));
        sendButton.setContentDescription(
                sendButton.getContext().getString(sendContentDescriptionRes(editing)));
    }
}
