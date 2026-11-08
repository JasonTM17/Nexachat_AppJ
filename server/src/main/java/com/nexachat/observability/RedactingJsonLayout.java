package com.nexachat.observability;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.LayoutBase;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JSON console layout for the {@code json-logs} profile.
 *
 * <p>The rendered message and exception text pass through {@link Redactor}
 * before serialization; the correlation id comes from the {@code traceId} MDC
 * key set by {@link RequestIdFilter}. No connection strings, tokens, phones or
 * message bodies are emitted.
 */
public class RedactingJsonLayout extends LayoutBase<ILoggingEvent> {

    private static final int MAX_STACK_TRACE_LENGTH = 4_000;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String doLayout(ILoggingEvent event) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("timestamp", Instant.ofEpochMilli(event.getTimeStamp()).toString());
        root.put("level", event.getLevel().toString());
        root.put("logger", event.getLoggerName());
        root.put("thread", event.getThreadName());
        String traceId = event.getMDCPropertyMap().get("traceId");
        if (traceId != null && !traceId.isBlank()) {
            root.put("traceId", traceId);
        }
        root.put("message", Redactor.redact(event.getFormattedMessage()));
        String exception = renderException(event.getThrowableProxy());
        if (exception != null) {
            root.put("exception", exception);
        }
        try {
            return MAPPER.writeValueAsString(root) + System.lineSeparator();
        } catch (Exception ex) {
            return "{\"message\":\"" + Redactor.redact(String.valueOf(ex.getClass().getSimpleName()))
                    + "\"}" + System.lineSeparator();
        }
    }

    private static String renderException(IThrowableProxy proxy) {
        if (proxy == null) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        builder.append(proxy.getClassName());
        if (proxy.getMessage() != null) {
            builder.append(": ").append(proxy.getMessage());
        }
        IThrowableProxy cause = proxy.getCause();
        while (cause != null) {
            builder.append(" | caused by ").append(cause.getClassName());
            if (cause.getMessage() != null) {
                builder.append(": ").append(cause.getMessage());
            }
            cause = cause.getCause();
        }
        if (proxy.getStackTraceElementProxyArray() != null
                && proxy.getStackTraceElementProxyArray().length > 0) {
            builder.append(" @ ");
            for (int i = 0; i < Math.min(8, proxy.getStackTraceElementProxyArray().length); i++) {
                if (i > 0) {
                    builder.append(" <- ");
                }
                builder.append(proxy.getStackTraceElementProxyArray()[i].toString());
            }
        }
        String text = builder.toString();
        return text.length() > MAX_STACK_TRACE_LENGTH
                ? Redactor.redact(text.substring(0, MAX_STACK_TRACE_LENGTH)) + "..."
                : Redactor.redact(text);
    }
}
