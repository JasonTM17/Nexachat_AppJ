package com.nexachat.summary;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SummaryProviderSelectorTest {

    @Test
    void lackOfConsentAlwaysSelectsLocalAndUsesLocalCacheIdentity() {
        StubProvider local = new StubProvider("LOCAL");
        StubProvider llm = new StubProvider("LLM");
        SummaryProviderSelector selector = selector(local, llm, true, "test-only-key");
        PreparedSummaryProvider prepared = selector.prepare(false);

        SummaryGeneration result = selector.generate(prepared, window(), SummaryLanguage.EN);

        assertThat(prepared.cacheProvider()).isEqualTo("LOCAL");
        assertThat(llm.calls).isZero();
        assertThat(local.calls).isEqualTo(1);
        assertThat(result.generatedBy()).isEqualTo("LOCAL");
        assertThat(result.fallbackReason()).isEqualTo(SummaryFallbackReason.NO_CONSENT);
    }

    @Test
    void missingOperatorConfigurationKeepsConsentedRequestLocal() {
        StubProvider local = new StubProvider("LOCAL");
        StubProvider llm = new StubProvider("LLM");
        SummaryProviderSelector selector = selector(local, llm, true, " ");
        PreparedSummaryProvider prepared = selector.prepare(true);

        SummaryGeneration result = selector.generate(prepared, window(), SummaryLanguage.EN);

        assertThat(prepared.cacheProvider()).isEqualTo("LOCAL");
        assertThat(llm.calls).isZero();
        assertThat(result.fallbackReason()).isEqualTo(SummaryFallbackReason.LLM_DISABLED);
    }

    @Test
    void consentAndOperatorConfigurationAreBothRequiredToCallLlm() {
        StubProvider local = new StubProvider("LOCAL");
        StubProvider llm = new StubProvider("LLM");
        SummaryProviderSelector selector = selector(local, llm, true, "test-only-key");
        PreparedSummaryProvider prepared = selector.prepare(true);

        SummaryGeneration result = selector.generate(prepared, window(), SummaryLanguage.EN);

        assertThat(prepared.cacheProvider()).isEqualTo("LLM");
        assertThat(llm.calls).isEqualTo(1);
        assertThat(local.calls).isZero();
        assertThat(result.generatedBy()).isEqualTo("LLM");
        assertThat(result.fallbackReason()).isNull();
    }

    @Test
    void providerTimeoutFallsBackToLocalWithTypedReason() {
        StubProvider local = new StubProvider("LOCAL");
        StubProvider llm = new StubProvider("LLM");
        llm.failure = new SummaryProviderException(SummaryFallbackReason.LLM_TIMEOUT);
        SummaryProviderSelector selector = selector(local, llm, true, "test-only-key");

        SummaryGeneration result = selector.generate(selector.prepare(true), window(), SummaryLanguage.EN);

        assertThat(llm.calls).isEqualTo(1);
        assertThat(local.calls).isEqualTo(1);
        assertThat(result.generatedBy()).isEqualTo("LOCAL");
        assertThat(result.fallbackReason()).isEqualTo(SummaryFallbackReason.LLM_TIMEOUT);
    }

    @Test
    void invalidLlmCitationsFallBackToLocal() {
        StubProvider local = new StubProvider("LOCAL");
        StubProvider llm = new StubProvider("LLM");
        llm.invalidCitation = true;
        SummaryProviderSelector selector = selector(local, llm, true, "test-only-key");

        SummaryGeneration result = selector.generate(selector.prepare(true), window(), SummaryLanguage.EN);

        assertThat(result.generatedBy()).isEqualTo("LOCAL");
        assertThat(result.fallbackReason()).isEqualTo(SummaryFallbackReason.LLM_BAD_OUTPUT);
    }

    @Test
    void localLlmEnabledAllowsRoutineWindowsToUseLlmProvider() {
        StubProvider local = new StubProvider("LOCAL");
        StubProvider llm = new StubProvider("LLM");
        SummaryProviderSelector selector = new SummaryProviderSelector(
                List.of(local, llm), "auto", false, "", true, new SummaryComplexityAssessor(12, 1500, 4));

        PreparedSummaryProvider prepared = selector.prepare(true, window(), false);
        assertThat(prepared.provider().id()).isEqualTo("LLM");
    }

    private static SummaryProviderSelector selector(
            StubProvider local, StubProvider llm, boolean enabled, String apiKey) {
        return new SummaryProviderSelector(List.of(local, llm), "auto", enabled, apiKey);
    }

    private static SummaryWindow window() {
        UUID conversationId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        return new SummaryWindow(conversationId, 0, 0, List.of(
                new SummaryMessage(UUID.randomUUID(), 1, senderId, "Sender", "First message", Instant.EPOCH, null),
                new SummaryMessage(UUID.randomUUID(), 2, senderId, "Sender", "Second message", Instant.EPOCH, null),
                new SummaryMessage(UUID.randomUUID(), 3, senderId, "Sender", "Third message", Instant.EPOCH, null),
                new SummaryMessage(UUID.randomUUID(), 4, senderId, "Sender", "Fourth message", Instant.EPOCH, null),
                new SummaryMessage(UUID.randomUUID(), 5, senderId, "Sender", "Fifth message", Instant.EPOCH, null)));
    }

    private static final class StubProvider implements SummaryProvider {

        private final String id;
        private int calls;
        private RuntimeException failure;
        private boolean invalidCitation;

        private StubProvider(String id) {
            this.id = id;
        }

        @Override
        public ProviderResult summarize(SummaryWindow window, SummaryLanguage language) {
            calls++;
            if (failure != null) {
                throw failure;
            }
            UUID citation = invalidCitation ? UUID.randomUUID() : window.messages().get(0).id();
            SummaryContent content = new SummaryContent(
                    "A concise overview.",
                    List.of(new SummaryItem("A key point.", List.of(citation))),
                    List.of(), List.of(), List.of(), List.of(), List.of());
            return new ProviderResult(content, SummaryLanguage.EN, null);
        }

        @Override
        public String id() {
            return id;
        }
    }
}
