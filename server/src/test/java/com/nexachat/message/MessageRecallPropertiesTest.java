package com.nexachat.message;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class MessageRecallPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(RecallConfiguration.class);

    @Test
    void defaultRecallWindowIsTwentyFourHours() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(MessageRecallProperties.class).getRecallWindow())
                    .isEqualTo(Duration.ofHours(24));
        });
    }

    @Test
    void acceptsInclusiveMinimumAndMaximumAtStartup() {
        runner.withPropertyValues("nexachat.message.recall-window=PT1M")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(MessageRecallProperties.class).getRecallWindow())
                            .isEqualTo(Duration.ofMinutes(1));
                });
        runner.withPropertyValues("nexachat.message.recall-window=P7D")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(MessageRecallProperties.class).getRecallWindow())
                            .isEqualTo(Duration.ofDays(7));
                });
    }

    @Test
    void rejectsValuesOutsideConfiguredBoundsAtStartup() {
        runner.withPropertyValues("nexachat.message.recall-window=PT59S")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("recall-window must be between");
                });
        runner.withPropertyValues("nexachat.message.recall-window=P7DT1S")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("recall-window must be between");
                });
    }

    @Test
    void recallWindowIncludesItsExactBoundaryAndUsesTheSuppliedClock() {
        MessageRecallProperties properties = new MessageRecallProperties();
        Instant createdAt = Instant.parse("2026-11-05T10:00:00Z");
        Instant boundary = createdAt.plus(properties.getRecallWindow());

        Clock justInside = Clock.fixed(boundary.minusNanos(1), ZoneOffset.UTC);
        Clock exactlyAt = Clock.fixed(boundary, ZoneOffset.UTC);
        Clock justOutside = Clock.fixed(boundary.plusNanos(1), ZoneOffset.UTC);

        assertThat(properties.isWithinRecallWindow(createdAt, justInside.instant())).isTrue();
        assertThat(properties.isWithinRecallWindow(createdAt, exactlyAt.instant())).isTrue();
        assertThat(properties.isWithinRecallWindow(createdAt, justOutside.instant())).isFalse();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MessageRecallProperties.class)
    static class RecallConfiguration {
    }
}
