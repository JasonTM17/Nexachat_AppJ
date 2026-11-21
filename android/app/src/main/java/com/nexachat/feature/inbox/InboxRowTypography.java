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
        applyStyle(view, unread ? Typeface.BOLD : Typeface.NORMAL);
    }

    /**
     * Deleted last-message previews stay italic and never pick up unread bold,
     * so a tombstone does not look like a live unread line.
     */
    public static void applyPreviewStyle(
            @NonNull TextView view, boolean unread, boolean deleted) {
        applyPreviewStyle(view, unread, deleted, false);
    }

    public static void applyPreviewStyle(
            @NonNull TextView view, boolean unread, boolean deleted, boolean draft) {
        int style;
        if (deleted) {
            style = Typeface.ITALIC;
        } else if (draft) {
            style = Typeface.ITALIC;
        } else if (unread) {
            style = Typeface.BOLD;
        } else {
            style = Typeface.NORMAL;
        }
        applyStyle(view, style);
    }

    private static void applyStyle(@NonNull TextView view, int style) {
        Typeface resolved = view.getTypeface();
        if (resolved == null) {
            resolved = ResourcesCompat.getFont(view.getContext(), R.font.inter);
        }
        view.setTypeface(Typeface.create(resolved, style), style);
    }
}
