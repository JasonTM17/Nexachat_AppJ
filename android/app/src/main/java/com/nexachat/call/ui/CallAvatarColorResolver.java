package com.nexachat.call.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.widget.TextView;
import androidx.core.content.ContextCompat;
import com.nexachat.R;
import com.nexachat.core.ui.components.AvatarKt;

/** Applies the existing app avatar palette index to a native call-screen initials view. */
public final class CallAvatarColorResolver {
    private static final int[] BACKGROUND_RESOURCES = {
            R.color.call_avatar_bg_0, R.color.call_avatar_bg_1,
            R.color.call_avatar_bg_2, R.color.call_avatar_bg_3,
            R.color.call_avatar_bg_4, R.color.call_avatar_bg_5,
            R.color.call_avatar_bg_6, R.color.call_avatar_bg_7
    };
    private static final int[] FOREGROUND_RESOURCES = {
            R.color.call_avatar_fg_0, R.color.call_avatar_fg_1,
            R.color.call_avatar_fg_2, R.color.call_avatar_fg_3,
            R.color.call_avatar_fg_4, R.color.call_avatar_fg_5,
            R.color.call_avatar_fg_6, R.color.call_avatar_fg_7
    };

    private CallAvatarColorResolver() { }

    public static void apply(Context context, TextView initialsView, String name) {
        if (context == null || initialsView == null) return;
        int index = AvatarKt.avatarPaletteIndex(name == null ? "" : name);
        initialsView.setBackgroundTintList(ColorStateList.valueOf(
                ContextCompat.getColor(context, BACKGROUND_RESOURCES[index])));
        initialsView.setTextColor(ContextCompat.getColor(context, FOREGROUND_RESOURCES[index]));
    }
}
