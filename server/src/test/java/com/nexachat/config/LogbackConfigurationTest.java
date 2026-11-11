package com.nexachat.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.encoder.LayoutWrappingEncoder;
import ch.qos.logback.core.status.Status;
import ch.qos.logback.core.status.StatusManager;
import com.nexachat.observability.RedactingJsonLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LogbackConfigurationTest {

    @AfterEach
    void resetLoggingSystem() {
        LoggingSystem loggingSystem = LoggingSystem.get(getClass().getClassLoader());
        loggingSystem.cleanUp();
        loggingSystem.initialize(new LoggingInitializationContext(new MockEnvironment()), null, null);
    }

    private void configureLogging(String... activeProfiles) {
        MockEnvironment environment = new MockEnvironment();
        if (activeProfiles != null && activeProfiles.length > 0) {
            environment.setActiveProfiles(activeProfiles);
        }
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        StatusManager statusManager = context.getStatusManager();
        statusManager.clear();

        LoggingSystem loggingSystem = LoggingSystem.get(getClass().getClassLoader());
        loggingSystem.cleanUp();
        loggingSystem.initialize(new LoggingInitializationContext(environment), null, null);
    }

    @Test
    void defaultProfileConfiguresPatternConsoleWithoutWarnings() {
        configureLogging();

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        List<Status> warnings = context.getStatusManager().getCopyOfStatusList().stream()
                .filter(s -> s.getLevel() >= Status.WARN)
                .toList();

        assertThat(warnings)
                .as("Expected no WARN or ERROR status in Logback StatusManager, but found: %s", warnings)
                .isEmpty();

        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        Appender<ILoggingEvent> consoleAppender = root.getAppender("CONSOLE");
        assertThat(consoleAppender)
                .as("CONSOLE appender should be attached to root")
                .isNotNull();
        assertThat(root.getAppender("JSON_CONSOLE"))
                .as("JSON_CONSOLE appender should not be attached to root")
                .isNull();

        assertThat(consoleAppender).isInstanceOf(ConsoleAppender.class);
        ConsoleAppender<ILoggingEvent> appender = (ConsoleAppender<ILoggingEvent>) consoleAppender;
        assertThat(appender.getEncoder())
                .as("Default profile should use PatternLayoutEncoder")
                .isInstanceOf(PatternLayoutEncoder.class);
    }

    @Test
    void jsonLogsProfileConfiguresRedactingJsonConsoleWithoutWarnings() {
        configureLogging("json-logs");

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        List<Status> warnings = context.getStatusManager().getCopyOfStatusList().stream()
                .filter(s -> s.getLevel() >= Status.WARN)
                .toList();

        assertThat(warnings)
                .as("Expected no WARN or ERROR status in Logback StatusManager, but found: %s", warnings)
                .isEmpty();

        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        Appender<ILoggingEvent> jsonAppender = root.getAppender("JSON_CONSOLE");
        assertThat(jsonAppender)
                .as("JSON_CONSOLE appender should be attached to root")
                .isNotNull();
        assertThat(root.getAppender("CONSOLE"))
                .as("CONSOLE appender should not be attached to root")
                .isNull();

        assertThat(jsonAppender).isInstanceOf(ConsoleAppender.class);
        ConsoleAppender<ILoggingEvent> appender = (ConsoleAppender<ILoggingEvent>) jsonAppender;
        assertThat(appender.getEncoder())
                .as("json-logs profile should use LayoutWrappingEncoder")
                .isInstanceOf(LayoutWrappingEncoder.class);
        LayoutWrappingEncoder<?> layoutWrappingEncoder = (LayoutWrappingEncoder<?>) appender.getEncoder();
        assertThat(layoutWrappingEncoder.getLayout())
                .as("Encoder layout should be RedactingJsonLayout")
                .isInstanceOf(RedactingJsonLayout.class);
    }
}
