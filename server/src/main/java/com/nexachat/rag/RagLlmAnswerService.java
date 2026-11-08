package com.nexachat.rag;

import com.nexachat.summary.SummaryLanguage;
import com.nexachat.summary.SummaryConsentRepository;
import com.nexachat.summary.llm.CircuitBreaker;
import com.nexachat.summary.llm.LlmClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Answer generation through the existing OpenAI-compatible LLM client. The
 * caller's persisted consent is re-checked immediately before the outbound
 * call; any failure returns null so the router can degrade to extractive or
 * not-found instead of surfacing an error. When the primary provider fails
 * with a retryable error, the configured fallback provider is tried once
 * before the local tier and the extractive degradation.
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

    public RagLlmAnswerService(
            LlmClient llmClient,
            RagPromptBuilder promptBuilder,
            RagOutputValidator outputValidator,
            @Qualifier("ragCircuitBreaker") CircuitBreaker circuitBreaker,
            SummaryConsentRepository consentRepository,
            RagStats stats) {
        this.llmClient = llmClient;
        this.promptBuilder = promptBuilder;
        this.outputValidator = outputValidator;
        this.circuitBreaker = circuitBreaker;
        this.consentRepository = consentRepository;
        this.stats = stats;
    }

    public RagLlmAnswer answer(
            UUID userId, String question, SummaryLanguage language, List<RagContextItem> context) {
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
        if (!breakerAllows && fallback == null) {
            return null;
        }

        RagPrompt prompt = promptBuilder.build(question, language, context);
        try {
            LlmClient.LlmCallResult completion;
            boolean fallbackUsed = false;
            boolean primaryUsed = false;
            if (primary != null) {
                try {
                    completion = llmClient.completeTextWithFallback(
                            prompt.systemMessage(), prompt.userMessage(),
                            breakerAllows ? primary : null, fallback);
                    fallbackUsed = completion.fallbackUsed();
                    primaryUsed = !fallbackUsed;
                } catch (RuntimeException exception) {
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
            circuitBreaker.recordSuccess();
            RagLlmAnswer answer = outputValidator.validate(completion.content(), prompt);
            if (answer == null) {
                return null;
            }
            String provider = fallbackUsed ? "fallback" : (primaryUsed ? "primary" : null);
            return new RagLlmAnswer(answer.answer(), answer.citationIds(), provider);
        } catch (RuntimeException exception) {
            circuitBreaker.recordFailure();
            stats.recordLlmFailure();
            return null;
        }
    }
}
