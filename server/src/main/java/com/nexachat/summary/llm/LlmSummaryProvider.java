package com.nexachat.summary.llm;

import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.summary.ProviderResult;
import com.nexachat.summary.SummaryFallbackReason;
import com.nexachat.summary.SummaryLanguage;
import com.nexachat.summary.SummaryProvider;
import com.nexachat.summary.SummaryProviderException;
import com.nexachat.summary.SummaryWindow;
import com.nexachat.summary.SummaryConsentRepository;
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

    public LlmSummaryProvider(
            LlmProperties properties,
            LlmClient client,
            PromptBuilder promptBuilder,
            LlmOutputValidator outputValidator,
            CircuitBreaker circuitBreaker,
            SummaryConsentRepository consentRepository) {
        this.properties = properties;
        this.client = client;
        this.promptBuilder = promptBuilder;
        this.outputValidator = outputValidator;
        this.circuitBreaker = circuitBreaker;
        this.consentRepository = consentRepository;
    }

    @Override
    public ProviderResult summarize(SummaryWindow window, SummaryLanguage language) {
        if (!properties.isUsable()) {
            throw new SummaryProviderException(SummaryFallbackReason.LLM_DISABLED);
        }
        if (!isConsentedCaller()) {
            throw new SummaryProviderException(SummaryFallbackReason.NO_CONSENT);
        }
        if (!circuitBreaker.allowRequest()) {
            throw new SummaryProviderException(SummaryFallbackReason.LLM_HTTP_ERROR);
        }
        try {
            Prompt prompt = promptBuilder.build(window, language, properties.getMaxInputChars());
            String output = client.complete(prompt);
            ProviderResult result = outputValidator.validate(output, prompt, properties.getModel().trim());
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
