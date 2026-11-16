package com.nexachat.feature.safety;

import androidx.annotation.Nullable;

/** Server report row returned by `POST /reports` and `GET /reports/mine`. */
public final class ReportDto {
    @Nullable private final String id;
    @Nullable private final String targetType;
    @Nullable private final String targetId;
    @Nullable private final String reason;
    @Nullable private final String details;
    @Nullable private final String status;
    @Nullable private final String createdAt;

    public ReportDto() {
        this(null, null, null, null, null, null, null);
    }

    public ReportDto(
            @Nullable String id,
            @Nullable String targetType,
            @Nullable String targetId,
            @Nullable String reason,
            @Nullable String details,
            @Nullable String status,
            @Nullable String createdAt) {
        this.id = id;
        this.targetType = targetType;
        this.targetId = targetId;
        this.reason = reason;
        this.details = details;
        this.status = status;
        this.createdAt = createdAt;
    }

    @Nullable public String getId() {
        return id;
    }

    @Nullable public String getStatus() {
        return status;
    }
}
