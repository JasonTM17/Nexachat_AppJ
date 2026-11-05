package com.nexachat.user;

import com.nexachat.auth.JwtService;
import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.summary.PreparedSummaryProvider;
import com.nexachat.summary.SummaryConsentRepository;
import com.nexachat.summary.SummaryFallbackReason;
import com.nexachat.summary.SummaryLanguage;
import com.nexachat.summary.SummaryProviderSelector;
import com.nexachat.summary.SummaryMessage;
import com.nexachat.summary.SummaryWindow;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "nexachat.summary.provider=auto",
        "nexachat.summary.llm.enabled=true",
        "nexachat.summary.llm.api-key=local-test-token"
})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("it")
class UserSettingsFlowIT {

    private static final String SETTINGS_URI = "/api/v1/users/me/settings";
    private static final AtomicInteger FAKE_LLM_REQUESTS = new AtomicInteger();
    private static volatile HttpServer fakeLlmServer;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> env("IT_DB_URL",
                "jdbc:postgresql://localhost:5433/nexachat"));
        registry.add("spring.datasource.username", () -> env("IT_DB_USER", "nexachat"));
        registry.add("spring.datasource.password", () -> env("IT_DB_PASSWORD", "nexachat"));
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "2");
        registry.add("nexachat.summary.llm.base-url", UserSettingsFlowIT::fakeLlmBaseUrl);
    }

    private static String fakeLlmBaseUrl() {
        if (fakeLlmServer == null) {
            synchronized (UserSettingsFlowIT.class) {
                if (fakeLlmServer == null) {
                    try {
                        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                        server.createContext("/chat/completions", exchange -> {
                            FAKE_LLM_REQUESTS.incrementAndGet();
                            exchange.getRequestBody().readAllBytes();
                            exchange.sendResponseHeaders(503, -1);
                            exchange.close();
                        });
                        server.start();
                        fakeLlmServer = server;
                    } catch (IOException exception) {
                        throw new IllegalStateException("Could not start the fake summary provider", exception);
                    }
                }
            }
        }
        return "http://127.0.0.1:" + fakeLlmServer.getAddress().getPort();
    }

    @AfterAll
    static void stopFakeLlmServer() {
        if (fakeLlmServer != null) {
            fakeLlmServer.stop(0);
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private SummaryConsentRepository summaryConsentRepository;
    @Autowired
    private SummaryProviderSelector summaryProviderSelector;

    private User user;
    private String accessToken;

    @BeforeEach
    void createUser() {
        String suffix = String.format(Locale.ROOT, "%09d", Math.floorMod(System.nanoTime(), 1_000_000_000L));
        user = userRepository.saveAndFlush(User.create("+849" + suffix, "x".repeat(60), "Settings fixture"));
        accessToken = jwtService.generateAccessToken(user);
    }

    @AfterEach
    void removeUser() {
        SecurityContextHolder.clearContext();
        if (user != null) {
            userRepository.deleteById(user.getId());
        }
    }

    @Test
    void getAndPatchExposeIndependentPresenceAndSummaryConsentSettings() throws Exception {
        FAKE_LLM_REQUESTS.set(0);
        mockMvc.perform(get(SETTINGS_URI).header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.showPresence").value(true))
                .andExpect(jsonPath("$.aiSummaryConsent").value(false));
        assertThat(summaryConsentRepository.isConsented(user.getId())).isFalse();

        mockMvc.perform(patch(SETTINGS_URI)
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"aiSummaryConsent\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.showPresence").value(true))
                .andExpect(jsonPath("$.aiSummaryConsent").value(true));
        assertThat(summaryConsentRepository.isConsented(user.getId())).isTrue();
        PreparedSummaryProvider consentedSelection = summaryProviderSelector.prepare(
                summaryConsentRepository.isConsented(user.getId()));
        assertThat(consentedSelection.cacheProvider()).isEqualTo("LLM");
        assertThat(consentedSelection.fallbackReason()).isNull();
        mockMvc.perform(get(SETTINGS_URI).header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiSummaryConsent").value(true));

        mockMvc.perform(patch(SETTINGS_URI)
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"showPresence\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.showPresence").value(false))
                .andExpect(jsonPath("$.aiSummaryConsent").value(true));

        mockMvc.perform(patch(SETTINGS_URI)
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"aiSummaryConsent\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.showPresence").value(false))
                .andExpect(jsonPath("$.aiSummaryConsent").value(false));
        assertThat(summaryConsentRepository.isConsented(user.getId())).isFalse();
        PreparedSummaryProvider revokedSelection = summaryProviderSelector.prepare(
                summaryConsentRepository.isConsented(user.getId()));
        assertThat(revokedSelection.cacheProvider()).isEqualTo("LOCAL");
        assertThat(revokedSelection.fallbackReason()).isEqualTo(SummaryFallbackReason.NO_CONSENT);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new JwtPrincipal(user.getId()), null, List.of()));
        var generatedAfterRevocation = summaryProviderSelector.generate(
                consentedSelection, summaryWindow(), SummaryLanguage.EN);
        assertThat(generatedAfterRevocation.generatedBy()).isEqualTo("LOCAL");
        assertThat(generatedAfterRevocation.fallbackReason()).isEqualTo(SummaryFallbackReason.NO_CONSENT);
        assertThat(FAKE_LLM_REQUESTS).hasValue(0);
        mockMvc.perform(get(SETTINGS_URI).header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.showPresence").value(false))
                .andExpect(jsonPath("$.aiSummaryConsent").value(false));

        mockMvc.perform(patch(SETTINGS_URI)
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void settingsRequireAuthenticationForGetAndPatch() throws Exception {
        mockMvc.perform(get(SETTINGS_URI))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(patch(SETTINGS_URI)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"aiSummaryConsent\":true}"))
                .andExpect(status().isUnauthorized());
    }

    private String bearer() {
        return "Bearer " + accessToken;
    }

    private SummaryWindow summaryWindow() {
        List<SummaryMessage> messages = new ArrayList<>();
        for (int sequence = 1; sequence <= 5; sequence++) {
            messages.add(new SummaryMessage(UUID.randomUUID(), sequence, user.getId(), user.getDisplayName(),
                    "Synthetic discussion item " + sequence, Instant.EPOCH, null));
        }
        return new SummaryWindow(UUID.randomUUID(), 0, 0, messages);
    }
}
