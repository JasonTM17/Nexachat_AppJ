package com.nexachat.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.summary.SummaryConsentRepository;
import com.nexachat.summary.ConversationAiOptOuts;
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

/** Serializes under consent and source locks, then writes after releasing the database transaction. */
@Component
@Profile("!test")
public class AskResponseWriter {

    private final ObjectMapper objectMapper;
    private final SummaryConsentRepository consentRepository;
    private final RagStats stats;
    private final RagQuestionCache questionCache;
    private final RagRetrievalService retrievalService;
    private final ConversationMemberRepository memberRepository;
    private final ConversationAiOptOuts aiOptOuts;
    private final TransactionTemplate transactionTemplate;

    public AskResponseWriter(
            ObjectMapper objectMapper,
            SummaryConsentRepository consentRepository,
            RagStats stats,
            PlatformTransactionManager transactionManager,
            RagQuestionCache questionCache,
            RagRetrievalService retrievalService,
            ConversationMemberRepository memberRepository,
            ConversationAiOptOuts aiOptOuts) {
        this.objectMapper = objectMapper;
        this.consentRepository = consentRepository;
        this.stats = stats;
        this.questionCache = questionCache;
        this.retrievalService = retrievalService;
        this.memberRepository = memberRepository;
        this.aiOptOuts = aiOptOuts;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public void write(UUID userId, AskExecution execution, HttpServletResponse response) throws IOException {
        byte[] body;
        try {
            body = transactionTemplate.execute(status -> {
                SummaryConsentRepository.ConsentSnapshot current = consentRepository.lockForResponse(userId);
                boolean consentUnchanged = execution.consentSnapshot().equals(current);
                boolean contextUnchanged = execution.conversationId() == null
                        || questionCache.lockContextForResponse(execution.conversationId(), execution.contextSnapshot());
                boolean allowed = consentUnchanged && contextUnchanged && speakerUnchanged(userId, execution);
                if (!allowed) {
                    stats.recordNotFound();
                }
                AskResponse responseBody = allowed
                        ? execution.response()
                        : suppressed(execution, consentUnchanged
                            ? AskReason.CONTEXT_CHANGED
                            : AskReason.CONSENT_REQUIRED);
                try {
                    byte[] bytes = objectMapper.writeValueAsBytes(responseBody);
                    // Expiry and profile renames are not covered by the locked context revision.
                    if (allowed && execution.conversationId() != null
                            && (!questionCache.contextUnchanged(execution.conversationId(), execution.contextSnapshot())
                            || !speakerUnchanged(userId, execution))) {
                        stats.recordNotFound();
                        return objectMapper.writeValueAsBytes(
                                suppressed(execution, AskReason.CONTEXT_CHANGED));
                    }
                    return bytes;
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            });
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        }

        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
        response.flushBuffer();
    }

    private boolean speakerUnchanged(UUID userId, AskExecution execution) {
        AskSpeakerLookup.Binding speaker = execution.speaker();
        UUID conversationId = execution.conversationId();
        return speaker == null || memberRepository.findJoinedAt(conversationId, userId)
                .filter(speaker.admittedFrom()::equals).isPresent()
                && retrievalService.speakerUnchanged(conversationId, speaker);
    }

    private AskResponse suppressed(AskExecution execution, AskReason reason) {
        boolean omitted = execution.conversationId() == null ? execution.response().contextOmitted()
                : aiOptOuts.hasOptedOutMessages(execution.conversationId());
        boolean contextChanged = reason == AskReason.CONTEXT_CHANGED;
        boolean llmAttempted = execution.response() != null
                && execution.response().provenance() != null
                && execution.response().provenance().llmAttempted();
        return AskResponse.notFound(
                omitted,
                reason,
                contextChanged,
                contextChanged ? 1 : null,
                false,
                llmAttempted);
    }
}
