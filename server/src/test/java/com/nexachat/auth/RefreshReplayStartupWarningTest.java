package com.nexachat.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.DefaultApplicationArguments;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshReplayStartupWarningTest {

    private static final Logger LOGGER = (Logger) LoggerFactory.getLogger(RefreshReplayStartupWarning.class);

    @Test
    void unavailableKeyEmitsOneStartupWarningWithoutPrintingConfiguration() throws Exception {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        LOGGER.addAppender(appender);
        try {
            new RefreshReplayStartupWarning(cipher("private-test-key-value")).run(new DefaultApplicationArguments());

            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.WARN);
            assertThat(appender.list.get(0).getFormattedMessage())
                    .contains("legacy strict rotation")
                    .doesNotContain("private-test-key-value");
        } finally {
            LOGGER.detachAppender(appender);
        }
    }

    @Test
    void validKeyEmitsNoStartupWarning() throws Exception {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        LOGGER.addAppender(appender);
        try {
            new RefreshReplayStartupWarning(cipher(validKey())).run(new DefaultApplicationArguments());

            assertThat(appender.list).isEmpty();
        } finally {
            LOGGER.detachAppender(appender);
        }
    }

    private static RefreshReplayCipher cipher(String key) {
        RefreshReplayProperties properties = new RefreshReplayProperties();
        properties.setKey(key);
        return new RefreshReplayCipher(new ObjectMapper(), properties);
    }

    private static String validKey() {
        byte[] bytes = new byte[32];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) (31 - index);
        }
        return Base64.getEncoder().encodeToString(bytes);
    }
}
