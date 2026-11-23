package com.nexachat.summary;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.observability.ObservabilityMetrics;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Serializes under user then conversation locks; HTTP I/O starts after transaction completion. */
@Component
@Profile("!test")
public class SummaryResponseWriter {
    private final ObjectMapper objectMapper;
    private final SummaryConsentRepository consentRepository;
    private final ConversationAiOptOuts aiOptOuts;
    private final JdbcTemplate jdbcTemplate;
    private final ObservabilityMetrics metrics;
    private final TransactionTemplate transactionTemplate;

    public SummaryResponseWriter(ObjectMapper objectMapper, SummaryConsentRepository consentRepository,
            ConversationAiOptOuts aiOptOuts, JdbcTemplate jdbcTemplate, ObservabilityMetrics metrics,
            PlatformTransactionManager transactionManager) {
        this.objectMapper = objectMapper;
        this.consentRepository = consentRepository;
        this.aiOptOuts = aiOptOuts;
        this.jdbcTemplate = jdbcTemplate;
        this.metrics = metrics;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    void write(UUID userId, SummaryExecution execution, HttpServletResponse response) throws IOException {
        byte[] body;
        try {
            body = "LLM".equals(execution.response().generatedBy())
                    ? transactionTemplate.execute(status -> serializeAuthorized(userId, execution))
                    : serialize(execution.response());
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        }
        SummaryResponse selected = execution.response();
        // The controller now calls the internal execution path. Keep its existing request metrics.
        metrics.recordSummaryRequest(selected.generatedBy(), selected.cached() ? "CACHE" : "GENERATE",
                selected.fallbackReason() == null ? "NONE" : selected.fallbackReason());
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
        response.flushBuffer();
    }

    private byte[] serializeAuthorized(UUID userId, SummaryExecution execution) {
        SummaryConsentRepository.ConsentSnapshot current = consentRepository.lockForResponse(userId);
        UUID conversationId = execution.response().conversationId();
        // The AI context opt-out trigger updates this parent row, so an opt-out cannot commit
        // between the exclusion read and JSON serialization while this SHARE lock is held.
        var conversations = jdbcTemplate.queryForList(
                "SELECT id FROM conversations WHERE id = ? FOR SHARE", UUID.class, conversationId);
        if (current.epoch() < 0) {
            throw ApiException.of(ApiErrorCode.USER_NOT_FOUND, "User not found");
        }
        if (conversations.isEmpty()) {
            throw ApiException.of(ApiErrorCode.CONVERSATION_NOT_FOUND, "Conversation not found");
        }
        if (!current.equals(execution.consentSnapshot())
                || !execution.expectedExclusions().equals(aiOptOuts.optedOutUserIds(conversationId))) {
            throw ApiException.of(ApiErrorCode.AI_CONSENT_REQUIRED,
                    "AI summary authorization changed before the response was serialized");
        }
        return serialize(execution.response());
    }

    private byte[] serialize(SummaryResponse response) {
        try {
            return objectMapper.writeValueAsBytes(response);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
