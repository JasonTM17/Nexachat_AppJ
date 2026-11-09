package com.nexachat.feature.label;

import androidx.annotation.Nullable;
import java.util.List;
import java.util.Locale;

/** Client-side limits mirrored from the labels API contract. */
public final class LabelRules {
    public static final int MAX_LABELS = 10;
    public static final int MAX_PER_CONVERSATION = 3;
    public static final int MAX_NAME_CODE_POINTS = 30;

    private LabelRules() {}

    public static String normalizeName(@Nullable String value) {
        return value == null ? "" : value.strip();
    }

    public static boolean isValidName(@Nullable String value) {
        String normalized = normalizeName(value);
        return normalized.codePointCount(0, normalized.length()) >= 1
                && normalized.codePointCount(0, normalized.length()) <= MAX_NAME_CODE_POINTS;
    }

    public static boolean hasDuplicateName(
            String name, List<ConversationLabel> labels, @Nullable String exceptId) {
        String normalized = normalizeName(name).toLowerCase(Locale.ROOT);
        return labels.stream().anyMatch(label -> !label.isPending()
                && !label.getId().equals(exceptId)
                && normalizeName(label.getName()).toLowerCase(Locale.ROOT).equals(normalized));
    }

    public static String toCsv(List<String> ids) {
        return String.join(",", ids);
    }
}
