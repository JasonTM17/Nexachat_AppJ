package com.nexachat.summary.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.common.FakeOpenAiServer;
import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.summary.ConversationAiOptOuts;
import com.nexachat.summary.ProviderResult;
import com.nexachat.summary.SummaryComplexityAssessor;
import com.nexachat.summary.SummaryConsentRepository;
import com.nexachat.summary.SummaryContent;
import com.nexachat.summary.SummaryFallbackReason;
import com.nexachat.summary.SummaryItem;
import com.nexachat.summary.SummaryLanguage;
import com.nexachat.summary.SummaryMessage;
import com.nexachat.summary.SummaryParticipant;
import com.nexachat.summary.SummaryProvider;
import com.nexachat.summary.SummaryProviderException;
import com.nexachat.summary.SummaryProviderSelector;
import com.nexachat.summary.SummaryWindow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmSummaryOptOutTest {

    private FakeOpenAiServer server;
    private LlmClient client;

    @AfterEach
    void closeResources() {
        SecurityContextHolder.clearContext();
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close();
        }
    }

    @Test
    void externalPromptOmitsMessagesFromAnOptedOutAuthor() throws Exception {
        startServer();
        UUID caller = UUID.randomUUID();
        UUID optedOutSender = UUID.randomUUID();
        UUID safeSender = UUID.randomUUID();
        setPrincipal(caller);
        List<SummaryMessage> messages = new ArrayList<>();
        messages.add(new SummaryMessage(UUID.randomUUID(), 1, optedOutSender, "Opted out",
                "The private detail is alpha-bravo.", Instant.EPOCH, null));
        messages.add(new SummaryMessage(UUID.randomUUID(), 2, safeSender, "Safe",
                "The team agreed to ship on Friday.", Instant.EPOCH, null));
        messages.add(new SummaryMessage(UUID.randomUUID(), 3, safeSender, "Safe",
                "The release notes are ready.", Instant.EPOCH, null));
        messages.add(new SummaryMessage(UUID.randomUUID(), 4, safeSender, "Safe",
                "The demo is scheduled.", Instant.EPOCH, null));
        messages.add(new SummaryMessage(UUID.randomUUID(), 5, safeSender, "Safe",
                "The review is complete.", Instant.EPOCH, null));
        SummaryWindow window = new SummaryWindow(UUID.randomUUID(), 0, 0, messages);
        ConversationAiOptOuts optOuts = optOuts(caller, Set.of(optedOutSender));
        LlmSummaryProvider provider = provider(optOuts, consent(caller, true));

        ProviderResult result = provider.summarize(window, SummaryLanguage.EN);

        String body = server.requests().get(0).body();
        assertThat(body).doesNotContain("alpha-bravo");
        assertThat(body).contains("ship on Friday");
        assertThat(result.content().keyPoints().get(0).messageIds())
                .containsExactly(messages.get(1).id());
    }

    @Test
    void allOptedOutMessagesFallBackToLocalWithoutAnExternalCall() throws Exception {
        startServer();
        UUID caller = UUID.randomUUID();
        UUID optedOutSender = UUID.randomUUID();
        setPrincipal(caller);
        SummaryWindow window = new SummaryWindow(UUID.randomUUID(), 0, 0, List.of(
                message(1, optedOutSender, "The private detail is alpha-bravo."),
                message(2, optedOutSender, "Another private detail."),
                message(3, optedOutSender, "Third private detail."),
                message(4, optedOutSender, "Fourth private detail."),
                message(5, optedOutSender, "Fifth private detail.")));
        LlmProperties properties = properties();
        client = new LlmClient(properties, new ObjectMapper());
        LlmSummaryProvider llm = provider(properties, optOuts(caller, Set.of(optedOutSender)),
                consent(caller, true));
        StubLocalProvider local = new StubLocalProvider();
        SummaryProviderSelector selector = new SummaryProviderSelector(
                List.of(local, llm), "auto", true, "test-key");

        var result = selector.generate(selector.prepare(true), window, SummaryLanguage.EN);

        assertThat(result.generatedBy()).isEqualTo("LOCAL");
        assertThat(result.fallbackReason()).isEqualTo(SummaryFallbackReason.NO_EXTERNAL_CONTEXT);
        assertThat(server.requestCount()).isZero();
    }

    @Test
    void revokedConsentAtPromptBuildPreventsTheCall() throws Exception {
        startServer();
        UUID caller = UUID.randomUUID();
        UUID sender = UUID.randomUUID();
        setPrincipal(caller);
        SummaryWindow window = new SummaryWindow(UUID.randomUUID(), 0, 0, List.of(
                message(1, sender, "The team agreed on the plan."),
                message(2, sender, "The release notes are ready."),
                message(3, sender, "The demo is scheduled."),
                message(4, sender, "The review is complete."),
                message(5, sender, "The next step is the rollout.")));
        SummaryConsentRepository consent = mock(SummaryConsentRepository.class);
        when(consent.isConsented(caller)).thenReturn(true, false);
        LlmSummaryProvider provider = provider(optOuts(caller, Set.of()), consent);

        assertThatThrownBy(() -> provider.summarize(window, SummaryLanguage.EN))
                .isInstanceOf(SummaryProviderException.class)
                .extracting("fallbackReason").isEqualTo(SummaryFallbackReason.NO_CONSENT);
        assertThat(server.requestCount()).isZero();
    }

    private LlmSummaryProvider provider(
            ConversationAiOptOuts optOuts, SummaryConsentRepository consent) {
        LlmProperties properties = properties();
        client = new LlmClient(properties, new ObjectMapper());
        return provider(properties, optOuts, consent);
    }

    private LlmSummaryProvider provider(
            LlmProperties properties, ConversationAiOptOuts optOuts, SummaryConsentRepository consent) {
        return new LlmSummaryProvider(properties, client, new PromptBuilder(new ObjectMapper(), new Redactor()),
                new LlmOutputValidator(), new CircuitBreaker(Clock.systemUTC()), consent,
                new SummaryComplexityAssessor(1, 1, 1), optOuts);
    }

    private LlmProperties properties() {
        LlmProperties properties = new LlmProperties();
        properties.setEnabled(true);
        properties.setApiKey("test-key");
        properties.setModel("test-model");
        properties.setBaseUrl(server.baseUrl());
        properties.setTimeoutMs(5_000);
        return properties;
    }

    private void startServer() throws Exception {
        server = new FakeOpenAiServer();
        server.handle((exchange, body, authorization) -> FakeOpenAiServer.respond(exchange, 200,
                FakeOpenAiServer.jsonResponse(
                        "{\"overview\":\"The group agreed on the next steps.\","
                                + "\"keyPoints\":[{\"text\":\"The team agreed.\",\"citations\":[1]}],"
                                + "\"decisions\":[],\"actionItems\":[],\"questions\":[],\"topics\":[\"Next steps\"]}")));
    }

    private static void setPrincipal(UUID caller) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new JwtPrincipal(caller), null, List.of()));
    }

    private static SummaryConsentRepository consent(UUID caller, boolean value) {
        SummaryConsentRepository consent = mock(SummaryConsentRepository.class);
        when(consent.isConsented(caller)).thenReturn(value);
        return consent;
    }

    private static ConversationAiOptOuts optOuts(UUID caller, Set<UUID> optedOut) {
        return new ConversationAiOptOuts() {
            @Override
            public Set<UUID> optedOutUserIds(UUID conversationId) {
                return Set.copyOf(optedOut);
            }

            @Override
            public Set<UUID> optedOutConversationIds(UUID userId) {
                return userId.equals(caller) && !optedOut.isEmpty() ? Set.of(UUID.randomUUID()) : Set.of();
            }

            @Override
            public boolean hasOptedOutMessages(UUID conversationId) {
                return !optedOut.isEmpty();
            }

            @Override
            public boolean isOptedOut(UUID conversationId, UUID userId) {
                return optedOut.contains(userId);
            }
        };
    }

    private static SummaryMessage message(long seq, UUID sender, String text) {
        return new SummaryMessage(UUID.randomUUID(), seq, sender, "Sender", text, Instant.EPOCH, null);
    }

    private static final class StubLocalProvider implements SummaryProvider {

        @Override
        public ProviderResult summarize(SummaryWindow window, SummaryLanguage language) {
            return new ProviderResult(
                    new SummaryContent("Local summary", List.of(), List.of(), List.of(), List.of(),
                            List.of(), List.of()),
                    SummaryLanguage.EN,
                    null);
        }

        @Override
        public String id() {
            return "LOCAL";
        }
    }
}
