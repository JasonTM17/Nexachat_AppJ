package com.nexachat.feature.label;

import androidx.annotation.StringRes;
import com.nexachat.R;

/** Localized resource mapping for the label controls. */
public final class LabelText {
    private LabelText() {}

    @StringRes
    public static int errorMessage(LabelUiState.Error error) {
        return switch (error) {
            case VALIDATION -> R.string.label_error_validation;
            case CONFLICT -> R.string.label_error_conflict;
            case RATE_LIMITED -> R.string.label_error_rate_limit;
            case OFFLINE -> R.string.label_error_offline;
            case LABEL_LIMIT -> R.string.label_error_label_limit;
            case ASSIGNMENT_LIMIT -> R.string.label_error_assignment_limit;
            case NONE -> 0;
            case GENERIC -> R.string.label_error_generic;
            default -> R.string.label_error_generic;
        };
    }

    @StringRes
    public static int colorName(LabelColor color) {
        return switch (color) {
            case INDIGO -> R.string.label_color_indigo;
            case BLUE -> R.string.label_color_blue;
            case CYAN -> R.string.label_color_cyan;
            case TEAL -> R.string.label_color_teal;
            case EMERALD -> R.string.label_color_emerald;
            case AMBER -> R.string.label_color_amber;
            case ROSE -> R.string.label_color_rose;
            case PURPLE -> R.string.label_color_purple;
            default -> R.string.label_color_indigo;
        };
    }
}
