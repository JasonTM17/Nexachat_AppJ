package com.nexachat.report;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReportRepository extends JpaRepository<Report, UUID> {

    /**
     * The OPEN-report dedupe: one reporter can hold at most one OPEN report
     * per (type, id) — enforced by the partial unique index, looked up here
     * so a repeat submit returns the same row instead of erroring.
     */
    @Query("""
            SELECT r FROM Report r
            WHERE r.reporter.id = :reporterId
              AND r.targetType = :targetType AND r.targetId = :targetId
              AND r.status = com.nexachat.report.Report.Status.OPEN
            """)
    Optional<Report> findOpen(@Param("reporterId") UUID reporterId,
                              @Param("targetType") Report.TargetType targetType,
                              @Param("targetId") UUID targetId);

    /** Reporter's own filings, newest first — keyset on (created_at, id). */
    @Query("""
            SELECT r FROM Report r
            WHERE r.reporter.id = :reporterId
            ORDER BY r.createdAt DESC, r.id ASC
            """)
    List<Report> findMinePageFirst(@Param("reporterId") UUID reporterId,
                                   Pageable pageable);

    @Query("""
            SELECT r FROM Report r
            WHERE r.reporter.id = :reporterId
              AND (r.createdAt < :cursorCreatedAt
                   OR (r.createdAt = :cursorCreatedAt AND r.id > :cursorId))
            ORDER BY r.createdAt DESC, r.id ASC
            """)
    List<Report> findMinePageAfter(@Param("reporterId") UUID reporterId,
                                   @Param("cursorCreatedAt") Instant cursorCreatedAt,
                                   @Param("cursorId") UUID cursorId,
                                   Pageable pageable);
}
