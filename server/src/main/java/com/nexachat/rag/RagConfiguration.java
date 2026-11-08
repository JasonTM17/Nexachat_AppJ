package com.nexachat.rag;

import com.nexachat.summary.llm.CircuitBreaker;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class RagConfiguration {

    @Bean
    public VectorCache ragVectorCache(RagProperties properties) {
        return new VectorCache(properties.getIndex().getCacheMaxVectors());
    }

    @Bean
    public CircuitBreaker ragCircuitBreaker(Clock clock) {
        return new CircuitBreaker(clock);
    }
}
