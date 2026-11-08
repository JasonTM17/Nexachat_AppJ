package com.nexachat.observability;

import ch.qos.logback.classic.Logger;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The json-logs profile must select the JSON console appender; every other
 * profile keeps Spring Boot's pattern console.
 *
 * <p>The logging system is global to the JVM, so the test re-initializes it
 * with the active profiles instead of relying on startup order.
 */
@SpringBootTest
@ActiveProfiles({"test", "json-logs"})
class JsonLogsProfileTest extends ObservabilityHealthTestBase {

    @Autowired
    private ConfigurableEnvironment environment;

    @Test
    void jsonLogsProfileUsesTheRedactingJsonAppender() {
        LoggingSystem loggingSystem = LoggingSystem.get(getClass().getClassLoader());
        loggingSystem.cleanUp();
        loggingSystem.initialize(new LoggingInitializationContext(environment), null, null);

        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        assertThat(root.getAppender("JSON_CONSOLE")).isNotNull();
        assertThat(root.getAppender("CONSOLE")).isNull();
    }
}
