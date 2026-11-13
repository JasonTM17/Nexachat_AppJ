package com.nexachat.feature.summary;

import android.content.res.ColorStateList;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.material.color.MaterialColors;
import com.nexachat.R;
import com.nexachat.summary.SummaryError;

/** Binds typed summary errors to the shared Android View include. */
public final class SummaryErrorViewHelper {

    private SummaryErrorViewHelper() {
    }

    public static void bind(
            @NonNull View root,
            @Nullable SummaryError error,
            @NonNull Runnable onRetry
    ) {
        SummaryError resolved = error == null ? SummaryError.UNKNOWN : error;
        ImageView icon = root.findViewById(R.id.summaryErrorIcon);
        TextView title = root.findViewById(R.id.summaryErrorText);
        TextView desc = root.findViewById(R.id.summaryErrorDesc);
        View retry = root.findViewById(R.id.summaryRetry);

        int iconRes;
        int titleRes;
        Integer descRes;
        boolean showRetry;
        int iconTintAttr;
        switch (resolved) {
            case TOO_FEW:
                iconRes = R.drawable.ic_chat_bubble_outline;
                titleRes = R.string.summary_error_too_few;
                descRes = R.string.summary_error_too_few_desc;
                showRetry = false;
                iconTintAttr = com.google.android.material.R.attr.colorOnSurfaceVariant;
                break;
            case RATE_LIMITED:
                iconRes = R.drawable.ic_hourglass_top;
                titleRes = R.string.summary_error_rate_limited;
                descRes = R.string.summary_error_rate_limited_desc;
                showRetry = true;
                iconTintAttr = com.google.android.material.R.attr.colorPrimary;
                break;
            case NOT_MEMBER:
                iconRes = R.drawable.ic_error_outline;
                titleRes = R.string.summary_error_not_member;
                descRes = null;
                showRetry = false;
                iconTintAttr = com.google.android.material.R.attr.colorError;
                break;
            case NETWORK:
                iconRes = R.drawable.ic_wifi_off;
                titleRes = R.string.summary_error_offline;
                descRes = null;
                showRetry = true;
                iconTintAttr = com.google.android.material.R.attr.colorError;
                break;
            case UNKNOWN:
            default:
                iconRes = R.drawable.ic_error_outline;
                titleRes = R.string.summary_error_generic;
                descRes = null;
                showRetry = true;
                iconTintAttr = com.google.android.material.R.attr.colorError;
                break;
        }

        if (icon != null) {
            icon.setImageResource(iconRes);
            int tint = MaterialColors.getColor(icon, iconTintAttr);
            icon.setImageTintList(ColorStateList.valueOf(tint));
        }
        if (title != null) {
            title.setText(titleRes);
        }
        if (desc != null) {
            if (descRes != null) {
                desc.setText(descRes);
                desc.setVisibility(View.VISIBLE);
            } else {
                desc.setVisibility(View.GONE);
            }
        }
        if (retry != null) {
            retry.setVisibility(showRetry ? View.VISIBLE : View.GONE);
            retry.setOnClickListener(showRetry ? v -> onRetry.run() : null);
        }
    }
}
