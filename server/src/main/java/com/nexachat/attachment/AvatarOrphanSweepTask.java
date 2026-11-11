package com.nexachat.attachment;

import com.nexachat.ops.ScheduledAdvisoryLock;
import com.nexachat.ops.ScheduledLockIds;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Operator-run sweep for avatar files that no longer have a database owner.
 *
 * <p>Avatar replacement stopped leaking the previous file in the L9 fix, but
 * files orphaned before that fix (and files left by an interrupted upload)
 * still occupy the volume. Each run lists {@code avatars/}, keeps every key
 * referenced by a user or group avatar URL, keeps files younger than the
 * configured grace age, and deletes at most one bounded batch of the rest.
 * Dry-run is the default; deletion happens only after an operator explicitly
 * disables it.</p>
 *
 * <p>Safety properties: only the server-generated avatar key shape is
 * considered, the run is serialized across instances with a PostgreSQL
 * advisory lock, and logs/metrics contain counts only (no file names, user
 * identifiers, or URLs).</p>
 */
@Component
@Profile("!test")
public class AvatarOrphanSweepTask {

    private static final Logger log = LoggerFactory.getLogger(AvatarOrphanSweepTask.class);

    private static final String RUNS = "nexachat.avatar.sweep.runs";
    private static final String FILES_OBSERVED = "nexachat.avatar.sweep.files.observed";
    private static final String FILES_DELETED = "nexachat.avatar.sweep.files.deleted";
    private static final String FILES_KEPT = "nexachat.avatar.sweep.files.kept";
    private static final String ERRORS = "nexachat.avatar.sweep.errors";

    private static final String AVATARS = "avatars";

    private final StorageService storageService;
    private final AvatarSweepProperties properties;
    private final ScheduledAdvisoryLock advisoryLock;
    private final JdbcTemplate jdbcTemplate;
    private final MeterRegistry meterRegistry;
    private final AtomicBoolean running = new AtomicBoolean();

    public AvatarOrphanSweepTask(StorageService storageService,
                                 AvatarSweepProperties properties,
                                 ScheduledAdvisoryLock advisoryLock,
                                 JdbcTemplate jdbcTemplate,
                                 MeterRegistry meterRegistry) {
        this.storageService = storageService;
        this.properties = properties;
        this.advisoryLock = advisoryLock;
        this.jdbcTemplate = jdbcTemplate;
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(fixedDelayString = "${nexachat.avatar-sweep.interval-ms:86400000}")
    public void scheduledSweep() {
        runOnce();
    }

    /**
     * Runs one batch. Returns empty when the sweep is disabled, already
     * running in this process, serialized out by another instance, or failed
     * before producing a result; the scheduled path never throws.
     */
    public Optional<SweepResult> runOnce() {
        if (!properties.isEnabled()) {
            return Optional.empty();
        }
        if (!running.compareAndSet(false, true)) {
            return Optional.empty();
        }
        try {
            if (!advisoryLock.tryAcquire(ScheduledLockIds.AVATAR_ORPHAN_SWEEP)) {
                return Optional.empty();
            }
            try {
                return Optional.of(execute(properties.isDryRun()));
            } finally {
                advisoryLock.release(ScheduledLockIds.AVATAR_ORPHAN_SWEEP);
            }
        } catch (RuntimeException failure) {
            meterRegistry.counter(RUNS, "outcome", "failure").increment();
            log.warn("Avatar sweep failed ({})", failure.getClass().getSimpleName());
            return Optional.empty();
        } finally {
            running.set(false);
        }
    }

    private SweepResult execute(boolean dryRun) {
        Instant cutoff = Instant.now().minus(Duration.ofHours(properties.getMinAgeHours()));
        Set<String> referenced = referencedAvatarKeys();
        List<StorageService.StoredFileInfo> candidates = new ArrayList<>();
        int keptReferenced = 0;
        int keptRecent = 0;
        int ignored = 0;
        for (StorageService.StoredFileInfo file : storageService.list(AVATARS)) {
            if (!AvatarUrlPolicy.AVATAR_STORAGE_KEY.matcher(file.storageKey()).matches()) {
                ignored++;
                continue;
            }
            if (file.lastModified().isAfter(cutoff)) {
                keptRecent++;
                continue;
            }
            if (referenced.contains(file.storageKey())) {
                keptReferenced++;
                continue;
            }
            if (candidates.size() < properties.getBatchSize()) {
                candidates.add(file);
            }
        }

        int deleted = 0;
        int errors = 0;
        if (!dryRun) {
            for (StorageService.StoredFileInfo candidate : candidates) {
                try {
                    if (storageService.deleteIfExists(candidate.storageKey())) {
                        deleted++;
                    }
                } catch (RuntimeException failure) {
                    errors++;
                    log.warn("Avatar sweep skipped one file ({})",
                            failure.getClass().getSimpleName());
                }
            }
        }

        SweepResult result = new SweepResult(
                dryRun, candidates.size(), deleted, keptReferenced, keptRecent, ignored, errors);
        recordMetrics(result);
        if (dryRun) {
            log.info("Avatar sweep dry run: candidates={}, keptReferenced={}, keptRecent={}, ignored={}",
                    result.observed(), result.keptReferenced(), result.keptRecent(), result.ignored());
        } else {
            log.info("Avatar sweep deleted={} observed={} errors={}",
                    result.deleted(), result.observed(), result.errors());
        }
        return result;
    }

    /**
     * Keys referenced by a user or group avatar URL. Both tables store the
     * relative {@code /api/v1/files/} form, but legacy rows may carry an
     * absolute origin, a query string, a fragment, or an encoded path; the
     * URL is normalized before comparison.
     */
    private Set<String> referencedAvatarKeys() {
        List<String> urls = jdbcTemplate.queryForList("""
                SELECT avatar_url FROM users
                WHERE avatar_url LIKE '%/api/v1/files/%'
                UNION
                SELECT avatar_url FROM conversations
                WHERE avatar_url LIKE '%/api/v1/files/%'
                """, String.class);
        Set<String> keys = new HashSet<>();
        for (String url : urls) {
            String key = storageKeyFromUrl(url);
            if (key != null) {
                keys.add(key);
            }
        }
        return keys;
    }

    static String storageKeyFromUrl(String avatarUrl) {
        if (avatarUrl == null) {
            return null;
        }
        String value = avatarUrl.trim();
        if (value.isEmpty()) {
            return null;
        }
        String path = value;
        try {
            String parsedPath = URI.create(value).getPath();
            if (parsedPath != null && !parsedPath.isEmpty()) {
                path = parsedPath;
            }
        } catch (IllegalArgumentException malformed) {
            // Legacy row that is not a valid URI: strip the suffix manually.
            int fragment = value.indexOf('#');
            if (fragment >= 0) {
                value = value.substring(0, fragment);
            }
            int query = value.indexOf('?');
            if (query >= 0) {
                value = value.substring(0, query);
            }
            path = value;
        }
        int prefix = path.lastIndexOf(AvatarUrlPolicy.FILES_PATH);
        if (prefix < 0) {
            return null;
        }
        String key = path.substring(prefix + AvatarUrlPolicy.FILES_PATH.length());
        return key.isBlank() ? null : key;
    }

    private void recordMetrics(SweepResult result) {
        String outcome = result.dryRun()
                ? "dry_run"
                : result.errors() > 0 ? "partial" : "success";
        meterRegistry.counter(RUNS, "outcome", outcome).increment();
        if (result.dryRun()) {
            increment(FILES_OBSERVED, result.observed());
        } else {
            increment(FILES_DELETED, result.deleted());
            increment(ERRORS, result.errors());
        }
        increment(FILES_KEPT, result.keptReferenced(), "referenced");
        increment(FILES_KEPT, result.keptRecent(), "recent");
        increment(FILES_KEPT, result.ignored(), "unrecognized");
    }

    private void increment(String metric, int amount, String reason) {
        if (amount > 0) {
            meterRegistry.counter(metric, "reason", reason).increment(amount);
        }
    }

    private void increment(String metric, int amount) {
        if (amount > 0) {
            meterRegistry.counter(metric).increment(amount);
        }
    }

    /**
     * Outcome of one batch.
     *
     * @param dryRun          true when candidates were counted only
     * @param observed        candidates considered in this batch (bounded)
     * @param deleted         files removed (zero in a dry run)
     * @param keptReferenced  old avatar-shaped files kept because a row references them
     * @param keptRecent      avatar-shaped files younger than the grace age
     * @param ignored         files in {@code avatars/} that the server did not name
     * @param errors          per-file deletion failures
     */
    public record SweepResult(boolean dryRun,
                              int observed,
                              int deleted,
                              int keptReferenced,
                              int keptRecent,
                              int ignored,
                              int errors) {
    }
}
