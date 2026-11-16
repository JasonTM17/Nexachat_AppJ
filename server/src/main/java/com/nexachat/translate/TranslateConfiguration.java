package com.nexachat.translate;

import com.nexachat.summary.llm.CircuitBreaker;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class TranslateConfiguration {

    /**
     * Dedicated breaker for the translate path so a flapping provider only
     * pauses translations, never the summary or ask paths (and vice versa).
     */
    @Bean
    public CircuitBreaker translateCircuitBreaker(Clock clock) {
        return new CircuitBreaker(clock);
    }
}
