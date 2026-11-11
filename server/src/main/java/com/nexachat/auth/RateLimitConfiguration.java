package com.nexachat.auth;

import io.github.bucket4j.TimeMeter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Time source for the in-memory token buckets.
 *
 * <p>Production keeps Bucket4j's default millisecond system clock. Tests that
 * need to prove exhaustion exactly (the budget refills greedily, so a slow
 * caller regains tokens while it retries) replace this bean with a frozen
 * meter, which makes the number of admitted requests deterministic.
 */
@Configuration
public class RateLimitConfiguration {

    @Bean
    public TimeMeter rateLimitTimeMeter() {
        return TimeMeter.SYSTEM_MILLISECONDS;
    }
}
