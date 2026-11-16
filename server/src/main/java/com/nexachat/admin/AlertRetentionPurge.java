package com.nexachat.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Bounded purge of closed alerts past the configured retention window, on the
 * same opt-in + dry-run convention the global retention jobs use. Open and
 * acknowledged rows are never purged — only resolved/dismissed evidence has
 * a shelf life.
 */
@Component
public class AlertRetentionPurge {

    private static final Logger log = LoggerFactory.getLogger(AlertRetentionPurge.class);

    private final AlertRepository alertRepository;
    private final AdminProperties properties;

    public AlertRetentionPurge(AlertRepository alertRepository, AdminProperties properties) {
        this.alertRepository = alertRepository;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${nexachat.admin.alerts.retention.interval-ms:3600000}",
            initialDelayString = "${nexachat.admin.alerts.retention.interval-ms:3600000}")
    public void run() {
        AdminProperties.Alerts.Retention retention = properties.alerts().retention();
        if (!retention.enabled()) {
            return;
        }
        Instant cutoff = Instant.now().minus(retention.retentionDays(), ChronoUnit.DAYS);
        if (retention.dryRun()) {
            long count = alertRepository.countClosedOlderThan(cutoff);
            if (count > 0) {
                log.info("Alert retention dry-run: {} closed alert(s) older than {} day(s)",
                        count, retention.retentionDays());
            }
            return;
        }
        int deleted = alertRepository.purgeClosed(cutoff, Math.max(1, retention.batchSize()));
        if (deleted > 0) {
            log.info("Alert retention purged {} closed alert(s) older than {} day(s)",
                    deleted, retention.retentionDays());
        }
    }
}
