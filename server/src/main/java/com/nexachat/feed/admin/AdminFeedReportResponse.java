package com.nexachat.feed.admin;

import com.nexachat.report.Report;

import java.time.Instant;
import java.util.UUID;

/**
 * One feed report as the admin console needs it: the report itself, the
 * reporter, and the current state of the post or comment it targets.
 * {@code targetBody} is the server's copy of the content (the report snapshot
 * is the same text for feed targets).
 */
public record AdminFeedReportResponse(
        UUID id,
        Report.TargetType targetType,
        UUID targetId,
        Report.Reason reason,
        String details,
        String messageSnapshot,
        Report.Status status,
        Instant createdAt,
        UUID reporterId,
        String reporterName,
        UUID authorId,
        String authorName,
        String targetBody,
        boolean targetDeleted,
        boolean targetHidden,
        Instant targetCreatedAt
) {
}
