package com.nexachat.summary;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Component
public class SummaryProviderSelector {

    private final SummaryProvider localProvider;
    private final SummaryProvider llmProvider;
    private final String configuredProvider;
    private final boolean llmEnabled;
    private final boolean operatorKeyConfigured;
    private final boolean localLlmEnabled;
    private final SummaryComplexityAssessor complexityAssessor;

    public SummaryProviderSelector(
            List<SummaryProvider> providers, String configuredProvider, boolean llmEnabled, String operatorApiKey) {
        this(providers, configuredProvider, llmEnabled, operatorApiKey, false, new SummaryComplexityAssessor(12, 1500, 4));
    }

    public SummaryProviderSelector(
            List<SummaryProvider> providers,
            String configuredProvider,
            boolean llmEnabled,
            String operatorApiKey,
            SummaryComplexityAssessor complexityAssessor) {
        this(providers, configuredProvider, llmEnabled, operatorApiKey, false, complexityAssessor);
    }

    @Autowired
    public SummaryProviderSelector(
            List<SummaryProvider> providers,
            @Value("${nexachat.summary.provider:auto}") String configuredProvider,
            @Value("${nexachat.summary.llm.enabled:false}") boolean llmEnabled,
            @Value("${nexachat.summary.llm.api-key:}") String operatorApiKey,
            @Value("${nexachat.summary.llm.local-enabled:false}") boolean localLlmEnabled,
            SummaryComplexityAssessor complexityAssessor) {
        this.complexityAssessor = complexityAssessor;
        this.localProvider = findProvider(providers, "LOCAL");
        this.llmProvider = providers.stream()
                .filter(provider -> provider.id().equals("LLM"))
                .findFirst()
                .orElse(null);
        this.configuredProvider = configuredProvider.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("auto", "local", "llm").contains(this.configuredProvider)) {
            throw new IllegalArgumentException("Unsupported summary provider mode");
        }
        this.llmEnabled = llmEnabled;
        this.operatorKeyConfigured = operatorApiKey != null && !operatorApiKey.isBlank();
        this.localLlmEnabled = localLlmEnabled;
    }

    /**
     * Local-first routing: the local engine/model serves routine windows;
     * the primary cloud model is escalated to only when a window is complex or an explicit refresh is requested.
     */
    public PreparedSummaryProvider prepare(boolean consented, SummaryWindow window, boolean refresh) {
        PreparedSummaryProvider base = prepare(consented);
        if (base.provider().id().equals("LLM")) {
            boolean isComplex = complexityAssessor.isComplex(window, refresh);
            if (!localLlmEnabled && !isComplex) {
                return new PreparedSummaryProvider(requireLocalProvider(), "LOCAL", null);
            }
        }
        return base;
    }

    public PreparedSummaryProvider prepare(boolean consented) {
        if (configuredProvider.equals("local")) {
            return new PreparedSummaryProvider(requireLocalProvider(), "LOCAL", null);
        }
        if (!consented) {
            return new PreparedSummaryProvider(requireLocalProvider(), "LOCAL", SummaryFallbackReason.NO_CONSENT);
        }
        boolean hasAnyLlm = (llmEnabled && operatorKeyConfigured) || localLlmEnabled;
        if (!hasAnyLlm || llmProvider == null) {
            return new PreparedSummaryProvider(requireLocalProvider(), "LOCAL", SummaryFallbackReason.LLM_DISABLED);
        }
        return new PreparedSummaryProvider(llmProvider, "LLM", null);
    }

    public SummaryGeneration generate(
            PreparedSummaryProvider prepared,
            SummaryWindow window,
            SummaryLanguage requestedLanguage) {
        SummaryProvider provider = prepared.provider();
        if (!provider.id().equals("LLM")) {
            ProviderResult result = provider.summarize(window, requestedLanguage);
            validate(result, window);
            return generation(result, provider.id(), prepared.fallbackReason());
        }

        try {
            ProviderResult result = provider.summarize(window, requestedLanguage);
            validate(result, window);
            return generation(result, provider.id(), null);
        } catch (SummaryProviderException ex) {
            return fallback(window, requestedLanguage, ex.fallbackReason());
        } catch (IllegalArgumentException | NullPointerException ex) {
            return fallback(window, requestedLanguage, SummaryFallbackReason.LLM_BAD_OUTPUT);
        } catch (RuntimeException ex) {
            return fallback(window, requestedLanguage, SummaryFallbackReason.LLM_HTTP_ERROR);
        }
    }

    private SummaryGeneration fallback(
            SummaryWindow window,
            SummaryLanguage requestedLanguage,
            SummaryFallbackReason reason) {
        SummaryProvider local = requireLocalProvider();
        ProviderResult result = local.summarize(window, requestedLanguage);
        validate(result, window);
        return generation(result, local.id(), reason);
    }

    private static SummaryGeneration generation(
            ProviderResult result,
            String generatedBy,
            SummaryFallbackReason fallbackReason) {
        return new SummaryGeneration(result.content(), result.language(), generatedBy, result.model(),
                fallbackReason, result.provider());
    }

    private static SummaryProvider findProvider(List<SummaryProvider> providers, String id) {
        return providers.stream()
                .filter(provider -> provider.id().equals(id))
                .findFirst()
                .orElse(null);
    }

    private SummaryProvider requireLocalProvider() {
        if (localProvider == null) {
            throw new IllegalStateException("Local summary provider is not registered");
        }
        return localProvider;
    }

    private static void validate(ProviderResult result, SummaryWindow window) {
        if (result == null || result.content() == null || result.language() == SummaryLanguage.AUTO) {
            throw new IllegalArgumentException("Provider returned an invalid summary");
        }
        SummaryContent content = result.content();
        if (content.overview().codePointCount(0, content.overview().length()) > 400) {
            throw new IllegalArgumentException("Summary overview exceeds its limit");
        }

        Set<UUID> inputIds = new HashSet<>();
        Set<UUID> participantIds = new HashSet<>();
        window.messages().forEach(message -> {
            inputIds.add(message.id());
            participantIds.add(message.senderId());
        });
        content.keyPoints().forEach(item -> validateCitations(item.text(), item.messageIds(), inputIds));
        content.decisions().forEach(item -> validateCitations(item.text(), item.messageIds(), inputIds));
        content.questions().forEach(item -> validateCitations(item.text(), item.messageIds(), inputIds));
        content.actionItems().forEach(item -> {
            validateCitations(item.text(), item.messageIds(), inputIds);
            if (item.ownerUserId() != null && !participantIds.contains(item.ownerUserId())) {
                throw new IllegalArgumentException("Summary action owner is outside the input window");
            }
        });
        content.participants().forEach(participant -> {
            if (!participantIds.contains(participant.userId()) || participant.messageCount() < 0) {
                throw new IllegalArgumentException("Summary participant is outside the input window");
            }
        });
    }

    private static void validateCitations(String text, List<UUID> messageIds, Set<UUID> inputIds) {
        if (text == null || text.isBlank() || messageIds == null || messageIds.isEmpty()
                || !inputIds.containsAll(messageIds)) {
            throw new IllegalArgumentException("Summary item has invalid citations");
        }
    }
}
