package com.nexachat.auth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.UUID;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RefreshReplayProperties.class)
public class RefreshReplayConfiguration {

    public enum Operation {
        ISSUE,
        ROTATE,
        LOGOUT,
        LOGOUT_ALL,
        PASSWORD_CHANGE,
        ACCESS_REVOKE,
        REUSE_REVOKE,
        EXPIRY_REVOKE
    }

    /** Named, no-op-by-default seams for deterministic lock and commit schedules in tests. */
    public enum Boundary {
        BEFORE_USER_LOCK,
        AFTER_USER_LOCK,
        AFTER_SUCCESSOR_CREATED,
        BEFORE_RESPONSE_CONSTRUCTION,
        AFTER_RESPONSE_CONSTRUCTION,
        AFTER_ROTATION_STORED,
        AFTER_REFRESH_TRANSACTION_COMMIT
    }

    @FunctionalInterface
    public interface BoundaryHook {
        void reached(Boundary boundary, Operation operation, UUID userId, UUID tokenId);
    }

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock refreshReplayClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean(BoundaryHook.class)
    BoundaryHook refreshReplayBoundaryHook() {
        return (boundary, operation, userId, tokenId) -> { };
    }
}
