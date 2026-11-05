package com.nexachat.summary;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@Configuration
@EnableScheduling
public class SummaryConfiguration {

    @Bean
    public Clock summaryClock() {
        return Clock.systemUTC();
    }
}
