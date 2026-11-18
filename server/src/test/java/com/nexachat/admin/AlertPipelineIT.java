package com.nexachat.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.auth.JwtService;
import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.Conversation;
import com.nexachat.conversation.ConversationRepository;
import com.nexachat.message.MessageService;
import com.nexachat.message.dto.MessageResponse;
import com.nexachat.message.dto.SendMessageRequest;
import com.nexachat.summary.ConversationAiOptOutService;
import com.nexachat.testutil.ItPhoneRanges;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage of the sensitive-message pipeline: real sends trigger
 * the AFTER_COMMIT listener, the bounded queue drains, alerts land with a
 * redacted snippet — and the hard exclusions (E2EE ciphertext, opted-out
 * senders) produce nothing. Workflow, reveal guards and audit immutability
 * are exercised at the same seam.
 */
@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("it")
class AlertPipelineIT {

    private static final Duration ALERT_WAIT = Duration.ofSeconds(10);

    @DynamicPropertySource
    static void pipelineItProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat_test"));
        registry.add("spring.datasource.username", () -> env("IT_DB_USER", "nexachat"));
        registry.add("spring.datasource.password", () -> env("IT_DB_PASSWORD", "nexachat"));
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "2");
        registry.add("nexachat.admin.reveal.per-minute", () -> "3");
        registry.add("nexachat.admin.alerts.drain-interval-ms", () -> "150");
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ConversationRepository conversationRepository;
    @Autowired
    private MessageService messageService;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private AlertScanService scanService;
    @Autowired
    private AlertScanQueue scanQueue;
    @Autowired
    private AlertScanListener scanListener;
    @Autowired
    private AlertRuleRepository ruleRepository;
    @Autowired
    private AdminUserService adminUserService;
    @Autowired
    private AlertService alertService;
    @Autowired
    private ConversationAiOptOutService optOutService;

    private User admin;
    private User sender;
    private User peer;
    private UUID conversationId;
    private long nextSeq = 1;

    @BeforeEach
    void createFixture() {
        admin = createUser("Admin Probe");
        jdbcTemplate.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", admin.getId());
        sender = createUser("Sender Probe");
        peer = createUser("Peer Probe");
        Conversation conversation = conversationRepository.save(
                Conversation.group(admin, "Alert probe"));
        conversationId = conversation.getId();
        for (UUID member : List.of(admin.getId(), sender.getId(), peer.getId())) {
            addMember(member);
        }
        // Deterministic audit assertions: only this suite writes the table,
        // and a clean slate keeps "exactly one row" claims honest.
        jdbcTemplate.execute("TRUNCATE admin_audit_log");
    }

    @AfterEach
    void removeFixture() {
        // user_notices has a plain user FK (no cascade) — clear it before the
        // shared cleaner deletes the fixture users.
        jdbcTemplate.update("""
                DELETE FROM user_notices WHERE user_id IN
                (SELECT id FROM users WHERE phone LIKE ?)
                """, ItPhoneRanges.prefixFor(AlertPipelineIT.class) + "%");
        jdbcTemplate.update("DELETE FROM alert_rules WHERE built_in = FALSE");
        ItPhoneRanges.clean(jdbcTemplate, AlertPipelineIT.class);
    }

    // ------------------------------------------------------------- scanning

    @Test
    void credentialKeywordSendCreatesAnAlertWithARedactedSnippet() {
        send(sender, "hey, send me the code 482913 right now");

        List<Map<String, Object>> alerts = awaitAlerts(1);
        Map<String, Object> alert = alerts.get(0);
        assertThat(alert.get("category")).isEqualTo("CREDENTIAL_SHARING");
        assertThat(alert.get("severity")).isEqualTo("HIGH");
        assertThat(alert.get("status")).isEqualTo("OPEN");
        String snippet = (String) alert.get("snippet");
        assertThat(snippet).contains("send me the code");
        assertThat(snippet).doesNotContain("482913").contains("[code]");
    }

    @Test
    void vietnameseKeywordFoldsDiacritics() {
        send(sender, "em mất mã otp rồi, gửi mã cho em với");

        List<Map<String, Object>> alerts = awaitAlerts(1);
        assertThat(alerts.get(0).get("category")).isEqualTo("CREDENTIAL_SHARING");
    }

    @Test
    void leetspeakStillMatchesAKeyword() {
        send(sender, "s3nd m3 th3 c0d3 quickly — send me the code");

        // "send me the code" matches directly; the leet prefix is the
        // second check that folding + leet view cover obfuscation.
        assertThat(awaitAlerts(1)).isNotEmpty();
    }

    @Test
    void luhnValidCardMatchesButRandomDigitsDoNot() {
        send(sender, "my card is 4111111111111111 exp 12/28");

        List<Map<String, Object>> cardAlerts = awaitAlerts(1);
        assertThat(cardAlerts.get(0).get("category")).isEqualTo("ID_NUMBER");

        send(sender, "my random id is 1234567890123456");
        // Give the drain a beat, then assert the second message produced no
        // ID_NUMBER alert (it matches no Luhn and no 9/12-digit boundary).
        List<Map<String, Object>> second = awaitAlertsFor(
                "SELECT a.* FROM message_alerts a JOIN messages m ON a.message_id = m.id "
                        + "WHERE m.content LIKE 'my random id%'", 0, Duration.ofSeconds(2));
        assertThat(second).isEmpty();
    }

    @Test
    void severalRulesOfOneCategoryMergeIntoOneAlert() throws java.sql.SQLException {
        // "mã otp" and "mật khẩu" are different rules of the same category.
        send(sender, "gửi mã otp và mật khẩu cho anh");

        List<Map<String, Object>> alerts = awaitAlerts(1);
        assertThat(alerts).hasSize(1);
        Object ruleIds = alerts.get(0).get("matched_rule_ids");
        int matched = ruleIds instanceof java.sql.Array array
                ? ((Object[]) array.getArray()).length : -1;
        assertThat(matched).as("both CREDENTIAL_SHARING rules merged into one alert")
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void e2eeAndSystemAndDeletedMessagesAreNeverQueued() {
        // The listener is the hard privacy gate: drive it directly so the
        // invariant does not depend on fixture plumbing.
        MessageResponse encrypted = response(UUID.randomUUID(), sender.getId(),
                "ciphertext blob", "TEXT", "E2EE_V1");
        MessageResponse system = response(UUID.randomUUID(), null,
                "send me the code", "SYSTEM", null);
        MessageResponse deleted = response(UUID.randomUUID(), sender.getId(),
                "send me the code", "TEXT", null, Instant.now());
        int depthBefore = scanQueue.depth();

        scanListener.onMessageCreated(
                new com.nexachat.message.MessageCreatedEvent(conversationId, encrypted));
        scanListener.onMessageCreated(
                new com.nexachat.message.MessageCreatedEvent(conversationId, system));
        scanListener.onMessageCreated(
                new com.nexachat.message.MessageCreatedEvent(conversationId, deleted));

        assertThat(scanQueue.depth()).isEqualTo(depthBefore);
    }

    @Test
    void optedOutSenderProducesNoAlert() {
        optOutService.optOut(conversationId, sender.getId());
        send(sender, "send me the code 482913 please");

        List<Map<String, Object>> alerts = awaitAlertsFor(
                "SELECT * FROM message_alerts WHERE sender_id = '" + sender.getId() + "'",
                0, Duration.ofSeconds(2));
        assertThat(alerts).isEmpty();
    }

    @Test
    void postHocOptOutTombstonesTheStoredSnippet() {
        send(sender, "send me the code 482913 please");
        awaitAlerts(1);

        optOutService.optOut(conversationId, sender.getId());

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT snippet, snippet_tombstoned FROM message_alerts WHERE sender_id = ?",
                sender.getId());
        assertThat(row.get("snippet")).isNull();
        assertThat(row.get("snippet_tombstoned")).isEqualTo(true);
    }

    @Test
    void messageDeletionTombstonesTheSnippet() {
        send(sender, "send me the code 482913 please");
        List<Map<String, Object>> alerts = awaitAlerts(1);
        UUID messageId = (UUID) alerts.get(0).get("message_id");

        scanListener.onMessageDeleted(new com.nexachat.message.MessageDeletedEvent(
                conversationId, new com.nexachat.message.dto.MessageDeletedPayload(
                        messageId, Instant.now())));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT snippet, snippet_tombstoned FROM message_alerts WHERE message_id = ?",
                messageId);
        assertThat(row.get("snippet")).isNull();
        assertThat(row.get("snippet_tombstoned")).isEqualTo(true);
    }

    @Test
    void rescanOfTheSameMessageCannotDuplicateTheAlert() {
        AlertScanQueue.ScanTask task = new AlertScanQueue.ScanTask(
                UUID.randomUUID(), conversationId, sender.getId(), nextSeq++,
                "send me the code", Instant.now(), false);
        // The message row must exist for the FK — insert a real one first.
        UUID messageId = jdbcTemplate.queryForObject("""
                INSERT INTO messages (id, conversation_id, sender_id, client_message_id,
                                      type, content, created_at)
                VALUES (?, ?, ?, ?, 'TEXT', ?, now()) RETURNING id
                """, UUID.class, task.messageId(), conversationId, sender.getId(),
                UUID.randomUUID(), task.content());

        scanService.process(task);
        scanService.process(task);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM message_alerts WHERE message_id = ?", Integer.class, messageId);
        assertThat(count).isEqualTo(1);
    }

    // ------------------------------------------------------------- workflow

    @Test
    void alertWorkflowUpdatesStatusNotesAndAudits() throws Exception {
        send(sender, "send me the code 482913 please");
        UUID alertId = (UUID) awaitAlerts(1).get(0).get("id");
        String token = token(admin);

        mockMvc.perform(get("/api/v1/admin/alerts")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(alertId.toString()))
                .andExpect(jsonPath("$.items[0].category").value("CREDENTIAL_SHARING"));

        mockMvc.perform(patch("/api/v1/admin/alerts/" + alertId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("status", "ACKNOWLEDGED", "assigneeId",
                                        admin.getId().toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alert.status").value("ACKNOWLEDGED"));

        mockMvc.perform(post("/api/v1/admin/alerts/" + alertId + "/notes")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("body", "investigating"))))
                .andExpect(status().isCreated());

        mockMvc.perform(patch("/api/v1/admin/alerts/" + alertId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "RESOLVED"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alert.status").value("RESOLVED"));

        Integer auditRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM admin_audit_log WHERE action = 'ALERT_UPDATE' AND target_id = ?",
                Integer.class, alertId.toString());
        assertThat(auditRows).isGreaterThanOrEqualTo(2);
    }

    @Test
    void revealReturnsContextIsAuditedAndRateLimited() throws Exception {
        send(sender, "nothing sensitive here");
        send(sender, "send me the code 482913 please");
        UUID alertId = (UUID) awaitAlerts(1).get(0).get("id");
        String token = token(admin);

        var reveal = mockMvc.perform(post("/api/v1/admin/alerts/" + alertId + "/reveal")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("reason", "case 1"))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode messages = objectMapper.readTree(reveal.getResponse().getContentAsString())
                .path("messages");
        assertThat(messages.size()).isGreaterThanOrEqualTo(1);
        assertThat(messages.toString()).contains("send me the code");

        Integer auditRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM admin_audit_log WHERE action = 'ALERT_REVEAL' AND target_id = ?",
                Integer.class, alertId.toString());
        assertThat(auditRows).isEqualTo(1);

        // The configured per-minute cap is 3: two more succeeds, the next is refused.
        for (int i = 2; i <= 3; i++) {
            mockMvc.perform(post("/api/v1/admin/alerts/" + alertId + "/reveal")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("reason", "case " + i))))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(post("/api/v1/admin/alerts/" + alertId + "/reveal")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("reason", "over limit"))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
    }

    @Test
    void revealRefusesAnE2eeConversation() throws Exception {
        // e2ee_enabled is DIRECT-only per chk_conversations_e2ee_direct.
        Conversation direct = conversationRepository.save(Conversation.direct(admin,
                directKey(sender.getId(), admin.getId())));
        for (UUID member : List.of(admin.getId(), sender.getId())) {
            jdbcTemplate.update("""
                    INSERT INTO conversation_members (conversation_id, user_id, last_read_seq, joined_at)
                    VALUES (?, ?, 0, now() - interval '30 days')
                    """, direct.getId(), member);
        }
        messageService.send(sender.getId(), direct.getId(), new SendMessageRequest(
                UUID.randomUUID(), "TEXT", "send me the code 482913 please", null, List.of()));
        UUID alertId = (UUID) awaitAlertsFor(
                "SELECT * FROM message_alerts WHERE conversation_id = '" + direct.getId() + "'",
                1, ALERT_WAIT).get(0).get("id");
        jdbcTemplate.update("UPDATE conversations SET e2ee_enabled = TRUE WHERE id = ?",
                direct.getId());

        mockMvc.perform(post("/api/v1/admin/alerts/" + alertId + "/reveal")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("reason", "e2ee probe"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void revealOmitsContextFromMembersWhoOptedOut() throws Exception {
        send(peer, "private opted out neighbour line");
        send(sender, "send me the code 482913 please");
        UUID alertId = (UUID) awaitAlerts(1).get(0).get("id");
        optOutService.optOut(conversationId, peer.getId());

        var reveal = mockMvc.perform(post("/api/v1/admin/alerts/" + alertId + "/reveal")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("reason", "context probe"))))
                .andExpect(status().isOk())
                .andReturn();
        String body = reveal.getResponse().getContentAsString();
        assertThat(body).contains("send me the code");
        assertThat(body).doesNotContain("private opted out neighbour line");
    }

    @Test
    void revealRefusesAnOptedOutSender() throws Exception {
        send(sender, "send me the code 482913 please");
        UUID alertId = (UUID) awaitAlerts(1).get(0).get("id");
        optOutService.optOut(conversationId, sender.getId());

        mockMvc.perform(post("/api/v1/admin/alerts/" + alertId + "/reveal")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("reason", "opt-out probe"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void probingUnknownAlertIdsDoesNotSpendTheRevealBudget() throws Exception {
        send(sender, "send me the code 482913 please");
        UUID alertId = (UUID) awaitAlerts(1).get(0).get("id");
        String token = token(admin);

        // The configured per-minute budget is 3: unknown ids must not touch
        // it, or probing alone would lock the actor out of real reveals.
        for (int i = 0; i < 4; i++) {
            mockMvc.perform(post("/api/v1/admin/alerts/" + UUID.randomUUID() + "/reveal")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("reason", "probe"))))
                    .andExpect(status().isNotFound());
        }
        mockMvc.perform(post("/api/v1/admin/alerts/" + alertId + "/reveal")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("reason", "real case"))))
                .andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- rules

    @Test
    void customRuleCreateToggleAndDeleteFlow() throws Exception {
        String token = token(admin);

        var created = mockMvc.perform(post("/api/v1/admin/rules")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "probe custom", "category", "CUSTOM",
                                "severity", "LOW", "patternType", "KEYWORD",
                                "pattern", "zebra unicorn"))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID ruleId = UUID.fromString(objectMapper
                .readTree(created.getResponse().getContentAsString()).path("id").asText());

        send(sender, "a zebra unicorn appeared");
        awaitAlerts(1);

        mockMvc.perform(patch("/api/v1/admin/rules/" + ruleId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("enabled", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/admin/rules/" + ruleId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNoContent());
    }

    @Test
    void redosShapedAndInvalidPatternsAreRejectedAtWriteTime() throws Exception {
        String token = token(admin);
        for (String bad : List.of("(a+)+", "(x*)*y", "(\\w+)\\1")) {
            mockMvc.perform(post("/api/v1/admin/rules")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "name", "bad", "category", "CUSTOM", "severity", "LOW",
                                    "patternType", "REGEX", "pattern", bad))))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(post("/api/v1/admin/rules")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "bad", "category", "CUSTOM", "severity", "LOW",
                                "patternType", "REGEX", "pattern", "([unclosed"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ruleTestEndpointPreviewsMatchesWithoutWriting() throws Exception {
        mockMvc.perform(post("/api/v1/admin/rules/test")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "patternType", "KEYWORD", "pattern", "send me the code",
                                "sampleText", "please send me the code 482913"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matched").value(true))
                .andExpect(jsonPath("$.matches[0].snippet").value(
                        org.hamcrest.Matchers.containsString("[code]")));
    }

    // ---------------------------------------------------------------- audit

    @Test
    void auditLogRejectsUpdateAndDelete() {
        adminUserService.suspend(sender.getId(), "immutability probe", admin.getId(),
                User.Role.ADMIN, "127.0.0.1");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE admin_audit_log SET reason = 'tampered' WHERE action = 'USER_SUSPEND'"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM admin_audit_log WHERE action = 'USER_SUSPEND'"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void auditSurvivesActorDeletion() {
        // A conversation-less actor: deleting the real fixture admin would trip
        // conversations.created_by before the audit-log assertion is reached.
        User actor = createUser("Doomed Actor");
        adminUserService.suspend(sender.getId(), "deletion probe", actor.getId(),
                User.Role.ADMIN, "127.0.0.1");
        jdbcTemplate.update("DELETE FROM user_notices WHERE user_id = ?", actor.getId());
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", actor.getId());

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM admin_audit_log WHERE actor_id = ?",
                Integer.class, actor.getId());
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void bulkDismissWritesOneAuditRowWithTheJoinedIds() throws Exception {
        send(sender, "send me the code 482913 please");
        send(sender, "wire transfer the money now");
        List<String> ids = awaitAlerts(2).stream()
                .map(alert -> alert.get("id").toString()).toList();

        mockMvc.perform(post("/api/v1/admin/alerts/bulk-dismiss")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("ids", ids, "reason", "sweep probe"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dismissed").value(2));

        Map<String, Object> audit = jdbcTemplate.queryForMap(
                "SELECT target_id FROM admin_audit_log WHERE action = 'ALERT_BULK_DISMISS'");
        assertThat((String) audit.get("target_id")).contains(ids.get(0), ids.get(1));
    }

    @Test
    void aFailedAuditWriteRollsTheDismissBack() {
        send(sender, "send me the code 482913 please");
        send(sender, "wire transfer the money now");
        List<UUID> ids = awaitAlerts(2).stream()
                .map(alert -> (UUID) alert.get("id")).toList();

        // actor_id is NOT NULL in admin_audit_log: a null actor makes the
        // audit insert fail, and the dismissal must roll back with it.
        assertThatThrownBy(() -> alertService.bulkDismiss(ids, "rollback probe", null,
                User.Role.ADMIN, "127.0.0.1"))
                .isInstanceOf(DataAccessException.class);

        Integer stillOpen = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM message_alerts WHERE status = 'OPEN' AND id IN (?, ?)",
                Integer.class, ids.get(0), ids.get(1));
        assertThat(stillOpen).isEqualTo(2);
        Integer auditRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM admin_audit_log WHERE action = 'ALERT_BULK_DISMISS'",
                Integer.class);
        assertThat(auditRows).isZero();
    }

    // ------------------------------------------------------------- service

    @Test
    void lastAdminGuardFiresOnDemoteAndSuspend() {
        User soleAdmin = createUser("Sole Admin");
        jdbcTemplate.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", soleAdmin.getId());
        // Every other admin — the fixture's plus leftovers from earlier runs on
        // this shared DB — is demoted so soleAdmin is genuinely the last one.
        jdbcTemplate.update("UPDATE users SET role = 'USER' WHERE role = 'ADMIN' AND id <> ?",
                soleAdmin.getId());
        UUID fabricatedActor = UUID.randomUUID();

        assertThatThrownBy(() -> adminUserService.changeRole(soleAdmin.getId(), User.Role.USER,
                "probe", fabricatedActor, User.Role.ADMIN, "127.0.0.1"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(com.nexachat.common.error.ApiErrorCode.LAST_ADMIN);
        assertThatThrownBy(() -> adminUserService.suspend(soleAdmin.getId(),
                "probe", fabricatedActor, User.Role.ADMIN, "127.0.0.1"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(com.nexachat.common.error.ApiErrorCode.LAST_ADMIN);
        // The denied attempts still landed in the audit log (REQUIRES_NEW).
        Integer denied = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM admin_audit_log WHERE detail::text LIKE '%last-admin%'",
                Integer.class);
        assertThat(denied).isGreaterThanOrEqualTo(2);
    }

    // ------------------------------------------------------------- fixtures

    @Test
    void reopeningAResolvedAlertClearsTheStaleResolutionEvidence() throws Exception {
        send(sender, "send me the code 482913 please");
        UUID alertId = (UUID) awaitAlerts(1).get(0).get("id");

        mockMvc.perform(patch("/api/v1/admin/alerts/" + alertId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "RESOLVED"))))
                .andExpect(status().isOk());
        Map<String, Object> resolved = jdbcTemplate.queryForMap(
                "SELECT resolved_by, resolved_at FROM message_alerts WHERE id = ?", alertId);
        assertThat(resolved.get("resolved_by")).isEqualTo(admin.getId());

        mockMvc.perform(patch("/api/v1/admin/alerts/" + alertId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "OPEN"))))
                .andExpect(status().isOk());

        Map<String, Object> reopened = jdbcTemplate.queryForMap(
                "SELECT status, resolved_by, resolved_at FROM message_alerts WHERE id = ?", alertId);
        assertThat(reopened.get("status")).isEqualTo("OPEN");
        assertThat(reopened.get("resolved_by")).isNull();
        assertThat(reopened.get("resolved_at")).isNull();
    }

    @Test
    void warnUserRejectsAnAlertRaisedOnAnotherUsersMessage() {
        send(sender, "send me the code 482913 please");
        UUID alertId = (UUID) awaitAlerts(1).get(0).get("id");

        // The peer did not send the flagged message: citing this alert against
        // them would mislink the warning's evidence.
        assertThatThrownBy(() -> adminUserService.warnUser(peer.getId(), alertId,
                "wrong target probe", admin.getId(), User.Role.ADMIN, "127.0.0.1"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(com.nexachat.common.error.ApiErrorCode.VALIDATION_FAILED);

        adminUserService.warnUser(sender.getId(), alertId,
                "right target probe", admin.getId(), User.Role.ADMIN, "127.0.0.1");
        Integer notices = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM user_notices WHERE user_id = ? AND alert_id = ?",
                Integer.class, sender.getId(), alertId);
        assertThat(notices).isEqualTo(1);
    }

    @Test
    void deletingABuiltInRuleDisablesItInsteadOfRefusing() throws Exception {
        UUID builtInId = jdbcTemplate.queryForObject(
                "SELECT id FROM alert_rules WHERE built_in = TRUE ORDER BY id LIMIT 1", UUID.class);
        Boolean wasEnabled = jdbcTemplate.queryForObject(
                "SELECT enabled FROM alert_rules WHERE id = ?", Boolean.class, builtInId);
        try {
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .delete("/api/v1/admin/rules/" + builtInId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(admin)))
                    .andExpect(status().isNoContent());

            Boolean enabled = jdbcTemplate.queryForObject(
                    "SELECT enabled FROM alert_rules WHERE id = ?", Boolean.class, builtInId);
            assertThat(enabled)
                    .as("DELETE on a built-in rule disables it; the row must survive")
                    .isFalse();
            Integer rows = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM alert_rules WHERE id = ?", Integer.class, builtInId);
            assertThat(rows).isEqualTo(1);
        } finally {
            jdbcTemplate.update("UPDATE alert_rules SET enabled = ? WHERE id = ?",
                    wasEnabled, builtInId);
        }
    }

    private User createUser(String displayName) {
        String phone = ItPhoneRanges.nextPhone(AlertPipelineIT.class);
        return userRepository.save(User.create(phone, "x".repeat(60), displayName));
    }

    /** Same LEAST:GREATEST pair-key the service uses, inlined (it's package-private). */
    private static String directKey(UUID a, UUID b) {
        return a.compareTo(b) <= 0 ? a + ":" + b : b + ":" + a;
    }

    private void addMember(UUID userId) {
        jdbcTemplate.update("""
                INSERT INTO conversation_members (conversation_id, user_id, last_read_seq, joined_at)
                VALUES (?, ?, 0, now() - interval '30 days')
                """, conversationId, userId);
    }

    private void send(User author, String content) {
        messageService.send(author.getId(), conversationId, new SendMessageRequest(
                UUID.randomUUID(), "TEXT", content, null, List.of()));
    }

    private String token(User user) {
        return jwtService.generateAccessToken(user);
    }

    private List<Map<String, Object>> awaitAlerts(int expected) {
        return awaitAlertsFor(
                "SELECT * FROM message_alerts WHERE conversation_id = '" + conversationId + "'",
                expected, ALERT_WAIT);
    }

    /**
     * Polls until at least {@code expected} rows match. With {@code expected=0}
     * it sleeps out the timeout so "no alert was produced" claims actually
     * give the drain its window before asserting.
     */
    private List<Map<String, Object>> awaitAlertsFor(String sql, int expected, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        List<Map<String, Object>> rows = List.of();
        while (Instant.now().isBefore(deadline)) {
            rows = jdbcTemplate.queryForList(sql);
            if (expected > 0 && rows.size() >= expected) {
                return rows;
            }
            if (expected == 0 && !rows.isEmpty()) {
                return rows;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return rows;
            }
        }
        return rows;
    }

    private MessageResponse response(UUID messageId, UUID authorId, String content, String type,
                                     String encryption) {
        return response(messageId, authorId, content, type, encryption, null);
    }

    private MessageResponse response(UUID messageId, UUID authorId, String content, String type,
                                     String encryption, Instant deletedAt) {
        return new MessageResponse(messageId, conversationId, nextSeq++, authorId, "probe",
                type, content, UUID.randomUUID(), null, null, List.of(), List.of(),
                Instant.now(), null, deletedAt, List.of(), false, null, null,
                encryption, null);
    }
}
