package com.nexachat.rag;

import com.nexachat.summary.ConversationAiOptOuts;
import com.nexachat.summary.SummaryConsentRepository;
import com.nexachat.summary.SummaryLanguage;
import com.nexachat.summary.SummaryProviderException;
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
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Answer generation through the existing OpenAI-compatible LLM client. The
 * caller's persisted consent is re-checked immediately before the outbound
 * call. Outcomes are typed: a protocol-valid abstention is evidence-based, a
 * provider/validation failure is a degradation, and a bulkhead rejection is a
 * retryable capacity result rather than a silent {@code null}.
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
    private final Semaphore llmBulkhead;
    private final long bulkheadWaitMs;

    public RagLlmAnswerService(
            LlmClient llmClient,
            RagPromptBuilder promptBuilder,
            RagOutputValidator outputValidator,
            @Qualifier("ragCircuitBreaker") CircuitBreaker circuitBreaker,
            SummaryConsentRepository consentRepository,
            RagStats stats) {
        this(llmClient, promptBuilder, outputValidator, circuitBreaker, consentRepository, stats,
                ConversationAiOptOuts.none(), new RagProperties());
    }

    public RagLlmAnswerService(
            LlmClient llmClient,
            RagPromptBuilder promptBuilder,
            RagOutputValidator outputValidator,
            @Qualifier("ragCircuitBreaker") CircuitBreaker circuitBreaker,
            SummaryConsentRepository consentRepository,
            RagStats stats,
            ConversationAiOptOuts aiOptOuts) {
        this(llmClient, promptBuilder, outputValidator, circuitBreaker, consentRepository, stats,
                aiOptOuts, new RagProperties());
    }

    @Autowired
    public RagLlmAnswerService(
            LlmClient llmClient,
            RagPromptBuilder promptBuilder,
            RagOutputValidator outputValidator,
            @Qualifier("ragCircuitBreaker") CircuitBreaker circuitBreaker,
            SummaryConsentRepository consentRepository,
            RagStats stats,
            ConversationAiOptOuts aiOptOuts,
            RagProperties properties) {
        this.llmClient = llmClient;
        this.promptBuilder = promptBuilder;
        this.outputValidator = outputValidator;
        this.circuitBreaker = circuitBreaker;
        this.consentRepository = consentRepository;
        this.stats = stats;
        this.aiOptOuts = aiOptOuts;
        RagProperties effectiveProperties = properties == null ? new RagProperties() : properties;
        this.llmBulkhead = new Semaphore(
                Math.max(1, effectiveProperties.getLlm().getMaxConcurrent()), true);
        this.bulkheadWaitMs = Math.max(0, effectiveProperties.getLlm().getBulkheadWaitMs());
    }

    /** Compatibility API: only a validated answer is returned, exactly as before. */
    public RagLlmAnswer answer(
            UUID userId,
            UUID conversationId,
            String question,
            SummaryLanguage language,
            List<RagContextItem> context,
            Map<UUID, RagMessage> contextMessages) {
        return answer(userId, conversationId, question, language, context, contextMessages, List.of());
    }

    /** Compatibility API: only a validated answer is returned, exactly as before. */
    public RagLlmAnswer answer(
            UUID userId,
            UUID conversationId,
            String question,
            SummaryLanguage language,
            List<RagContextItem> context,
            Map<UUID, RagMessage> contextMessages,
            List<AskPriorTurn> priorTurns) {
        RagLlmOutcome outcome = answerDetailed(
                userId, conversationId, question, language, context, contextMessages, priorTurns);
        return outcome.status() == RagLlmOutcome.Status.ANSWER ? outcome.answer() : null;
    }

    public RagLlmOutcome answerDetailed(
            UUID userId,
            UUID conversationId,
            String question,
            SummaryLanguage language,
            List<RagContextItem> context,
            Map<UUID, RagMessage> contextMessages,
            List<AskPriorTurn> priorTurns) {
        return answerDetailed(userId, conversationId, question, language, context, contextMessages,
                priorTurns, null);
    }

    public RagLlmOutcome answerDetailed(
            UUID userId,
            UUID conversationId,
            String question,
            SummaryLanguage language,
            List<RagContextItem> context,
            Map<UUID, RagMessage> contextMessages,
            List<AskPriorTurn> priorTurns,
            AskDeadline deadline) {
        return answerDetailed(userId, conversationId, question, language, context, contextMessages, priorTurns, deadline, false);
    }

    RagLlmOutcome answerSpeakerQuote(UUID userId, UUID conversationId, String question, SummaryLanguage language,
                                    List<RagContextItem> context, Map<UUID, RagMessage> contextMessages, AskDeadline deadline) {
        return answerDetailed(userId, conversationId, question, language, context, contextMessages, List.of(), deadline, true);
    }

    private RagLlmOutcome answerDetailed(UUID userId, UUID conversationId, String question, SummaryLanguage language,
                                         List<RagContextItem> context, Map<UUID, RagMessage> contextMessages,
                                         List<AskPriorTurn> priorTurns, AskDeadline deadline, boolean speakerQuote) {
        if (!consentRepository.isConsented(userId)) {
            return RagLlmOutcome.providerError(false, true, false, false);
        }
        List<RagContextItem> externalContext = externalContext(conversationId, context, contextMessages);
        if (externalContext.isEmpty()) {
            return RagLlmOutcome.providerError(false, false, true, false);
        }
        // Re-check immediately before the outbound prompt is built: a consent
        // revocation or an opt-out committed after retrieval must win the race.
        if (!consentRepository.isConsented(userId)) {
            return RagLlmOutcome.providerError(false, true, false, false);
        }
        LlmClient.TargetConfig primary = llmClient.cloudTarget();
        LlmClient.TargetConfig secondary = llmClient.localTarget();
        LlmClient.TargetConfig fallback = primary == null ? null : llmClient.fallbackTarget();
        if (primary == null && secondary == null) {
            return RagLlmOutcome.providerError(false);
        }

        boolean breakerAllows = circuitBreaker.allowRequest();
        if (!breakerAllows && fallback == null && secondary == null) {
            return RagLlmOutcome.providerError(false);
        }

        boolean bulkheadAcquired = false;
        boolean primaryAttempted = false;
        boolean breakerRecorded = false;
        boolean[] providerAttempted = new boolean[1];
        try {
            long waitMs = bulkheadWaitMs;
            if (deadline != null) {
                waitMs = Math.min(waitMs, Math.max(0, deadline.remainingMs()));
            }
            bulkheadAcquired = llmBulkhead.tryAcquire(waitMs, TimeUnit.MILLISECONDS);
            if (!bulkheadAcquired) {
                stats.recordLlmBulkheadRejection();
                return RagLlmOutcome.bulkheadRejected(deadline != null && deadline.expired());
            }
            RagPrompt prompt = speakerQuote ? promptBuilder.buildSpeakerQuote(question, language, externalContext)
                    : promptBuilder.build(question, language, externalContext, priorTurns);
            try {
                primaryAttempted = primary != null && breakerAllows;
                TieredCompletion tiered = completeTiers(
                        prompt, primary, fallback, secondary, breakerAllows, deadline, providerAttempted);
                LlmClient.LlmCallResult completion = tiered.completion();
                RagOutputValidator.Validation validation = outputValidator.validateDetailed(completion.content(), prompt);
                // Validate before recording: a primary that answers 200 with an
                // unusable body is a failing primary, not a healthy one. A protocol-valid
                // abstention is successful provider behavior and must not poison the breaker.
                if (validation.status() == RagOutputValidator.Status.INVALID) {
                    if (tiered.primaryUsed() || tiered.primaryFailed()) {
                        circuitBreaker.recordFailure();
                        breakerRecorded = true;
                    }
                    stats.recordLlmFailure();
                    return RagLlmOutcome.providerError(providerAttempted[0]);
                }
                if (tiered.primaryUsed()) {
                    circuitBreaker.recordSuccess();
                    breakerRecorded = true;
                } else if (tiered.primaryFailed()) {
                    circuitBreaker.recordFailure();
                    breakerRecorded = true;
                }
                if (validation.status() == RagOutputValidator.Status.ABSTAIN) {
                    return RagLlmOutcome.abstain();
                }
                RagLlmAnswer answer = validation.answer();
                String provider = tiered.fallbackUsed() ? "fallback" : (tiered.primaryUsed() ? "primary" : null);
                return RagLlmOutcome.answer(
                        new RagLlmAnswer(answer.answer(), answer.citationIds(), provider),
                        validation.sensitiveContentRedacted());
            } catch (AskDeadlineExceededException deadlineExceeded) {
                return RagLlmOutcome.providerError(providerAttempted[0], false, false, true);
            } catch (RuntimeException exception) {
                if (primaryAttempted && !breakerRecorded) {
                    circuitBreaker.recordFailure();
                }
                stats.recordLlmFailure();
                return RagLlmOutcome.providerError(providerAttempted[0]);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return RagLlmOutcome.providerError(providerAttempted[0]);
        } finally {
            if (bulkheadAcquired) {
                llmBulkhead.release();
            }
        }
    }

    /**
     * Calls the configured tiers one at a time with a fresh remainder of the ask
     * deadline. This preserves the existing primary -> fallback -> local routing
     * without giving every tier a new full timeout that could outlive the client.
     */
    private TieredCompletion completeTiers(
            RagPrompt prompt,
            LlmClient.TargetConfig primary,
            LlmClient.TargetConfig fallback,
            LlmClient.TargetConfig secondary,
            boolean breakerAllows,
            AskDeadline deadline,
            boolean[] providerAttempted) {
        LlmClient.LlmCallResult completion = null;
        RuntimeException failure = null;
        boolean fallbackUsed = false;
        boolean primaryUsed = false;
        boolean primaryFailed = false;

        if (primary != null && breakerAllows) {
            try {
                completion = invoke(prompt, primary, deadline, providerAttempted);
                primaryUsed = true;
            } catch (RuntimeException primaryFailure) {
                if (primaryFailure instanceof AskDeadlineExceededException deadlineExceeded) {
                    throw deadlineExceeded;
                }
                primaryFailed = true;
                failure = primaryFailure;
                if (primaryFailure instanceof SummaryProviderException providerFailure
                        && providerFailure.isRetryable()
                        && fallback != null) {
                    try {
                        completion = invoke(prompt, fallback, deadline, providerAttempted);
                        fallbackUsed = true;
                        failure = null;
                    } catch (RuntimeException fallbackFailure) {
                        if (fallbackFailure instanceof AskDeadlineExceededException deadlineExceeded) {
                            throw deadlineExceeded;
                        }
                        completion = null;
                        failure = fallbackFailure;
                    }
                }
            }
        } else if (primary != null && fallback != null) {
            try {
                completion = invoke(prompt, fallback, deadline, providerAttempted);
                fallbackUsed = true;
            } catch (RuntimeException fallbackFailure) {
                if (fallbackFailure instanceof AskDeadlineExceededException deadlineExceeded) {
                    throw deadlineExceeded;
                }
                completion = null;
                failure = fallbackFailure;
            }
        }

        if (completion == null && secondary != null) {
            try {
                completion = invoke(prompt, secondary, deadline, providerAttempted);
                failure = null;
            } catch (RuntimeException localFailure) {
                if (localFailure instanceof AskDeadlineExceededException deadlineExceeded) {
                    throw deadlineExceeded;
                }
                failure = localFailure;
            }
        }
        if (completion == null) {
            if (failure != null) {
                throw failure;
            }
            throw new IllegalStateException("no LLM provider is available");
        }
        return new TieredCompletion(completion, fallbackUsed, primaryUsed, primaryFailed);
    }

    private LlmClient.LlmCallResult invoke(
            RagPrompt prompt,
            LlmClient.TargetConfig target,
            AskDeadline deadline,
            boolean[] providerAttempted) {
        LlmClient.TargetConfig effective = targetWithinDeadline(target, deadline);
        providerAttempted[0] = true;
        LlmClient.LlmCallResult result = llmClient.completeTextWithTargetResult(
                prompt.systemMessage(), prompt.userMessage(), effective);
        if (deadline != null) {
            deadline.check();
        }
        return result;
    }

    private LlmClient.TargetConfig targetWithinDeadline(
            LlmClient.TargetConfig target, AskDeadline deadline) {
        if (deadline == null) {
            return target;
        }
        long remaining = deadline.networkBudgetMs();
        int timeoutMs = (int) Math.min(target.timeoutMs(), remaining);
        return new LlmClient.TargetConfig(
                target.endpoint(), target.apiKey(), target.model(), timeoutMs, target.maxOutputTokens());
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

    private record TieredCompletion(
            LlmClient.LlmCallResult completion,
            boolean fallbackUsed,
            boolean primaryUsed,
            boolean primaryFailed) {
    }
}
