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
        bind(root, error, onRetry, null);
    }

    /**
     * Binds an error; when {@code onChangeScope} is provided for a TOO_FEW error the action
     * button returns the user to the scope chips instead of retrying the same short scope.
     */
    public static void bind(
            @NonNull View root,
            @Nullable SummaryError error,
            @NonNull Runnable onRetry,
            @Nullable Runnable onChangeScope
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
                showRetry = onChangeScope != null;
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
            case E2EE_UNAVAILABLE:
                iconRes = R.drawable.ic_lock;
                titleRes = R.string.summary_error_e2ee_unavailable;
                descRes = null;
                showRetry = false;
                iconTintAttr = com.google.android.material.R.attr.colorOnSurfaceVariant;
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
        if (retry != null && retry instanceof com.google.android.material.button.MaterialButton) {
            com.google.android.material.button.MaterialButton retryButton =
                    (com.google.android.material.button.MaterialButton) retry;
            retryButton.setVisibility(showRetry ? View.VISIBLE : View.GONE);
            boolean changeScope = resolved == SummaryError.TOO_FEW && onChangeScope != null;
            retryButton.setText(changeScope
                    ? R.string.summary_action_change_scope
                    : R.string.summary_action_retry);
            retryButton.setIcon(changeScope
                    ? null
                    : androidx.core.content.ContextCompat.getDrawable(
                            root.getContext(), R.drawable.ic_refresh));
            retryButton.setOnClickListener(showRetry
                    ? v -> (changeScope ? onChangeScope : onRetry).run()
                    : null);
        }
    }
}
