package com.nexachat.feature.chat;

import android.view.View;
import android.widget.TextView;
import androidx.annotation.NonNull;
import com.nexachat.R;

/** Native Android view binders for the composer banners. Sheets live in their own fragments. */
public final class ChatInteractionsViews {

    private ChatInteractionsViews() {}

    public static void bindReplyBanner(@NonNull View root, @NonNull String text, @NonNull Runnable cancel) {
        TextView preview = root.findViewById(R.id.replyPreview);
        preview.setText(text);
        root.findViewById(R.id.cancelReplyEdit).setContentDescription(root.getContext().getString(R.string.cd_cancel_reply));
        root.findViewById(R.id.cancelReplyEdit).setOnClickListener(v -> cancel.run());
        root.setVisibility(View.VISIBLE);
    }

    /** The banner cycles pins on tap, so it must say which pin is showing. */
    public static void bindPinnedBanner(
            @NonNull View root,
            @NonNull String text,
            int position,
            int total,
            @NonNull Runnable jump) {
        ((TextView) root.findViewById(R.id.pinnedText)).setText(text);
        TextView count = root.findViewById(R.id.pinnedCount);
        String spoken;
        if (total > 1) {
            count.setText(root.getContext().getString(
                    R.string.pinned_count, position + 1, total));
            count.setVisibility(View.VISIBLE);
            spoken = root.getContext().getResources().getQuantityString(
                    R.plurals.pinned_position, total, position + 1, total);
        } else {
            count.setVisibility(View.GONE);
            spoken = root.getContext().getString(R.string.pinned_title);
        }
        root.setContentDescription(spoken + ", " + text);
        root.setOnClickListener(v -> jump.run());
        root.setVisibility(View.VISIBLE);
    }
}
