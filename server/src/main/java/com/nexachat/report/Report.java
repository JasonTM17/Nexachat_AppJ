package com.nexachat.report;

import com.nexachat.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Maps {@code reports} (V38): one abuse report per row, filed by
 * {@code reporter} against a polymorphic {@code (target_type, target_id)}.
 *
 * <p>{@code targetId} intentionally carries no FK — the admin console joins
 * whichever side it needs; the write path validates the target at submit.
 * {@code messageSnapshot} is the evidence copy: plaintext truncated to 500
 * chars for server-readable messages, or reporter-supplied text for E2EE
 * ones, marked by {@code snapshotReporterProvided}.
 */
@Entity
@Table(name = "reports")
public class Report {

    public enum TargetType {
        USER, MESSAGE, CONVERSATION
    }

    public enum Reason {
        SPAM, HARASSMENT, VIOLENCE, SEXUAL, SCAM, OTHER
    }

    public enum Status {
        OPEN, REVIEWED, DISMISSED
    }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reporter_id", nullable = false)
    private User reporter;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 12)
    private TargetType targetType;

    @Column(name = "target_id", nullable = false)
    private UUID targetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 12)
    private Reason reason;

    @Column(name = "details", length = 500)
    private String details;

    @Column(name = "message_snapshot", length = 500)
    private String messageSnapshot;

    @Column(name = "snapshot_reporter_provided", nullable = false)
    private boolean snapshotReporterProvided;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 9)
    private Status status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Report() {
        // JPA only
    }

    public static Report create(User reporter, TargetType targetType, UUID targetId,
                                Reason reason, String details, String messageSnapshot,
                                boolean snapshotReporterProvided, Instant createdAt) {
        Report report = new Report();
        report.id = UUID.randomUUID();
        report.reporter = reporter;
        report.targetType = targetType;
        report.targetId = targetId;
        report.reason = reason;
        report.details = details;
        report.messageSnapshot = messageSnapshot;
        report.snapshotReporterProvided = snapshotReporterProvided;
        report.status = Status.OPEN;
        report.createdAt = createdAt;
        return report;
    }

    public UUID getId() {
        return id;
    }

    public User getReporter() {
        return reporter;
    }

    public TargetType getTargetType() {
        return targetType;
    }

    public UUID getTargetId() {
        return targetId;
    }

    public Reason getReason() {
        return reason;
    }

    public String getDetails() {
        return details;
    }

    public String getMessageSnapshot() {
        return messageSnapshot;
    }

    public boolean isSnapshotReporterProvided() {
        return snapshotReporterProvided;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
