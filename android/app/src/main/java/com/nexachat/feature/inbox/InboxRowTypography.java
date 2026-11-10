package com.nexachat.feature.inbox;

import android.graphics.Typeface;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.content.res.ResourcesCompat;
import com.nexachat.R;

/** Inbox row text weight without replacing the theme/typeface family. */
public final class InboxRowTypography {
    private InboxRowTypography() {}

    public static void applyUnreadWeight(@NonNull TextView view, boolean unread) {
        Typeface resolved = view.getTypeface();
        if (resolved == null) {
            resolved = ResourcesCompat.getFont(view.getContext(), R.font.inter);
        }
        int style = unread ? Typeface.BOLD : Typeface.NORMAL;
        view.setTypeface(Typeface.create(resolved, style), style);
    }
}
