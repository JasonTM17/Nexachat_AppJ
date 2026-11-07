package com.nexachat.summary.llm;

import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.summary.ProviderResult;
import com.nexachat.summary.SummaryFallbackReason;
import com.nexachat.summary.SummaryLanguage;
import com.nexachat.summary.SummaryProvider;
import com.nexachat.summary.SummaryProviderException;
import com.nexachat.summary.SummaryWindow;
import com.nexachat.summary.SummaryConsentRepository;
import com.nexachat.summary.SummaryComplexityAssessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public final class LlmSummaryProvider implements SummaryProvider {

    public static final String PROVIDER_ID = "LLM";

    private final LlmProperties properties;
    private final LlmClient client;
    private final PromptBuilder promptBuilder;
    private final LlmOutputValidator outputValidator;
    private final CircuitBreaker circuitBreaker;
    private final SummaryConsentRepository consentRepository;
    private final SummaryComplexityAssessor complexityAssessor;

    public LlmSummaryProvider(
            LlmProperties properties,
            LlmClient client,
            PromptBuilder promptBuilder,
            LlmOutputValidator outputValidator,
            CircuitBreaker circuitBreaker,
            SummaryConsentRepository consentRepository) {
        this(properties, client, promptBuilder, outputValidator, circuitBreaker, consentRepository,
                new SummaryComplexityAssessor(12, 1500, 4));
    }

    @Autowired
    public LlmSummaryProvider(
            LlmProperties properties,
            LlmClient client,
            PromptBuilder promptBuilder,
            LlmOutputValidator outputValidator,
            CircuitBreaker circuitBreaker,
            SummaryConsentRepository consentRepository,
            SummaryComplexityAssessor complexityAssessor) {
        this.properties = properties;
        this.client = client;
        this.promptBuilder = promptBuilder;
        this.outputValidator = outputValidator;
        this.circuitBreaker = circuitBreaker;
        this.consentRepository = consentRepository;
        this.complexityAssessor = complexityAssessor;
    }

    @Override
    public ProviderResult summarize(SummaryWindow window, SummaryLanguage language) {
        if (!properties.isUsable() && !properties.isLocalUsable()) {
            throw new SummaryProviderException(SummaryFallbackReason.LLM_DISABLED);
        }
        if (!isConsentedCaller()) {
            throw new SummaryProviderException(SummaryFallbackReason.NO_CONSENT);
        }
        if (!circuitBreaker.allowRequest()) {
            throw new SummaryProviderException(SummaryFallbackReason.LLM_HTTP_ERROR);
        }

        boolean isComplex = complexityAssessor.isComplex(window, false);
        LlmClient.TargetConfig primary;
        LlmClient.TargetConfig secondary;

        if (isComplex) {
            // Complex window ("quá khó"): prioritize Cloud DeepSeek, fall back to Local Server Model
            if (properties.isUsable()) {
                primary = client.cloudTarget();
                secondary = properties.isLocalUsable() ? client.localTarget() : null;
            } else {
                primary = client.localTarget();
                secondary = null;
            }
        } else {
            // Routine window: prioritize Local Server Model, escalate to Cloud DeepSeek
            if (properties.isLocalUsable()) {
                primary = client.localTarget();
                secondary = properties.isUsable() ? client.cloudTarget() : null;
            } else {
                primary = client.cloudTarget();
                secondary = null;
            }
        }

        if (primary == null) {
            throw new SummaryProviderException(SummaryFallbackReason.LLM_DISABLED);
        }

        try {
            Prompt prompt = promptBuilder.build(window, language, properties.getMaxInputChars());
            LlmClient.LlmCompletion completion;
            LlmClient.TargetConfig usedTarget = primary;
            try {
                completion = client.completeWithTarget(prompt, primary);
            } catch (SummaryProviderException ex) {
                if (secondary != null) {
                    usedTarget = secondary;
                    completion = client.completeWithTarget(prompt, secondary);
                } else {
                    throw ex;
                }
            } catch (RuntimeException ex) {
                if (secondary != null) {
                    usedTarget = secondary;
                    completion = client.completeWithTarget(prompt, secondary);
                } else {
                    throw ex;
                }
            }

            ProviderResult result = outputValidator.validate(completion.content(), prompt, usedTarget.model().trim());
            circuitBreaker.recordSuccess();
            return result;
        } catch (SummaryProviderException exception) {
            circuitBreaker.recordFailure();
            throw exception;
        } catch (RuntimeException exception) {
            circuitBreaker.recordFailure();
            throw new SummaryProviderException(SummaryFallbackReason.LLM_HTTP_ERROR);
        }
    }

    @Override
    public String id() {
        return PROVIDER_ID;
    }

    private boolean isConsentedCaller() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof JwtPrincipal principal)) {
            return false;
        }
        return consentRepository.isConsented(principal.userId());
    }
}
