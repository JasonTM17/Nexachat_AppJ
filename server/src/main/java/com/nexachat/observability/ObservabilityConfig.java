package com.nexachat.observability;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.util.List;

/**
 * Observability wiring: properties, the delta-sync filter and its URL
 * binding. The request-id filter is component-scanned; the aspects and
 * listeners are components too.
 */
@Configuration
@EnableConfigurationProperties(ObservabilityProperties.class)
public class ObservabilityConfig {

    @Bean
    public FilterRegistrationBean<DeltaSyncMetricsFilter> deltaSyncMetricsFilterRegistration(
            ObservabilityMetrics metrics) {
        FilterRegistrationBean<DeltaSyncMetricsFilter> registration =
                new FilterRegistrationBean<>(new DeltaSyncMetricsFilter(metrics));
        registration.setName("deltaSyncMetricsFilter");
        registration.setUrlPatterns(List.of("/api/v1/sync", "/api/v1/chat/sync"));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 100);
        return registration;
    }
}
