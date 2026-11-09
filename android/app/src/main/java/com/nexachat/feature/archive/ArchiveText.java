package com.nexachat.feature.archive;

import android.content.Context;
import android.text.format.DateFormat;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.nexachat.R;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/** Localized labels shared by archive rows and their accessibility descriptions. */
final class ArchiveText {

    private ArchiveText() { }

    @NonNull
    static String senderName(
            @NonNull Context context,
            @NonNull ArchiveUiState state,
            @Nullable String senderId) {
        if (senderId != null) {
            for (ArchiveSender sender : state.getSenders()) {
                if (senderId.equals(sender.getId())
                        && sender.getDisplayName() != null
                        && !sender.getDisplayName().isBlank()) {
                    return sender.getDisplayName();
                }
            }
        }
        return context.getString(R.string.archive_unknown_sender);
    }

    @NonNull
    static String date(@NonNull Context context, @Nullable String createdAt) {
        if (createdAt == null) {
            return "";
        }
        try {
            return DateFormat.getMediumDateFormat(context)
                    .format(java.util.Date.from(Instant.parse(createdAt)));
        } catch (DateTimeParseException ignored) {
            return "";
        }
    }

    @NonNull
    static String details(
            @NonNull Context context,
            @NonNull ArchiveUiState state,
            @NonNull ArchivePage.Item item) {
        return context.getString(
                R.string.archive_details_format,
                senderName(context, state, item.getSenderId()),
                date(context, item.getCreatedAt()));
    }

    private static final int MAX_ACCESSIBILITY_TITLE_LENGTH = 160;

    @NonNull
    static String accessibilityDescription(
            @NonNull Context context,
            @NonNull ArchiveUiState state,
            @NonNull String title,
            @NonNull ArchivePage.Item item) {
        String safeTitle = title.length() <= MAX_ACCESSIBILITY_TITLE_LENGTH
                ? title
                : title.substring(0, MAX_ACCESSIBILITY_TITLE_LENGTH - 1) + "…";
        return context.getString(
                R.string.archive_media_description,
                safeTitle,
                senderName(context, state, item.getSenderId()),
                date(context, item.getCreatedAt()));
    }
}
