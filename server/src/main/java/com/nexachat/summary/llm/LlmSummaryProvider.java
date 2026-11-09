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

        LlmClient.TargetConfig fallback = client.fallbackTarget();
        if (primary == null) {
            throw new SummaryProviderException(SummaryFallbackReason.LLM_DISABLED);
        }

        boolean breakerAllows = circuitBreaker.allowRequest();
        if (!breakerAllows && fallback == null && secondary == null) {
            throw new SummaryProviderException(SummaryFallbackReason.LLM_HTTP_ERROR);
        }

        boolean primaryAttempted = false;
        boolean breakerRecorded = false;
        try {
            Prompt prompt = promptBuilder.build(window, language, properties.getMaxInputChars());
            LlmClient.LlmCallResult completion;
            LlmClient.TargetConfig usedTarget;
            boolean primaryFailed = false;
            try {
                primaryAttempted = breakerAllows;
                completion = client.completeWithFallback(prompt, breakerAllows ? primary : null, fallback);
                usedTarget = completion.fallbackUsed() ? fallback : primary;
                primaryFailed = breakerAllows && completion.fallbackUsed();
            } catch (SummaryProviderException ex) {
                primaryFailed = breakerAllows;
                if (secondary == null) {
                    throw ex;
                }
                completion = client.completeWithTargetResult(prompt, secondary);
                usedTarget = secondary;
            } catch (RuntimeException ex) {
                primaryFailed = breakerAllows;
                if (secondary == null) {
                    throw ex;
                }
                completion = client.completeWithTargetResult(prompt, secondary);
                usedTarget = secondary;
            }

            ProviderResult result = outputValidator.validate(completion.content(), prompt, usedTarget.model().trim());
            if (usedTarget == primary) {
                circuitBreaker.recordSuccess();
                breakerRecorded = true;
            } else if (primaryFailed) {
                circuitBreaker.recordFailure();
                breakerRecorded = true;
            }
            String provider = usedTarget == fallback
                    ? "fallback"
                    : (usedTarget == primary ? "primary" : null);
            return new ProviderResult(result.content(), result.language(), result.model(), provider);
        } catch (SummaryProviderException exception) {
            if (primaryAttempted && !breakerRecorded) {
                circuitBreaker.recordFailure();
            }
            throw exception;
        } catch (RuntimeException exception) {
            if (primaryAttempted && !breakerRecorded) {
                circuitBreaker.recordFailure();
            }
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
