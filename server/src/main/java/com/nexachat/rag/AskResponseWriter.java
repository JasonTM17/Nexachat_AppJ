package com.nexachat.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.summary.SummaryConsentRepository;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Writes the small ask response while consent revocation is serialized on the user row. */
@Component
@Profile("!test")
public class AskResponseWriter {

    private final ObjectMapper objectMapper;
    private final SummaryConsentRepository consentRepository;
    private final RagStats stats;
    private final TransactionTemplate transactionTemplate;

    public AskResponseWriter(
            ObjectMapper objectMapper,
            SummaryConsentRepository consentRepository,
            RagStats stats,
            PlatformTransactionManager transactionManager) {
        this.objectMapper = objectMapper;
        this.consentRepository = consentRepository;
        this.stats = stats;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public void write(UUID userId, AskExecution execution, HttpServletResponse response) throws IOException {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                SummaryConsentRepository.ConsentSnapshot current = consentRepository.lockForResponse(userId);
                boolean consentUnchanged = execution.consentSnapshot().equals(current);
                if (!consentUnchanged) {
                    stats.recordNotFound();
                }
                AskResponse responseBody = consentUnchanged
                        ? execution.response()
                        : AskResponse.notFound(execution.response().contextOmitted());
                try {
                    byte[] body = objectMapper.writeValueAsBytes(responseBody);
                    response.setStatus(HttpServletResponse.SC_OK);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                    response.setContentLength(body.length);
                    response.getOutputStream().write(body);
                    response.flushBuffer();
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            });
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        }
    }
}
