package com.nexachat.rag;

import com.nexachat.summary.ConversationAiOptOuts;
import com.nexachat.summary.SummaryLanguage;
import com.nexachat.summary.SummaryConsentRepository;
import com.nexachat.summary.llm.CircuitBreaker;
import com.nexachat.summary.llm.LlmClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Answer generation through the existing OpenAI-compatible LLM client. The
 * caller's persisted consent is re-checked immediately before the outbound
 * call; any failure returns null so the router can degrade to extractive or
 * not-found instead of surfacing an error. When the primary provider fails
 * with a retryable error, the configured fallback provider is tried once
 * before the local tier and the extractive degradation.
 *
 * <p>The circuit breaker tracks the primary cloud tier only. A fallback or
 * local answer neither resets it nor is skipped while it is open: an open
 * breaker still tries the fallback and the healthy local tier before giving up.
 */
@Service
@Profile("!test")
public class RagLlmAnswerService {

    private final LlmClient llmClient;
    private final RagPromptBuilder promptBuilder;
    private final RagOutputValidator outputValidator;
    private final CircuitBreaker circuitBreaker;
    private final SummaryConsentRepository consentRepository;
    private final RagStats stats;
    private final ConversationAiOptOuts aiOptOuts;

    public RagLlmAnswerService(
            LlmClient llmClient,
            RagPromptBuilder promptBuilder,
            RagOutputValidator outputValidator,
            @Qualifier("ragCircuitBreaker") CircuitBreaker circuitBreaker,
            SummaryConsentRepository consentRepository,
            RagStats stats) {
        this(llmClient, promptBuilder, outputValidator, circuitBreaker, consentRepository, stats,
                ConversationAiOptOuts.none());
    }

    @Autowired
    public RagLlmAnswerService(
            LlmClient llmClient,
            RagPromptBuilder promptBuilder,
            RagOutputValidator outputValidator,
            @Qualifier("ragCircuitBreaker") CircuitBreaker circuitBreaker,
            SummaryConsentRepository consentRepository,
            RagStats stats,
            ConversationAiOptOuts aiOptOuts) {
        this.llmClient = llmClient;
        this.promptBuilder = promptBuilder;
        this.outputValidator = outputValidator;
        this.circuitBreaker = circuitBreaker;
        this.consentRepository = consentRepository;
        this.stats = stats;
        this.aiOptOuts = aiOptOuts;
    }

    public RagLlmAnswer answer(
            UUID userId,
            UUID conversationId,
            String question,
            SummaryLanguage language,
            List<RagContextItem> context,
            Map<UUID, RagMessage> contextMessages) {
        if (!consentRepository.isConsented(userId)) {
            return null;
        }
        List<RagContextItem> externalContext = externalContext(conversationId, context, contextMessages);
        if (externalContext.isEmpty()) {
            return null;
        }
        // Re-check immediately before the outbound prompt is built: a consent
        // revocation or an opt-out committed after retrieval must win the race.
        if (!consentRepository.isConsented(userId)) {
            return null;
        }
        LlmClient.TargetConfig primary = llmClient.cloudTarget();
        LlmClient.TargetConfig secondary = llmClient.localTarget();
        LlmClient.TargetConfig fallback = primary == null ? null : llmClient.fallbackTarget();

        LlmClient.TargetConfig first = primary != null ? primary : secondary;
        if (first == null) {
            return null;
        }

        boolean breakerAllows = circuitBreaker.allowRequest();
        if (!breakerAllows && fallback == null && secondary == null) {
            return null;
        }

        RagPrompt prompt = promptBuilder.build(question, language, externalContext);
        boolean primaryAttempted = false;
        boolean breakerRecorded = false;
        try {
            LlmClient.LlmCallResult completion;
            boolean fallbackUsed = false;
            boolean primaryUsed = false;
            boolean primaryFailed = false;
            if (primary != null) {
                primaryAttempted = breakerAllows;
                try {
                    completion = llmClient.completeTextWithFallback(
                            prompt.systemMessage(), prompt.userMessage(),
                            breakerAllows ? primary : null, fallback);
                    fallbackUsed = completion.fallbackUsed();
                    primaryUsed = breakerAllows && !fallbackUsed;
                    primaryFailed = breakerAllows && fallbackUsed;
                } catch (RuntimeException exception) {
                    primaryFailed = breakerAllows;
                    if (secondary == null) {
                        throw exception;
                    }
                    completion = llmClient.completeTextWithTargetResult(
                            prompt.systemMessage(), prompt.userMessage(), secondary);
                }
            } else {
                completion = llmClient.completeTextWithTargetResult(
                        prompt.systemMessage(), prompt.userMessage(), secondary);
            }
            if (primaryUsed) {
                circuitBreaker.recordSuccess();
                breakerRecorded = true;
            } else if (primaryFailed) {
                circuitBreaker.recordFailure();
                breakerRecorded = true;
            }
            RagLlmAnswer answer = outputValidator.validate(completion.content(), prompt);
            if (answer == null) {
                return null;
            }
            String provider = fallbackUsed ? "fallback" : (primaryUsed ? "primary" : null);
            return new RagLlmAnswer(answer.answer(), answer.citationIds(), provider);
        } catch (RuntimeException exception) {
            if (primaryAttempted && !breakerRecorded) {
                circuitBreaker.recordFailure();
            }
            stats.recordLlmFailure();
            return null;
        }
    }

    /**
     * Drops context items whose messages now belong to an opted-out author.
     * Grouped items always share one sender, so dropping an item never splits
     * a sender's text. An unknown message id is dropped rather than risk
     * sending unverified text.
     */
    private List<RagContextItem> externalContext(
            UUID conversationId,
            List<RagContextItem> context,
            Map<UUID, RagMessage> contextMessages) {
        if (context == null || context.isEmpty()) {
            return List.of();
        }
        if (conversationId == null) {
            return context;
        }
        Set<UUID> optedOut = aiOptOuts.optedOutUserIds(conversationId);
        if (optedOut.isEmpty()) {
            return context;
        }
        List<RagContextItem> kept = new ArrayList<>();
        for (RagContextItem item : context) {
            boolean allowed = true;
            for (UUID messageId : item.messageIds()) {
                RagMessage message = contextMessages == null ? null : contextMessages.get(messageId);
                if (message == null
                        || (message.senderId() != null && optedOut.contains(message.senderId()))) {
                    allowed = false;
                    break;
                }
            }
            if (allowed) {
                kept.add(item);
            }
        }
        return List.copyOf(kept);
    }
}
