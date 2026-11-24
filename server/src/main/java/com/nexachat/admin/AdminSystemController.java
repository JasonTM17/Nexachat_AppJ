package com.nexachat.admin;

import com.nexachat.call.CallRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

/**
 * Operator-facing health snapshot for the System page: the same signals the
 * platform already exposes through Actuator, condensed for the console so an
 * operator never needs raw Actuator access to triage.
 */
@RestController
@RequestMapping("/api/v1/admin/system")
public class AdminSystemController {

    private final JdbcTemplate jdbcTemplate;
    private final CallRegistry callRegistry;
    private final AlertScanQueue scanQueue;
    private final Path storageRoot;

    public AdminSystemController(JdbcTemplate jdbcTemplate, CallRegistry callRegistry,
                                 AlertScanQueue scanQueue,
                                 @Value("${nexachat.storage.root:./data/uploads}") String storageRoot) {
        this.jdbcTemplate = jdbcTemplate;
        this.callRegistry = callRegistry;
        this.scanQueue = scanQueue;
        this.storageRoot = Path.of(storageRoot);
    }

    public record ServiceStatus(String name, String status, String detail) {
    }

    public record SystemHealth(String status, Instant checkedAt, long uptimeSeconds,
                               java.util.List<ServiceStatus> services) {
    }

    @GetMapping("/health")
    public SystemHealth health() {
        AdminGuard.requireStaff();
        java.util.List<ServiceStatus> services = new java.util.ArrayList<>();
        services.add(databaseStatus());
        services.add(flywayStatus());
        services.add(storageStatus());
        services.add(callStatus());
        services.add(scanStatus());
        boolean allUp = services.stream().allMatch(s -> "UP".equals(s.status()));
        long uptime = ManagementFactory.getRuntimeMXBean().getUptime() / 1000;
        return new SystemHealth(allUp ? "UP" : "DEGRADED", Instant.now(), uptime,
                java.util.List.copyOf(services));
    }

    private ServiceStatus databaseStatus() {
        try {
            Integer one = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return new ServiceStatus("database", "UP", "select 1 -> " + one);
        } catch (RuntimeException e) {
            return new ServiceStatus("database", "DOWN", "query failed");
        }
    }

    private ServiceStatus flywayStatus() {
        try {
            Map<String, Object> row = jdbcTemplate.queryForMap("""
                    SELECT count(*) AS applied, max(version) AS latest
                    FROM flyway_schema_history WHERE success
                    """);
            return new ServiceStatus("flyway", "UP",
                    "applied=" + row.get("applied") + " latest=" + row.get("latest"));
        } catch (RuntimeException e) {
            return new ServiceStatus("flyway", "DOWN", "history unreadable");
        }
    }

    private ServiceStatus storageStatus() {
        try {
            // C5-07: the uploads volume is what fills up, so usable space is
            // measured on the configured storage root — not the process's
            // working directory, which is unrelated to where uploads land.
            File root = storageRoot.toFile();
            long usable = root.getUsableSpace();
            return new ServiceStatus("storage", usable > 0 ? "UP" : "DOWN",
                    "usable=" + (usable / (1024 * 1024)) + "MiB");
        } catch (SecurityException e) {
            return new ServiceStatus("storage", "DOWN", "unreadable");
        }
    }

    private ServiceStatus callStatus() {
        int active = callRegistry == null ? 0 : callRegistry.activeCallSessions().size();
        return new ServiceStatus("calls", "UP", "active=" + active);
    }

    private ServiceStatus scanStatus() {
        if (scanQueue == null) {
            return new ServiceStatus("alert-pipeline", "DOWN", "queue unavailable");
        }
        return new ServiceStatus("alert-pipeline", "UP",
                "depth=" + scanQueue.depth() + " dropped=" + scanQueue.dropped());
    }
}
