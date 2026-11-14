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
import com.nexachat.summary.ConversationAiOptOuts;
import com.nexachat.summary.SummaryMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;

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
    private final ConversationAiOptOuts aiOptOuts;

    public LlmSummaryProvider(
            LlmProperties properties,
            LlmClient client,
            PromptBuilder promptBuilder,
            LlmOutputValidator outputValidator,
            CircuitBreaker circuitBreaker,
            SummaryConsentRepository consentRepository) {
        this(properties, client, promptBuilder, outputValidator, circuitBreaker, consentRepository,
                new SummaryComplexityAssessor(12, 1500, 4), ConversationAiOptOuts.none());
    }

    public LlmSummaryProvider(
            LlmProperties properties,
            LlmClient client,
            PromptBuilder promptBuilder,
            LlmOutputValidator outputValidator,
            CircuitBreaker circuitBreaker,
            SummaryConsentRepository consentRepository,
            SummaryComplexityAssessor complexityAssessor) {
        this(properties, client, promptBuilder, outputValidator, circuitBreaker, consentRepository,
                complexityAssessor, ConversationAiOptOuts.none());
    }

    @Autowired
    public LlmSummaryProvider(
            LlmProperties properties,
            LlmClient client,
            PromptBuilder promptBuilder,
            LlmOutputValidator outputValidator,
            CircuitBreaker circuitBreaker,
            SummaryConsentRepository consentRepository,
            SummaryComplexityAssessor complexityAssessor,
            ConversationAiOptOuts aiOptOuts) {
        this.properties = properties;
        this.client = client;
        this.promptBuilder = promptBuilder;
        this.outputValidator = outputValidator;
        this.circuitBreaker = circuitBreaker;
        this.consentRepository = consentRepository;
        this.complexityAssessor = complexityAssessor;
        this.aiOptOuts = aiOptOuts;
    }

    @Override
    public ProviderResult summarize(SummaryWindow window, SummaryLanguage language) {
        if (!properties.isUsable() && !properties.isLocalUsable()) {
            throw new SummaryProviderException(SummaryFallbackReason.LLM_DISABLED);
        }
        if (!isConsentedCaller()) {
            throw new SummaryProviderException(SummaryFallbackReason.NO_CONSENT);
        }
        SummaryWindow externalWindow = externalWindow(window);
        if (externalWindow.messages().isEmpty()) {
            throw new SummaryProviderException(SummaryFallbackReason.NO_EXTERNAL_CONTEXT);
        }

        boolean isComplex = complexityAssessor.isComplex(window, false);
        LlmClient.TargetConfig primary;
        LlmClient.TargetConfig secondary;

        if (isComplex) {
            // Complex window ("quá khó"): prioritize the primary cloud model, fall back to Local Server Model
            if (properties.isUsable()) {
                primary = client.cloudTarget();
                secondary = properties.isLocalUsable() ? client.localTarget() : null;
            } else {
                primary = client.localTarget();
                secondary = null;
            }
        } else {
            // Routine window: prioritize Local Server Model, escalate to the primary cloud model
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
            // Re-check at the moment the external prompt is built: consent and
            // opt-out changes committed after request start must win the race.
            if (!isConsentedCaller()) {
                throw new SummaryProviderException(SummaryFallbackReason.NO_CONSENT);
            }
            Prompt prompt = promptBuilder.build(externalWindow, language, properties.getMaxInputChars());
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

    /**
     * Removes messages authored by members who opted out in this conversation.
     * The provider re-reads the persisted rows so a change committed after the
     * caller loaded the window still applies to the external prompt.
     */
    private SummaryWindow externalWindow(SummaryWindow window) {
        Set<UUID> optedOut = aiOptOuts.optedOutUserIds(window.conversationId());
        if (optedOut.isEmpty()) {
            return window;
        }
        List<SummaryMessage> kept = window.messages().stream()
                .filter(message -> message.senderId() == null
                        || !optedOut.contains(message.senderId()))
                .toList();
        return new SummaryWindow(window.conversationId(), 0, 0, kept);
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
