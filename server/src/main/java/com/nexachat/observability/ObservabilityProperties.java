package com.nexachat.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Observability tuning knobs. Secrets are never logged and metric tags stay
 * bounded; these values only control thresholds.
 */
@ConfigurationProperties(prefix = "nexachat.observability")
public record ObservabilityProperties(
        /** Repository calls slower than this are logged with the method name only. */
        @DefaultValue("500") long slowQueryThresholdMs) {
}
