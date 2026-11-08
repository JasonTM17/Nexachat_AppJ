package com.nexachat.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RedactingJsonLayoutTest {

    private final RedactingJsonLayout layout = new RedactingJsonLayout();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void rendersJsonWithCorrelationIdAndRedactedMessage() throws Exception {
        MDC.put("traceId", "req-123");
        LoggingEvent event = event("login failed for phone=+84912345678 token=abcdef123456"); // gitleaks:allow
        try {
            JsonNode json = mapper.readTree(layout.doLayout(event));

            assertThat(json.get("traceId").asText()).isEqualTo("req-123");
            assertThat(json.get("level").asText()).isEqualTo("WARN");
            assertThat(json.get("logger").asText()).isEqualTo("com.nexachat.Test");
            assertThat(json.get("message").asText()).doesNotContain("84912345678");
            assertThat(json.get("message").asText()).doesNotContain("abcdef123456");
        } finally {
            MDC.clear();
        }
    }

    @Test
    void masksContentJsonFields() throws Exception {
        LoggingEvent event = event("send {\"content\":\"secret body\"} ok");
        JsonNode json = mapper.readTree(layout.doLayout(event));

        assertThat(json.get("message").asText()).doesNotContain("secret body");
        assertThat(json.get("message").asText()).contains("[REDACTED]");
    }

    @Test
    void rendersExceptionWithoutLeakingItsMessage() throws Exception {
        LoggingEvent event = event("request failed");
        event.setThrowableProxy(new ch.qos.logback.classic.spi.ThrowableProxy(
                new RuntimeException("bearer=abcdef123456")));

        JsonNode json = mapper.readTree(layout.doLayout(event));

        assertThat(json.has("exception")).isTrue();
        assertThat(json.get("exception").asText()).doesNotContain("abcdef123456");
    }

    private static LoggingEvent event(String message) {
        LoggingEvent event = new LoggingEvent();
        event.setLevel(Level.WARN);
        event.setLoggerName("com.nexachat.Test");
        event.setMessage(message);
        event.setTimeStamp(System.currentTimeMillis());
        event.setThreadName("test");
        event.setMDCPropertyMap(Map.of("traceId", "req-123"));
        return event;
    }
}
