package com.nexachat.feature.feed;

import androidx.annotation.Nullable;

/** HTTP failure from a feed endpoint; carries the server error code when present. */
public final class FeedApiException extends RuntimeException {
    private final int status;
    @Nullable private final String code;

    public FeedApiException(int status, @Nullable String code) {
        super(code == null ? "HTTP " + status : code);
        this.status = status;
        this.code = code;
    }

    public int getStatus() {
        return status;
    }

    @Nullable public String getCode() {
        return code;
    }

    public boolean isRateLimited() {
        return status == 429 || "RATE_LIMITED".equals(code);
    }

    /** The post or comment is gone (deleted, hidden, or never visible to the caller). */
    public boolean isGone() {
        return status == 404 || "POST_NOT_FOUND".equals(code) || "COMMENT_NOT_FOUND".equals(code);
    }
}
