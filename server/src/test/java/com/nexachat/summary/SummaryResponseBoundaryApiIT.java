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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.Connection;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doCallRealMethod;
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
    @MockitoSpyBean private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private UserRepository users;
    @Autowired private ConversationRepository conversations;
    @Autowired private JwtService jwtService;
    @MockitoSpyBean private SummaryService summaryService;
    @MockitoSpyBean private SummaryProviderSelector providerSelector;
    @MockitoSpyBean private SummaryConsentRepository consentRepository;
    @Autowired private SummaryResponseWriter responseWriter;
    @Autowired private DataSource dataSource;

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
            SummaryExecution execution = (SummaryExecution) invocation.callRealMethod();
            SummaryResponse selected = execution.response();
            assertThat(selected.overview()).contains("alpha-bravo");
            assertThat(selected.cached()).isEqualTo(mode == Mode.CACHED);
            switch (mutation) {
                case REVOKE -> jdbcTemplate.update("UPDATE users SET ai_summary_consent = FALSE, "
                        + "ai_summary_consent_epoch = ai_summary_consent_epoch + 1 WHERE id = ?", caller.getId());
                case REGRANT -> {
                    jdbcTemplate.update("UPDATE users SET ai_summary_consent = FALSE, "
                            + "ai_summary_consent_epoch = ai_summary_consent_epoch + 1 WHERE id = ?", caller.getId());
                    jdbcTemplate.update("UPDATE users SET ai_summary_consent = TRUE, "
                            + "ai_summary_consent_epoch = ai_summary_consent_epoch + 1 WHERE id = ?", caller.getId());
                }
                case OPT_OUT -> jdbcTemplate.update(
                        "INSERT INTO conversation_ai_opt_outs (conversation_id, user_id) VALUES (?, ?)",
                        conversationId, peer.getId());
            }
            return execution;
        }).when((SummaryService) AopTestUtils.getUltimateTargetObject(summaryService))
                .summarizeForResponse(eq(conversationId), eq(caller.getId()), any());

        var result = postSummary(mode == Mode.REFRESH).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("AI_CONSENT_REQUIRED")).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("alpha-bravo");
    }

    static Stream<Arguments> boundaryCases() {
        return Stream.of(Mode.values()).flatMap(mode -> Stream.of(Mutation.values())
                .map(mutation -> Arguments.of(mode, mutation)));
    }

    @ParameterizedTest
    @EnumSource(Lane.class)
    void unchangedJsonShapeAndLocalReplayRemainCompatible(Lane lane) throws Exception {
        if (lane == Lane.LOCAL) {
            doCallRealMethod().when(providerSelector).prepare(anyBoolean(), any(), anyBoolean());
        }
        SummaryExecution execution = summaryService.summarizeForResponse(conversationId, caller.getId(), request());
        var expectedJson = objectMapper.readTree(objectMapper.writeValueAsString(execution.response()));
        if (lane == Lane.LOCAL) {
            assertThat(execution.consentSnapshot()).isNull();
            assertThat(execution.expectedExclusions()).isEmpty();
            jdbcTemplate.update("UPDATE users SET ai_summary_consent = FALSE WHERE id = ?", caller.getId());
            jdbcTemplate.update("INSERT INTO conversation_ai_opt_outs (conversation_id, user_id) VALUES (?, ?)",
                    conversationId, peer.getId());
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        responseWriter.write(caller.getId(), execution, response);
        var actual = objectMapper.readTree(response.getContentAsString());
        assertThat(actual).isEqualTo(expectedJson);
        assertThat(actual.has("consentSnapshot")).isFalse();
        assertThat(actual.has("expectedExclusions")).isFalse();
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
    }

    @ParameterizedTest
    @EnumSource(value = Mutation.class, names = {"REVOKE", "OPT_OUT"})
    void serializationHoldsBothAuthorizationLocksUntilJsonExists(Mutation mutation) throws Exception {
        SummaryExecution execution = summaryService.summarizeForResponse(conversationId, caller.getId(), request());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<Future<?>> mutationTask = new AtomicReference<>();
        doAnswer(invocation -> {
            if (invocation.getArgument(0) instanceof SummaryResponse) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                ConnectionHolder holder = (ConnectionHolder) TransactionSynchronizationManager.getResource(dataSource);
                assertThat(holder).isNotNull();
                int writerPid = jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class);
                AtomicReference<Integer> mutationPid = new AtomicReference<>();
                CountDownLatch started = new CountDownLatch(1);
                mutationTask.set(executor.submit(() -> {
                    try (Connection connection = dataSource.getConnection();
                         var pidQuery = connection.createStatement();
                         var pidRow = pidQuery.executeQuery("SELECT pg_backend_pid()")) {
                        assertThat(pidRow.next()).isTrue();
                        mutationPid.set(pidRow.getInt(1));
                        String sql = mutation == Mutation.REVOKE
                                ? "UPDATE users SET ai_summary_consent = FALSE WHERE id = ?"
                                : "INSERT INTO conversation_ai_opt_outs (conversation_id, user_id) VALUES (?, ?)";
                        try (var statement = connection.prepareStatement(sql)) {
                            statement.setObject(1, mutation == Mutation.REVOKE ? caller.getId() : conversationId);
                            if (mutation == Mutation.OPT_OUT) {
                                statement.setObject(2, peer.getId());
                            }
                            started.countDown();
                            statement.executeUpdate();
                        }
                    }
                    return null;
                }));
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                boolean blocked = false;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!blocked && System.nanoTime() < deadline) {
                    blocked = Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                            "SELECT ? = ANY(pg_blocking_pids(?))", Boolean.class, writerPid, mutationPid.get()));
                }
                assertThat(blocked).as("the actual mutation waits on the writer's user/conversation lock").isTrue();
                assertThat(mutationTask.get().isDone()).isFalse();
            }
            return invocation.callRealMethod();
        }).when(objectMapper).writeValueAsBytes(any());
        try {
            MockHttpServletResponse response = new MockHttpServletResponse();
            responseWriter.write(caller.getId(), execution, response);
            mutationTask.get().get(5, TimeUnit.SECONDS);
            assertThat(response.getContentAsString()).contains("alpha-bravo");
            assertThat(objectMapper.readTree(response.getContentAsString()))
                    .isEqualTo(objectMapper.readTree(objectMapper.writeValueAsString(execution.response())));
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void blockedHttpWriteReleasesDatabaseLeaseAndBothMutationLocks() throws Exception {
        SummaryExecution execution = summaryService.summarizeForResponse(conversationId, caller.getId(), request());
        AtomicReference<Connection> writerLease = new AtomicReference<>();
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            ConnectionHolder holder = (ConnectionHolder) TransactionSynchronizationManager.getResource(dataSource);
            assertThat(holder).isNotNull();
            writerLease.set(holder.getConnection());
            return result;
        }).when(consentRepository).lockForResponse(caller.getId());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        BlockingResponse response = new BlockingResponse(entered, release);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> write = executor.submit(() -> {
                responseWriter.write(caller.getId(), execution, response);
                return null;
            });
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(writerLease.get()).isNotNull();
            assertThat(writerLease.get().isClosed()).as("the pooled lease is returned before HTTP I/O").isTrue();
            Future<?> mutations = executor.submit(() -> {
                jdbcTemplate.update("UPDATE users SET ai_summary_consent = FALSE WHERE id = ?", caller.getId());
                jdbcTemplate.update("INSERT INTO conversation_ai_opt_outs (conversation_id, user_id) VALUES (?, ?)",
                        conversationId, peer.getId());
            });
            mutations.get(5, TimeUnit.SECONDS);
            assertThat(write.isDone()).isFalse();
            release.countDown();
            write.get(5, TimeUnit.SECONDS);
            assertThat(response.body.toString(StandardCharsets.UTF_8)).contains("alpha-bravo");
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private SummaryRequest request() {
        return new SummaryRequest(SummaryScope.LAST_N, 5, SummaryLanguage.EN, false);
    }

    private static final class BlockingResponse extends MockHttpServletResponse {
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private final CountDownLatch entered;
        private final CountDownLatch release;

        private BlockingResponse(CountDownLatch entered, CountDownLatch release) {
            this.entered = entered;
            this.release = release;
        }

        @Override
        public ServletOutputStream getOutputStream() {
            return new ServletOutputStream() {
                @Override public boolean isReady() { return true; }
                @Override public void setWriteListener(WriteListener listener) { }
                @Override public void write(int value) throws IOException {
                    entered.countDown();
                    try {
                        if (!release.await(10, TimeUnit.SECONDS)) {
                            throw new IOException("Timed out waiting for the controlled HTTP writer");
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IOException(exception);
                    }
                    body.write(value);
                }
            };
        }
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
    enum Lane { LLM, LOCAL }
}
