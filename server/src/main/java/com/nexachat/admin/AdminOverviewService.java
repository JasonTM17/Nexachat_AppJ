package com.nexachat.admin;

import com.nexachat.call.CallRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * Real-query system overview for the admin dashboard. Answers are cached for
 * {@value #CACHE_TTL_SECONDS} seconds: the dashboard polls this endpoint, and
 * the heaviest aggregates (per-day series, database size) must not fan out
 * per click on a busy instance.
 */
@Service
public class AdminOverviewService {

    private static final long CACHE_TTL_SECONDS = 30;
    private static final List<Integer> ALLOWED_DAYS = List.of(7, 30, 90);

    public record Kpis(long registrationsToday, long dau, long wau, long messagesToday,
                       long activeCalls, long databaseBytes, long uploadsBytes,
                       long pendingAlerts, double errorRate,
                       QueueHealth queue) {
    }

    public record QueueHealth(int depth, long dropped, long scanned, long alertsCreated,
                              boolean enabled, boolean dryRun) {
    }

    public record SeriesPoint(String date, long registrations, long messages) {
    }

    public record Overview(Kpis kpis, int days, List<SeriesPoint> series, Instant generatedAt) {
    }

    private record Cached(Instant at, int days, Overview overview) {
    }

    private final JdbcTemplate jdbcTemplate;
    private final CallRegistry callRegistry;
    private final MeterRegistry meterRegistry;
    private final AlertScanQueue scanQueue;
    private final AdminProperties properties;
    private final Path uploadsRoot;
    private final AtomicReference<Cached> cache = new AtomicReference<>();

    public AdminOverviewService(JdbcTemplate jdbcTemplate,
                                CallRegistry callRegistry,
                                MeterRegistry meterRegistry,
                                AlertScanQueue scanQueue,
                                AdminProperties properties,
                                @Value("${nexachat.storage.root:./data/uploads}") String storageRoot) {
        this.jdbcTemplate = jdbcTemplate;
        this.callRegistry = callRegistry;
        this.meterRegistry = meterRegistry;
        this.scanQueue = scanQueue;
        this.properties = properties;
        this.uploadsRoot = Path.of(storageRoot);
    }

    @Transactional(readOnly = true)
    public Overview overview(int days) {
        int safeDays = ALLOWED_DAYS.contains(days) ? days : 7;
        Cached cached = cache.get();
        if (cached != null && cached.days() == safeDays
                && cached.at().plusSeconds(CACHE_TTL_SECONDS).isAfter(Instant.now())) {
            return cached.overview();
        }
        Overview overview = new Overview(kpis(), safeDays, series(safeDays), Instant.now());
        cache.set(new Cached(Instant.now(), safeDays, overview));
        return overview;
    }

    private Kpis kpis() {
        long registrationsToday = scalar(
                "SELECT count(*) FROM users"
                        + " WHERE created_at >= date_trunc('day', now() AT TIME ZONE 'UTC')"
                        + " AT TIME ZONE 'UTC'");
        long dau = scalar("""
                SELECT count(*) FROM users
                WHERE deleted_at IS NULL AND last_seen_at >= now() - interval '24 hours'
                """);
        long wau = scalar("""
                SELECT count(*) FROM users
                WHERE deleted_at IS NULL AND last_seen_at >= now() - interval '7 days'
                """);
        long messagesToday = scalar(
                "SELECT count(*) FROM messages"
                        + " WHERE created_at >= date_trunc('day', now() AT TIME ZONE 'UTC')"
                        + " AT TIME ZONE 'UTC'");
        long activeCalls = callRegistry == null ? 0 : callRegistry.activeCallSessions().size();
        long databaseBytes = scalar("SELECT pg_database_size(current_database())");
        long pendingAlerts = scalar("SELECT count(*) FROM message_alerts WHERE status = 'OPEN'");
        return new Kpis(registrationsToday, dau, wau, messagesToday, activeCalls,
                databaseBytes, uploadsBytes(), pendingAlerts, httpErrorRate(), queueHealth());
    }

    private QueueHealth queueHealth() {
        if (scanQueue == null) {
            return new QueueHealth(0, 0, 0, 0, properties.alerts().enabled(), properties.alerts().dryRun());
        }
        return new QueueHealth(scanQueue.depth(), scanQueue.dropped(), scanQueue.scanned(),
                scanQueue.alertsCreated(), properties.alerts().enabled(), properties.alerts().dryRun());
    }

    /**
     * Per-day registrations and message counts over the trailing window. All
     * day buckets are UTC — {@code first} is a UTC date, so the bucketing and
     * the series bound must resolve in UTC too rather than in the DB session
     * timezone, where {@code ::date} and {@code CURRENT_DATE} otherwise drift.
     */
    private List<SeriesPoint> series(int days) {
        LocalDate first = LocalDate.now(ZoneOffset.UTC).minusDays(days - 1L);
        List<SeriesPoint> points = jdbcTemplate.query("""
                        WITH days AS (
                            SELECT generate_series(?::date, (now() AT TIME ZONE 'UTC')::date,
                                                   interval '1 day')::date AS day
                        ), regs AS (
                            SELECT (created_at AT TIME ZONE 'UTC')::date AS day, count(*) AS n
                            FROM users
                            WHERE (created_at AT TIME ZONE 'UTC')::date >= ?::date GROUP BY 1
                        ), msgs AS (
                            SELECT (created_at AT TIME ZONE 'UTC')::date AS day, count(*) AS n
                            FROM messages
                            WHERE (created_at AT TIME ZONE 'UTC')::date >= ?::date GROUP BY 1
                        )
                        SELECT days.day, COALESCE(regs.n, 0) AS registrations,
                               COALESCE(msgs.n, 0) AS messages
                        FROM days
                        LEFT JOIN regs ON regs.day = days.day
                        LEFT JOIN msgs ON msgs.day = days.day
                        ORDER BY days.day
                        """,
                (rs, rowNum) -> new SeriesPoint(
                        rs.getDate(1).toString(), rs.getLong(2), rs.getLong(3)),
                Date.valueOf(first), Date.valueOf(first), Date.valueOf(first));
        return points;
    }

    /**
     * 5xx share of instrumented HTTP requests since boot. Reads the
     * {@code http.server.requests} timers; zero traffic reports 0 rather than NaN.
     */
    private double httpErrorRate() {
        if (meterRegistry == null) {
            return 0.0;
        }
        double total = 0;
        double errors = 0;
        for (Timer timer : meterRegistry.find("http.server.requests").timers()) {
            double count = timer.count();
            total += count;
            String status = timer.getId().getTag("status");
            if (status != null && status.startsWith("5")) {
                errors += count;
            }
        }
        return total <= 0 ? 0.0 : errors / total;
    }

    private long uploadsBytes() {
        if (!Files.isDirectory(uploadsRoot)) {
            return 0;
        }
        try (Stream<Path> paths = Files.walk(uploadsRoot)) {
            return paths.filter(Files::isRegularFile)
                    .mapToLong(path -> {
                        try {
                            return Files.size(path);
                        } catch (IOException e) {
                            return 0L;
                        }
                    }).sum();
        } catch (IOException | SecurityException e) {
            return 0;
        }
    }

    private long scalar(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }
}
