package com.nexachat.observability;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reports Flyway schema state on the readiness probe. The health details
 * (version, description, applied count) stay behind
 * {@code management.endpoint.health.show-details=never}; the public endpoint
 * exposes only the component status.
 *
 * <p>When Flyway is not configured (the {@code test} profile excludes it) the
 * indicator reports UP with a "not configured" detail so the group keeps a
 * stable membership; a real deployment always has Flyway.
 */
@Component
public class FlywayHealthIndicator implements HealthIndicator {

    private final ObjectProvider<Flyway> flywayProvider;

    public FlywayHealthIndicator(ObjectProvider<Flyway> flywayProvider) {
        this.flywayProvider = flywayProvider;
    }

    @Override
    public Health health() {
        Flyway flyway = flywayProvider.getIfAvailable();
        if (flyway == null) {
            return Health.up().withDetail("status", "not configured").build();
        }
        try {
            MigrationInfoService info = flyway.info();
            MigrationInfo current = info.current();
            flyway.validate();
            int pending = info.pending() == null ? 0 : info.pending().length;
            if (pending > 0) {
                return Health.down()
                        .withDetail("pending", pending)
                        .withDetail("version", current == null ? "none" : current.getVersion().toString())
                        .build();
            }
            return Health.up()
                    .withDetail("version", current == null ? "none" : current.getVersion().toString())
                    .withDetail("description", current == null ? null : current.getDescription())
                    .withDetail("applied", info.applied() == null ? 0 : info.applied().length)
                    .build();
        } catch (RuntimeException exception) {
            return Health.down()
                    .withDetail("error", exception.getClass().getSimpleName())
                    .build();
        }
    }
}
