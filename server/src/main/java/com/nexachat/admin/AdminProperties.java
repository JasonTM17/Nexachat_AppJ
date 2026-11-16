package com.nexachat.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Binds {@code nexachat.admin.*} for the admin console:
 * <ul>
 *   <li>{@code allowed-origins} — browser origins admitted by the dedicated
 *       admin CORS registration (empty = deny all cross-origin admin calls).</li>
 *   <li>{@code alerts.*} — sensitive-message scanning pipeline: kill switch,
 *       dry-run mode, bounded queue/backpressure, drain cadence.</li>
 *   <li>{@code reveal.*} — rate budget for audited content reveals.</li>
 *   <li>{@code retention.*} — purge of closed alerts (opt-in, dry-run first,
 *       matching the global retention-job convention).</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "nexachat.admin")
public record AdminProperties(
        List<String> allowedOrigins,
        Alerts alerts,
        Reveal reveal) {

    public record Alerts(
            boolean enabled,
            boolean dryRun,
            int queueCapacity,
            int batchSize,
            long drainIntervalMs,
            int snippetMaxChars,
            int revealContextRadius,
            int rulesCacheSeconds,
            Retention retention) {

        public record Retention(boolean enabled, boolean dryRun, int retentionDays,
                                int batchSize, long intervalMs) {
        }
    }

    public record Reveal(long perMinute) {
    }

    public AdminProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        alerts = alerts == null
                ? new Alerts(true, false, 1_000, 32, 500, 120, 3, 10,
                        new Alerts.Retention(false, true, 90, 200, 3_600_000))
                : alerts;
        reveal = reveal == null ? new Reveal(10) : reveal;
    }

    public boolean isAllowedOrigin(String origin) {
        return origin != null && allowedOrigins.contains(origin);
    }
}
