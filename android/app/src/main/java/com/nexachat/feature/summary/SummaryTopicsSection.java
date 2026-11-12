package com.nexachat.feature.summary;

import android.content.Context;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.nexachat.R;
import java.util.List;

/** Wraps summary topic chips so long topic lists reflow instead of overflowing horizontally. */
public final class SummaryTopicsSection {

    private SummaryTopicsSection() {}

    @NonNull
    public static ChipGroup build(@NonNull Context context, @NonNull List<String> topics) {
        ChipGroup group = new ChipGroup(context);
        group.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        group.setSingleLine(false);
        group.setChipSpacingHorizontal(context.getResources().getDimensionPixelSize(R.dimen.space_xs));
        group.setChipSpacingVertical(context.getResources().getDimensionPixelSize(R.dimen.space_xs));
        for (String topic : topics) {
            Chip chip = new Chip(context);
            chip.setText(context.getString(R.string.a11y_summary_topic_chip, topic));
            chip.setCheckable(false);
            chip.setClickable(false);
            group.addView(chip);
        }
        return group;
    }
}
