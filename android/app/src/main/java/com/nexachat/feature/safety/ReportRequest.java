package com.nexachat.feature.safety;

import androidx.annotation.Nullable;
import com.squareup.moshi.Json;

/** `POST /reports` body; `details` and `messageSnapshot` are optional. */
public final class ReportRequest {
    @Json(name = "targetType") final String targetType;
    @Json(name = "targetId") final String targetId;
    @Json(name = "reason") final String reason;
    @Json(name = "details") @Nullable final String details;
    @Json(name = "messageSnapshot") @Nullable final String messageSnapshot;

    public ReportRequest(
            String targetType,
            String targetId,
            String reason,
            @Nullable String details,
            @Nullable String messageSnapshot) {
        this.targetType = targetType;
        this.targetId = targetId;
        this.reason = reason;
        this.details = details;
        this.messageSnapshot = messageSnapshot;
    }
}
