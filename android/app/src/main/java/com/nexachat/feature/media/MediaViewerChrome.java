package com.nexachat.feature.media;

import androidx.annotation.Nullable;

/**
 * Decides the toolbar title/subtitle for the full-screen media viewer.
 * Visual media (images/videos) shows the sender name with the message time;
 * other documents keep the file name as the title.
 */
public final class MediaViewerChrome {

    private final String title;
    @Nullable private final String subtitle;

    private MediaViewerChrome(String title, @Nullable String subtitle) {
        this.title = title;
        this.subtitle = subtitle;
    }

    /**
     * Resolves the chrome for one attachment.
     *
     * @param visualMedia true for image/video attachments; documents keep the file name
     * @param senderName sender display name when known
     * @param sentAtLabel localized message date-time, already formatted by the caller;
     *                    null when no timestamp is available
     * @param fileName attachment display/file name
     * @param fallbackTitle title used when neither sender nor file name is available
     */
    public static MediaViewerChrome resolve(
            boolean visualMedia,
            @Nullable String senderName,
            @Nullable String sentAtLabel,
            @Nullable String fileName,
            String fallbackTitle) {
        if (!visualMedia) {
            return new MediaViewerChrome(
                    isBlank(fileName) ? fallbackTitle : fileName, null);
        }
        if (isBlank(senderName) && sentAtLabel == null) {
            return new MediaViewerChrome(
                    isBlank(fileName) ? fallbackTitle : fileName, null);
        }
        String title = isBlank(senderName) ? fileName : senderName;
        if (title == null || isBlank(title)) {
            title = fallbackTitle;
        }
        return new MediaViewerChrome(title, sentAtLabel);
    }

    public String getTitle() {
        return title;
    }

    @Nullable
    public String getSubtitle() {
        return subtitle;
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.trim().isEmpty();
    }
}
