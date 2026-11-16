package com.nexachat.report.dto;

import com.nexachat.report.Report;

import java.time.Instant;
import java.util.UUID;

/**
 * One report as its reporter sees it — {@code POST /reports} and each item
 * of {@code GET /reports/mine}.
 */
public record ReportResponse(
        UUID id,
        Report.TargetType targetType,
        UUID targetId,
        Report.Reason reason,
        String details,
        String messageSnapshot,
        Report.Status status,
        Instant createdAt
) {

    public static ReportResponse from(Report report) {
        return new ReportResponse(
                report.getId(), report.getTargetType(), report.getTargetId(),
                report.getReason(), report.getDetails(), report.getMessageSnapshot(),
                report.getStatus(), report.getCreatedAt());
    }
}
