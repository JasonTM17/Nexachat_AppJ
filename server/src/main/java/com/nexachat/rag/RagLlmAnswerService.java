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
 * not-found instead of surfacing an error.
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
        LlmClient.TargetConfig target = llmClient.cloudTarget();
        if (target == null) {
            target = llmClient.localTarget();
        }
        if (target == null || !circuitBreaker.allowRequest()) {
            return null;
        }
        RagPrompt prompt = promptBuilder.build(question, language, context);
        try {
            String completion = llmClient.completeTextWithTarget(
                    prompt.systemMessage(), prompt.userMessage(), target);
            circuitBreaker.recordSuccess();
            return outputValidator.validate(completion, prompt);
        } catch (RuntimeException exception) {
            circuitBreaker.recordFailure();
            stats.recordLlmFailure();
            return null;
        }
    }
}
