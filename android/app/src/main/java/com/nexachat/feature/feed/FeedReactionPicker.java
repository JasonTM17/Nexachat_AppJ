package com.nexachat.feature.feed;

import android.content.Context;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.widget.TextViewCompat;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.nexachat.R;
import com.nexachat.core.ui.ReactionSet;
import java.util.function.Consumer;

/** The shared emoji row, shown as a small dialog over a post. */
public final class FeedReactionPicker {

    private FeedReactionPicker() { }

    public static void show(@NonNull Context context, @NonNull Consumer<String> onPick) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        int padding = context.getResources().getDimensionPixelSize(R.dimen.space_md);
        row.setPadding(padding, padding, padding, padding);
        int target = context.getResources().getDimensionPixelSize(R.dimen.min_touch_target);
        int gap = context.getResources().getDimensionPixelSize(R.dimen.space_xs);
        AlertDialog dialog = new MaterialAlertDialogBuilder(context).setView(row).create();
        for (int i = 0; i < ReactionSet.size(); i++) {
            String emoji = ReactionSet.emoji(i);
            TextView tile = new TextView(context);
            tile.setText(emoji);
            tile.setContentDescription(context.getString(ReactionSet.label(i)));
            TextViewCompat.setTextAppearance(tile, R.style.TextAppearance_NexaChat_TitleLarge);
            tile.setGravity(Gravity.CENTER);
            tile.setMinWidth(target);
            tile.setMinHeight(target);
            tile.setBackgroundResource(R.drawable.bg_reaction_tile);
            tile.setFocusable(true);
            tile.setOnClickListener(ignored -> {
                dialog.dismiss();
                onPick.accept(emoji);
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) {
                params.setMarginStart(gap);
            }
            row.addView(tile, params);
        }
        dialog.show();
    }
}
