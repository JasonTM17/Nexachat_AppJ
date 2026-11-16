package com.nexachat.report.dto;

import com.nexachat.report.Report;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * {@code POST /reports} body. {@code messageSnapshot} is only meaningful for
 * {@code MESSAGE} targets whose content the server cannot read (E2EE): the
 * reporter supplies the evidence text and the row is marked
 * reporter-provided. For plaintext targets the server copies the message
 * itself and ignores this field.
 */
public record CreateReportRequest(
        @NotNull(message = "targetType is required")
        Report.TargetType targetType,

        @NotNull(message = "targetId is required")
        UUID targetId,

        @NotNull(message = "reason is required")
        Report.Reason reason,

        @Size(max = 500, message = "details must be at most 500 characters")
        String details,

        @Size(max = 500, message = "messageSnapshot must be at most 500 characters")
        String messageSnapshot
) {
}
