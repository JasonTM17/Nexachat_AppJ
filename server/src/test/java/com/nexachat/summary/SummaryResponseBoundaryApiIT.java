package com.nexachat.summary;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.auth.JwtService;
import com.nexachat.conversation.Conversation;
import com.nexachat.conversation.ConversationRepository;
import com.nexachat.testutil.ItPhoneRanges;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.util.AopTestUtils;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real controller/database response boundary, with a synthetic provider and no network AI. */
@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("it")
class SummaryResponseBoundaryApiIT {
    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat"));
        registry.add("spring.datasource.username", () -> env("IT_DB_USER", "nexachat"));
        registry.add("spring.datasource.password", () -> env("IT_DB_PASSWORD", "nexachat"));
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private UserRepository users;
    @Autowired private ConversationRepository conversations;
    @Autowired private JwtService jwtService;
    @MockitoSpyBean private SummaryService summaryService;
    @MockitoSpyBean private SummaryProviderSelector providerSelector;

    private User caller;
    private User peer;
    private UUID conversationId;

    @BeforeEach
    void fixture() {
        caller = users.save(User.create(ItPhoneRanges.nextPhone(SummaryResponseBoundaryApiIT.class),
                "x".repeat(60), "Boundary caller"));
        peer = users.save(User.create(ItPhoneRanges.nextPhone(SummaryResponseBoundaryApiIT.class),
                "x".repeat(60), "Boundary peer"));
        conversationId = conversations.save(Conversation.group(caller, "Boundary fixture")).getId();
        for (User member : List.of(caller, peer)) {
            jdbcTemplate.update("INSERT INTO conversation_members (conversation_id, user_id, joined_at)"
                    + " VALUES (?, ?, now() - interval '1 day')", conversationId, member.getId());
        }
        jdbcTemplate.update("UPDATE users SET ai_summary_consent = TRUE WHERE id = ?", caller.getId());
        for (int index = 0; index < 5; index++) {
            jdbcTemplate.update("INSERT INTO messages (id, conversation_id, sender_id, client_message_id, type, content)"
                            + " VALUES (?, ?, ?, ?, 'TEXT', ?)",
                    UUID.randomUUID(), conversationId, index == 0 ? peer.getId() : caller.getId(),
                    UUID.randomUUID(), index == 0 ? "The secret value is alpha-bravo" : "Safe public decision");
        }
        SummaryProvider synthetic = new SummaryProvider() {
            @Override
            public ProviderResult summarize(SummaryWindow window, SummaryLanguage language) {
                SummaryMessage safe = window.messages().stream()
                        .filter(message -> message.senderId().equals(caller.getId())).findFirst().orElseThrow();
                return new ProviderResult(new SummaryContent("The secret value is alpha-bravo",
                        List.of(new SummaryItem("Safe public decision", List.of(safe.id()))),
                        List.of(), List.of(), List.of(), List.of(),
                        List.of(new SummaryParticipant(caller.getId(), "Boundary caller", 4))), language, null);
            }
            @Override public String id() { return "LLM"; }
        };
        doReturn(new PreparedSummaryProvider(synthetic, "LLM", null)).when(providerSelector)
                .prepare(anyBoolean(), any(), anyBoolean());
    }

    @AfterEach
    void cleanFixture() {
        if (conversationId != null) {
            jdbcTemplate.update("DELETE FROM conversations WHERE id = ?", conversationId);
        }
        for (User user : new User[] {caller, peer}) {
            if (user != null) {
                jdbcTemplate.update("DELETE FROM users WHERE id = ?", user.getId());
            }
        }
    }

    @ParameterizedTest
    @MethodSource("boundaryCases")
    void mutationAfterServiceReturnSuppressesHttpJson(Mode mode, Mutation mutation) throws Exception {
        if (mode == Mode.CACHED) {
            postSummary(false).andExpect(status().isOk()).andExpect(jsonPath("$.cached").value(false));
        }
        doAnswer(invocation -> {
            SummaryResponse selected = (SummaryResponse) invocation.callRealMethod();
            assertThat(selected.overview()).contains("alpha-bravo");
            assertThat(selected.cached()).isEqualTo(mode == Mode.CACHED);
            switch (mutation) {
                case REVOKE -> jdbcTemplate.update("UPDATE users SET ai_summary_consent = FALSE WHERE id = ?", caller.getId());
                case REGRANT -> {
                    jdbcTemplate.update("UPDATE users SET ai_summary_consent = FALSE WHERE id = ?", caller.getId());
                    jdbcTemplate.update("UPDATE users SET ai_summary_consent = TRUE WHERE id = ?", caller.getId());
                }
                case OPT_OUT -> jdbcTemplate.update(
                        "INSERT INTO conversation_ai_opt_outs (conversation_id, user_id) VALUES (?, ?)",
                        conversationId, peer.getId());
            }
            return selected;
        }).when((SummaryService) AopTestUtils.getUltimateTargetObject(summaryService))
                .summarize(eq(conversationId), eq(caller.getId()), any());

        var result = postSummary(mode == Mode.REFRESH).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("AI_CONSENT_REQUIRED")).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("alpha-bravo");
    }

    static Stream<Arguments> boundaryCases() {
        return Stream.of(Mode.values()).flatMap(mode -> Stream.of(Mutation.values())
                .map(mutation -> Arguments.of(mode, mutation)));
    }

    private org.springframework.test.web.servlet.ResultActions postSummary(boolean refresh) throws Exception {
        return mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/summary")
                .header("Authorization", "Bearer " + jwtService.generateAccessToken(caller))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(java.util.Map.of(
                        "scope", "LAST_N", "limit", 5, "language", "EN", "refresh", refresh))));
    }

    enum Mode { FRESH, CACHED, REFRESH }
    enum Mutation { REVOKE, REGRANT, OPT_OUT }
}
