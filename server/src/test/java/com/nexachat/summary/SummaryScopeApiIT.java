package com.nexachat.summary;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.auth.JwtService;
import com.nexachat.conversation.Conversation;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.conversation.ConversationRepository;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Scope selection end to end: the local day follows the caller's zone (Vietnam by default),
 * UNREAD and LAST_N ignore the zone, participation and privacy filters still apply, a short scope
 * is reported rather than widened, and cached summaries are keyed by scope, day and zone.
 */
@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("it")
class SummaryScopeApiIT {

    // 2026-11-17 19:00 UTC is 02:00 on the 18th in Vietnam but still the 17th in UTC.
    private static final Instant NOW = Instant.parse("2026-11-17T19:00:00Z");
    private static final Instant VN_DAY_START = Instant.parse("2026-11-17T17:00:00Z");
    private static final Instant VN_NEXT_DAY_START = Instant.parse("2026-11-18T17:00:00Z");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> env("IT_DB_URL",
                "jdbc:postgresql://localhost:5433/nexachat"));
        registry.add("spring.datasource.username", () -> env("IT_DB_USER", "nexachat"));
        registry.add("spring.datasource.password", () -> env("IT_DB_PASSWORD", "nexachat"));
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
    private ConversationMemberRepository memberRepository;
    @Autowired
    private SummaryConsentRepository consentRepository;
    @Autowired
    private SummaryWindowRepository windowRepository;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private SummaryRateLimiter rateLimiter;
    @Autowired
    private SummaryProviderSelector providerSelector;
    @Autowired
    private SummaryCacheRepository cacheRepository;
    @Autowired
    private ConversationAiOptOuts aiOptOuts;

    private User caller;
    private User peer;
    private UUID conversationId;

    @BeforeEach
    void createFixture() {
        caller = createUser("Scope caller");
        peer = createUser("Scope peer");
        Conversation conversation = conversationRepository.save(Conversation.group(caller, "Scope fixture"));
        conversationId = conversation.getId();
        addMember(caller.getId(), Instant.parse("2020-01-01T00:00:00Z"));
        addMember(peer.getId(), Instant.parse("2020-01-01T00:00:00Z"));
    }

    @AfterEach
    void removeFixture() {
        if (conversationId != null) {
            jdbcTemplate.update("DELETE FROM conversations WHERE id = ?", conversationId);
        }
        for (User user : List.of(caller, peer)) {
            if (user != null) {
                jdbcTemplate.update("DELETE FROM users WHERE id = ?", user.getId());
            }
        }
    }

    @Test
    void todayDefaultsToTheVietnamDayAndKeepsItsExactBoundaries() {
        TestMessage justBefore = addMessage(caller.getId(), "Before VN day", VN_DAY_START.minus(1, ChronoUnit.MICROS));
        TestMessage atStart = addMessage(peer.getId(), "At VN midnight", VN_DAY_START);
        TestMessage lastMicro = addMessage(caller.getId(), "Last micro", VN_NEXT_DAY_START.minus(1, ChronoUnit.MICROS));
        TestMessage nextDay = addMessage(peer.getId(), "Next VN midnight", VN_NEXT_DAY_START);

        SummaryReadContext context = windowAt(NOW, new SummaryRequest(SummaryScope.TODAY, null, null, null));

        assertThat(ids(context)).containsExactly(atStart.id(), lastMicro.id());
        assertThat(ids(context)).doesNotContain(justBefore.id(), nextDay.id());
        assertThat(context.period().timeZone()).isEqualTo("Asia/Ho_Chi_Minh");
        assertThat(context.period().timeZoneDefaulted()).isTrue();
        assertThat(context.period().date()).hasToString("2026-11-18");
    }

    @Test
    void aZoneWhoseTodayDiffersFromUtcSelectsADifferentSetOfMessages() {
        TestMessage utcOnly = addMessage(caller.getId(), "Daytime UTC", Instant.parse("2026-11-17T10:00:00Z"));
        TestMessage vnMidnight = addMessage(peer.getId(), "VN midnight", VN_DAY_START);
        TestMessage vnEvening = addMessage(caller.getId(), "VN early morning", Instant.parse("2026-11-17T18:00:00Z"));

        SummaryReadContext vietnam = windowAt(NOW, new SummaryRequest(SummaryScope.TODAY, null, null, null));
        SummaryReadContext utc = windowAt(NOW, new SummaryRequest(SummaryScope.TODAY, null, null, null, "UTC"));
        SummaryReadContext tokyo = windowAt(NOW,
                new SummaryRequest(SummaryScope.TODAY, null, null, null, "Asia/Tokyo"));

        assertThat(ids(vietnam)).containsExactly(vnMidnight.id(), vnEvening.id());
        assertThat(ids(utc)).containsExactly(utcOnly.id(), vnMidnight.id(), vnEvening.id());
        // Tokyo is already on the 18th, starting 15:00 UTC on the 17th.
        assertThat(ids(tokyo)).containsExactly(vnMidnight.id(), vnEvening.id());
        assertThat(utc.period().timeZoneDefaulted()).isFalse();
    }

    @Test
    void unreadAndLastNMeaningDoesNotDependOnTheTimeZone() {
        List<TestMessage> messages = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            messages.add(addMessage(index % 2 == 0 ? caller.getId() : peer.getId(), "Message " + index,
                    Instant.parse("2026-11-10T00:00:00Z").plus(index, ChronoUnit.HOURS)));
        }
        jdbcTemplate.update("UPDATE conversation_members SET last_read_seq = ? WHERE conversation_id = ? "
                + "AND user_id = ?", messages.get(2).seq(), conversationId, caller.getId());

        for (String zone : new String[] {null, "UTC", "Pacific/Kiritimati", "America/Los_Angeles"}) {
            SummaryReadContext unread = windowAt(NOW, new SummaryRequest(SummaryScope.UNREAD, null, null, null, zone));
            SummaryReadContext lastFive = windowAt(NOW, new SummaryRequest(SummaryScope.LAST_N, 5, null, null, zone));

            assertThat(ids(unread)).containsExactlyElementsOf(
                    messages.subList(3, 8).stream().map(TestMessage::id).toList());
            assertThat(ids(lastFive)).containsExactlyElementsOf(
                    messages.subList(3, 8).stream().map(TestMessage::id).toList());
            assertThat(unread.period()).isNull();
            assertThat(lastFive.period()).isNull();
        }
    }

    @Test
    void todayOnlyReadsWhatTheCallerWasAllowedToSeeSinceTheyJoined() {
        Instant joined = Instant.parse("2026-11-17T20:00:00Z");
        jdbcTemplate.update("UPDATE conversation_members SET joined_at = ? WHERE conversation_id = ? AND user_id = ?",
                OffsetDateTime.ofInstant(joined, ZoneOffset.UTC), conversationId, caller.getId());
        addMessage(peer.getId(), "Before the caller joined", Instant.parse("2026-11-17T18:00:00Z"));
        TestMessage afterJoin = addMessage(peer.getId(), "After the caller joined", Instant.parse("2026-11-17T21:00:00Z"));
        TestMessage deleted = addMessage(peer.getId(), "Recalled", Instant.parse("2026-11-17T21:30:00Z"));
        jdbcTemplate.update("UPDATE messages SET deleted_at = now() WHERE id = ?", deleted.id());
        TestMessage expired = addMessage(peer.getId(), "Disappeared", Instant.parse("2026-11-17T21:45:00Z"));
        jdbcTemplate.update("UPDATE messages SET expires_at = now() - interval '1 minute' WHERE id = ?", expired.id());

        SummaryReadContext context = windowAt(NOW, new SummaryRequest(SummaryScope.TODAY, null, null, null));

        assertThat(ids(context)).containsExactly(afterJoin.id());
    }

    @Test
    void notEnoughMessagesInTheChosenDayIsReportedAndNeverFilledFromYesterday() throws Exception {
        Instant now = Instant.now();
        Instant startOfToday = SummaryPeriods.today(now, null).start();
        for (int index = 0; index < 6; index++) {
            addMessage(peer.getId(), "Yesterday " + index, startOfToday.minus(1 + index, ChronoUnit.HOURS));
        }
        for (int index = 0; index < 3; index++) {
            addMessage(peer.getId(), "Today " + index, startOfToday.plus(1 + index, ChronoUnit.SECONDS));
        }

        postSummary(caller, body("TODAY", null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("TOO_FEW_MESSAGES"))
                .andExpect(jsonPath("$.error.details.scope").value("TODAY"))
                .andExpect(jsonPath("$.error.details.available").value(3))
                .andExpect(jsonPath("$.error.details.required").value(5))
                .andExpect(jsonPath("$.error.details.period.timeZone").value("Asia/Ho_Chi_Minh"));
    }

    @Test
    void invalidTimeZoneIsRejectedWithItsOwnCode() throws Exception {
        for (String zone : new String[] {"Mars/Phobos", "../../etc/passwd", "UTC+99", "x".repeat(80)}) {
            postSummary(caller, body("TODAY", null, zone))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_TIME_ZONE"));
        }
    }

    @Test
    void responseNamesScopeProcessorPeriodAndOnlyCitesMessagesFromTheSourceSet() throws Exception {
        Instant now = Instant.now();
        Instant startOfToday = SummaryPeriods.today(now, "Asia/Ho_Chi_Minh").start();
        // Older context that must stay out of the Today set and out of every citation.
        Set<String> older = new HashSet<>();
        for (int index = 0; index < 5; index++) {
            older.add(addMessage(peer.getId(), "Decision yesterday " + index,
                    startOfToday.minus(2 + index, ChronoUnit.HOURS)).id().toString());
        }
        Set<String> today = new HashSet<>();
        String[] samples = {
                "The team decided to ship the summary picker.",
                "Please review the API contract by Friday.",
                "I will write the regression tests today.",
                "Can we merge after the tests pass?",
                "The next action is to record the demo."
        };
        for (int index = 0; index < samples.length; index++) {
            today.add(addMessage(index % 2 == 0 ? caller.getId() : peer.getId(), samples[index],
                    startOfToday.plus(1 + index, ChronoUnit.SECONDS)).id().toString());
        }

        MvcResult result = postSummary(caller, body("TODAY", null, "Asia/Ho_Chi_Minh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("TODAY"))
                .andExpect(jsonPath("$.limit").doesNotExist())
                .andExpect(jsonPath("$.generatedBy").value("LOCAL"))
                .andExpect(jsonPath("$.messageCount").value(5))
                .andExpect(jsonPath("$.period.timeZone").value("Asia/Ho_Chi_Minh"))
                .andExpect(jsonPath("$.period.timeZoneDefaulted").value(false))
                .andReturn();
        JsonNode summary = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(summary.path("period").path("date").asText())
                .isEqualTo(SummaryPeriods.today(now, "Asia/Ho_Chi_Minh").date().toString());
        for (String section : List.of("keyPoints", "decisions", "actionItems", "questions")) {
            for (JsonNode item : summary.path(section)) {
                for (JsonNode messageId : item.path("messageIds")) {
                    assertThat(today).contains(messageId.asText());
                    assertThat(older).doesNotContain(messageId.asText());
                }
            }
        }

        postSummary(caller, body("LAST_N", 20, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("LAST_N"))
                .andExpect(jsonPath("$.limit").value(20))
                .andExpect(jsonPath("$.period").doesNotExist());
    }

    @Test
    void cacheRowsAreKeyedByScopeAndZoneAndAccessLossStopsReplay() throws Exception {
        Instant now = Instant.now();
        SummaryPeriod vietnam = SummaryPeriods.today(now, "Asia/Ho_Chi_Minh");
        SummaryPeriod tokyo = SummaryPeriods.today(now, "Asia/Tokyo");
        // The two local days overlap for 22 hours. Messages inside the overlap belong to both
        // windows, so only the zone in the cache key can tell the two results apart.
        Instant shared = vietnam.start().isAfter(tokyo.start()) ? vietnam.start() : tokyo.start();
        for (int index = 0; index < 6; index++) {
            addMessage(index % 2 == 0 ? caller.getId() : peer.getId(), "Plan item number " + index + " is ready.",
                    shared.plus(10 + index, ChronoUnit.SECONDS));
        }

        postSummary(caller, body("TODAY", null, "Asia/Ho_Chi_Minh"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cached").value(false));
        postSummary(caller, body("TODAY", null, "Asia/Ho_Chi_Minh"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cached").value(true));
        // Same messages, different zone: a different key, so the old row is not served.
        postSummary(caller, body("TODAY", null, "Asia/Tokyo"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cached").value(false));
        postSummary(caller, body("LAST_N", 20, null))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cached").value(false));
        postSummary(caller, body("LAST_N", 50, null))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cached").value(false));

        // Access lost: the caller leaves; nothing cached is replayed to them.
        jdbcTemplate.update("DELETE FROM conversation_members WHERE conversation_id = ? AND user_id = ?",
                conversationId, caller.getId());
        postSummary(caller, body("TODAY", null, "Asia/Ho_Chi_Minh"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_MEMBER"));
        // The remaining member still gets their own result.
        postSummary(peer, body("TODAY", null, "Asia/Ho_Chi_Minh")).andExpect(status().isOk());
    }

    @Test
    void rejoiningWithALaterAdmissionExcludesWhatWasSentWhileAway() throws Exception {
        Instant startOfToday = SummaryPeriods.today(Instant.now(), "Asia/Ho_Chi_Minh").start();
        for (int index = 0; index < 6; index++) {
            addMessage(peer.getId(), "Sent while away " + index, startOfToday.plus(5 + index, ChronoUnit.SECONDS));
        }
        postSummary(caller, body("TODAY", null, null)).andExpect(status().isOk());

        jdbcTemplate.update("DELETE FROM conversation_members WHERE conversation_id = ? AND user_id = ?",
                conversationId, caller.getId());
        addMember(caller.getId(), Instant.now());

        postSummary(caller, body("TODAY", null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("TOO_FEW_MESSAGES"))
                .andExpect(jsonPath("$.error.details.available").value(0));
    }

    @Test
    void aTodaySummaryIsCachedPerDayAndNotReplayedOnTheFollowingDay() {
        // 19:00 UTC is 02:00 on the following Vietnam day: both dial positions sit
        // safely inside their own local day.
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-17T19:00:00Z"));
        Clock mutableClock = new Clock() {
            @Override
            public Instant instant() {
                return now.get();
            }

            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }
        };
        SummaryWindowService clockWindowService = new SummaryWindowService(
                conversationRepository, memberRepository, consentRepository, windowRepository, mutableClock);
        SummaryService service = new SummaryService(
                rateLimiter, clockWindowService, providerSelector, cacheRepository, aiOptOuts);

        SummaryPeriod firstDay = SummaryPeriods.today(now.get(), "Asia/Ho_Chi_Minh");
        for (int index = 0; index < 5; index++) {
            addMessage(caller.getId(), "Day one plan number " + index + " is settled.",
                    firstDay.start().plusSeconds(60 + index));
        }
        SummaryRequest firstRequest = new SummaryRequest(SummaryScope.TODAY, null,
                SummaryLanguage.EN, false, "Asia/Ho_Chi_Minh");
        SummaryResponse first = service.summarize(conversationId, caller.getId(), firstRequest);
        assertThat(first.cached()).isFalse();
        assertThat(first.period().date()).isEqualTo(LocalDate.parse("2026-11-18"));
        SummaryResponse firstRepeat = service.summarize(conversationId, caller.getId(), firstRequest);
        assertThat(firstRepeat.cached()).isTrue();
        assertThat(firstRepeat.summaryId()).isEqualTo(first.summaryId());

        now.set(Instant.parse("2026-11-18T19:00:00Z"));
        SummaryPeriod secondDay = SummaryPeriods.today(now.get(), "Asia/Ho_Chi_Minh");
        for (int index = 0; index < 5; index++) {
            addMessage(peer.getId(), "Day two plan number " + index + " is agreed.",
                    secondDay.start().plusSeconds(60 + index));
        }
        SummaryRequest nextDayRequest = new SummaryRequest(SummaryScope.TODAY, null,
                SummaryLanguage.EN, false, "Asia/Ho_Chi_Minh");
        SummaryResponse nextDay = service.summarize(conversationId, caller.getId(), nextDayRequest);
        assertThat(nextDay.cached()).isFalse();
        assertThat(nextDay.period().date()).isEqualTo(LocalDate.parse("2026-11-19"));
        assertThat(nextDay.summaryId()).isNotEqualTo(first.summaryId());
        SummaryResponse nextDayRepeat = service.summarize(conversationId, caller.getId(), nextDayRequest);
        assertThat(nextDayRepeat.cached()).isTrue();
    }

    private SummaryReadContext windowAt(Instant now, SummaryRequest request) {
        SummaryWindowService service = new SummaryWindowService(
                conversationRepository, memberRepository, consentRepository, windowRepository,
                Clock.fixed(now, ZoneOffset.UTC));
        return service.load(conversationId, caller.getId(), request);
    }

    private static List<UUID> ids(SummaryReadContext context) {
        return context.window().messages().stream().map(SummaryMessage::id).toList();
    }

    private User createUser(String displayName) {
        String phone = ItPhoneRanges.nextPhone(SummaryScopeApiIT.class);
        return userRepository.save(User.create(phone, "x".repeat(60), displayName));
    }

    private void addMember(UUID userId, Instant joinedAt) {
        jdbcTemplate.update("INSERT INTO conversation_members (conversation_id, user_id, last_read_seq, joined_at) "
                        + "VALUES (?, ?, 0, ?)",
                conversationId, userId, OffsetDateTime.ofInstant(joinedAt, ZoneOffset.UTC));
    }

    private TestMessage addMessage(UUID senderId, String content, Instant createdAt) {
        UUID messageId = UUID.randomUUID();
        Long sequence = jdbcTemplate.queryForObject("""
                INSERT INTO messages
                    (id, conversation_id, sender_id, client_message_id, type, content, created_at)
                VALUES (?, ?, ?, ?, 'TEXT', CAST(? AS text), ?)
                RETURNING seq
                """, Long.class, messageId, conversationId, senderId, UUID.randomUUID(), content,
                OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC));
        return new TestMessage(messageId, sequence);
    }

    private ResultActions postSummary(User user, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/summary")
                .header("Authorization", "Bearer " + jwtService.generateAccessToken(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String body(String scope, Integer limit, String timeZone) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scope", scope);
        if (limit != null) {
            body.put("limit", limit);
        }
        if (timeZone != null) {
            body.put("timeZone", timeZone);
        }
        body.put("language", "EN");
        return objectMapper.writeValueAsString(body);
    }

    private record TestMessage(UUID id, long seq) {
    }
}
