package com.nexachat.streak;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexachat.notification.MockFcmGateway;
import com.nexachat.testutil.FeatureItSupport;
import com.nexachat.streak.StreakRules.Side;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage of chat streaks against a real PostgreSQL: the send hook
 * (both sides, replays, peer-suppressed rows, groups, E2EE, concurrent sends),
 * the check-in streak, the inbox projection, the reminder opt-in and the daily
 * reminder job.
 *
 * <p>Days are the server's streak day (UTC by default), so a test that needs a
 * chain older than today writes the row directly instead of waiting.
 */
class StreakFlowIT extends FeatureItSupport {

    @Autowired
    private StreakReminderJob reminderJob;
    @Autowired
    private MockFcmGateway fcmGateway;
    @Autowired
    private StreakProperties streakProperties;
    @Autowired
    private StreakStore streakStore;

    private static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    private JsonNode streakOf(Actor actor, UUID conversationId) throws Exception {
        return json(getAs(actor, "/api/v1/conversations/" + conversationId + "/streak")
                .andExpect(status().isOk()));
    }

    private JsonNode checkInSummary(Actor actor) throws Exception {
        return json(getAs(actor, "/api/v1/streaks/me").andExpect(status().isOk()));
    }

    @Test
    void bothSidesSendingOnOneDayStartsTheChain() throws Exception {
        Actor alice = newUser("Alice");
        Actor bob = newUser("Bob");
        UUID conversationId = openDirect(alice, bob);

        sendText(conversationId, alice, "hi").andExpect(status().isCreated());
        JsonNode afterOneSide = streakOf(alice, conversationId);
        assertThat(afterOneSide.get("count").asInt()).isZero();
        assertThat(afterOneSide.get("mySentToday").asBoolean()).isTrue();
        assertThat(afterOneSide.get("peerSentToday").asBoolean()).isFalse();

        sendText(conversationId, bob, "hey").andExpect(status().isCreated());

        JsonNode streak = streakOf(alice, conversationId);
        assertThat(streak.get("count").asInt()).isEqualTo(1);
        assertThat(streak.get("bestCount").asInt()).isEqualTo(1);
        assertThat(streak.get("day").asText()).isEqualTo(today().toString());
        assertThat(streak.get("lastDay").asText()).isEqualTo(today().toString());
        assertThat(streak.get("startedOn").asText()).isEqualTo(today().toString());
        assertThat(streak.get("atRisk").asBoolean()).isFalse();
        assertThat(streak.get("freezeAvailable").asBoolean()).isTrue();
        assertThat(streak.get("milestone").asInt()).isZero();
        assertThat(streak.get("mySentToday").asBoolean()).isTrue();
        assertThat(streak.get("peerSentToday").asBoolean()).isTrue();
        assertThat(streak.has("openedToday")).isFalse();
    }

    @Test
    void extraMessagesOnTheSameDayNeverCountTwice() throws Exception {
        Actor alice = newUser("Alice");
        Actor bob = newUser("Bob");
        UUID conversationId = openDirect(alice, bob);

        for (int i = 0; i < 3; i++) {
            sendText(conversationId, alice, "a" + i).andExpect(status().isCreated());
            sendText(conversationId, bob, "b" + i).andExpect(status().isCreated());
        }

        assertThat(streakOf(alice, conversationId).get("count").asInt()).isEqualTo(1);
    }

    @Test
    void aReplayedSendDoesNotAdvanceTheChainAgain() throws Exception {
        Actor alice = newUser("Alice");
        Actor bob = newUser("Bob");
        UUID conversationId = openDirect(alice, bob);
        UUID clientMessageId = UUID.randomUUID();

        sendText(conversationId, alice, clientMessageId, "once").andExpect(status().isCreated());
        sendText(conversationId, alice, clientMessageId, "once").andExpect(status().isOk());
        sendText(conversationId, bob, "reply").andExpect(status().isCreated());

        assertThat(streakOf(alice, conversationId).get("count").asInt()).isEqualTo(1);
        assertThat(countRows("SELECT count(*) FROM conversation_streaks WHERE conversation_id = ?",
                conversationId)).isEqualTo(1);
    }

    @Test
    void aPeerSuppressedSendNeverCounts() throws Exception {
        Actor alice = newUser("Alice");
        Actor bob = newUser("Bob");
        UUID conversationId = openDirect(alice, bob);
        postAs(bob, "/api/v1/users/" + alice.id() + "/block", null).andExpect(status().isNoContent());

        // Bob blocked Alice: her send is stored but suppressed, so it is not half of a day.
        sendText(conversationId, alice, "into the void").andExpect(status().isCreated());
        // The blocker cannot write to the blocked user either, so unblock before the reply.
        deleteAs(bob, "/api/v1/users/" + alice.id() + "/block").andExpect(status().isNoContent());
        sendText(conversationId, bob, "still here").andExpect(status().isCreated());

        JsonNode bobView = streakOf(bob, conversationId);
        assertThat(bobView.get("count").asInt()).isZero();
        assertThat(bobView.get("mySentToday").asBoolean()).isTrue();
        assertThat(bobView.get("peerSentToday").asBoolean()).isFalse();
        assertThat(streakOf(alice, conversationId).get("count").asInt()).isZero();
    }

    @Test
    void aGroupChatHasNoChain() throws Exception {
        Actor owner = newUser("Owner");
        Actor member = newUser("Member");
        UUID groupId = createGroup(owner, "Team", member);

        sendText(groupId, owner, "hello").andExpect(status().isCreated());
        sendText(groupId, member, "hi").andExpect(status().isCreated());

        JsonNode streak = streakOf(owner, groupId);
        assertThat(streak.get("count").asInt()).isZero();
        assertThat(streak.get("atRisk").asBoolean()).isFalse();
        assertThat(streak.get("freezeAvailable").asBoolean()).isFalse();
        assertThat(streak.get("mySentToday").asBoolean()).isFalse();
        assertThat(streak.get("peerSentToday").asBoolean()).isFalse();
        assertThat(countRows("SELECT count(*) FROM conversation_streaks WHERE conversation_id = ?",
                groupId)).isZero();
    }

    @Test
    void anEncryptedMessageCountsWithoutReadingItsContent() throws Exception {
        Actor alice = newUser("Alice");
        Actor bob = newUser("Bob");
        UUID aliceDevice = UUID.randomUUID();
        UUID bobDevice = UUID.randomUUID();
        putDevice(alice, aliceDevice);
        putDevice(bob, bobDevice);
        UUID conversationId = openDirect(alice, bob);
        mockMvc.perform(put("/api/v1/conversations/" + conversationId + "/e2ee")
                        .header("Authorization", bearer(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("enabled", true))))
                .andExpect(status().isOk());

        sendEncrypted(conversationId, alice, aliceDevice, bobDevice).andExpect(status().isCreated());
        sendEncrypted(conversationId, bob, aliceDevice, bobDevice).andExpect(status().isCreated());

        JsonNode streak = streakOf(alice, conversationId);
        assertThat(streak.get("count").asInt()).isEqualTo(1);
        assertThat(streak.get("mySentToday").asBoolean()).isTrue();
        assertThat(streak.get("peerSentToday").asBoolean()).isTrue();
    }

    @Test
    void twoSimultaneousSendsCompleteTheDayExactlyOnce() throws Exception {
        Actor alice = newUser("Alice");
        Actor bob = newUser("Bob");
        UUID conversationId = openDirect(alice, bob);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (Actor sender : List.of(alice, bob)) {
                results.add(executor.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    return sendText(conversationId, sender, "race").andReturn()
                            .getResponse().getStatus();
                }));
            }
            start.countDown();
            for (Future<Integer> result : results) {
                assertThat(result.get(20, TimeUnit.SECONDS)).isEqualTo(201);
            }
        } finally {
            executor.shutdownNow();
        }

        JsonNode streak = streakOf(alice, conversationId);
        assertThat(streak.get("count").asInt()).isEqualTo(1);
        assertThat(streak.get("bestCount").asInt()).isEqualTo(1);
        assertThat(countRows("SELECT count(*) FROM conversation_streaks WHERE conversation_id = ?",
                conversationId)).isEqualTo(1);
    }

    @Test
    void aNonMemberCannotReadTheChain() throws Exception {
        Actor alice = newUser("Alice");
        Actor bob = newUser("Bob");
        Actor stranger = newUser("Stranger");
        UUID conversationId = openDirect(alice, bob);

        getAs(stranger, "/api/v1/conversations/" + conversationId + "/streak")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_MEMBER"));
    }

    @Test
    void checkInIsIdempotentAndCompletesWithASentMessage() throws Exception {
        Actor alice = newUser("Alice");

        JsonNode first = json(postAs(alice, "/api/v1/streaks/check-in", null)
                .andExpect(status().isOk()));
        assertThat(first.get("checkIn").get("openedToday").asBoolean()).isTrue();
        assertThat(first.get("checkIn").get("sentToday").asBoolean()).isFalse();
        assertThat(first.get("checkIn").get("count").asInt()).isZero();
        assertThat(first.get("reminderEnabled").asBoolean()).isFalse();

        json(postAs(alice, "/api/v1/streaks/check-in", null).andExpect(status().isOk()));

        Actor bob = newUser("Bob");
        UUID conversationId = openDirect(alice, bob);
        sendText(conversationId, alice, "morning").andExpect(status().isCreated());

        JsonNode summary = checkInSummary(alice);
        assertThat(summary.get("checkIn").get("count").asInt()).isEqualTo(1);
        assertThat(summary.get("checkIn").get("openedToday").asBoolean()).isTrue();
        assertThat(summary.get("checkIn").get("sentToday").asBoolean()).isTrue();
        assertThat(summary.get("checkIn").get("atRisk").asBoolean()).isFalse();
        assertThat(summary.get("checkIn").has("mySentToday")).isFalse();
        assertThat(countRows("SELECT count(*) FROM user_streaks WHERE user_id = ?", alice.id()))
                .isEqualTo(1);
    }

    @Test
    void theInboxCarriesTheCountAndTheRiskFlag() throws Exception {
        Actor alice = newUser("Alice");
        Actor bob = newUser("Bob");
        UUID conversationId = openDirect(alice, bob);
        sendText(conversationId, alice, "hi").andExpect(status().isCreated());
        sendText(conversationId, bob, "hey").andExpect(status().isCreated());

        JsonNode inbox = json(getAs(alice, "/api/v1/conversations").andExpect(status().isOk()));
        JsonNode entry = findConversation(inbox, conversationId);
        assertThat(entry.get("streakCount").asInt()).isEqualTo(1);
        assertThat(entry.get("streakAtRisk").asBoolean()).isFalse();

        // Yesterday's completed day is alive but not done today.
        jdbcTemplate.update("""
                UPDATE conversation_streaks
                SET low_day = ?, high_day = ?, last_day = ?, started_on = ?
                WHERE conversation_id = ?
                """, today().minusDays(1), today().minusDays(1), today().minusDays(1),
                today().minusDays(1), conversationId);

        JsonNode atRisk = findConversation(
                json(getAs(alice, "/api/v1/conversations").andExpect(status().isOk())), conversationId);
        assertThat(atRisk.get("streakCount").asInt()).isEqualTo(1);
        assertThat(atRisk.get("streakAtRisk").asBoolean()).isTrue();
    }

    @Test
    void aLapsedChainReadsAsZeroButKeepsItsBest() throws Exception {
        Actor alice = newUser("Alice");
        Actor bob = newUser("Bob");
        UUID conversationId = openDirect(alice, bob);
        insertChain(conversationId, alice, bob, 5, today().minusDays(3));

        JsonNode streak = streakOf(alice, conversationId);
        assertThat(streak.get("count").asInt()).isZero();
        assertThat(streak.get("bestCount").asInt()).isEqualTo(5);
        assertThat(streak.get("atRisk").asBoolean()).isFalse();
        assertThat(streak.get("milestone").asInt()).isZero();
        assertThat(streak.get("startedOn").isNull()).isTrue();
    }

    @Test
    void aChainInsideTheGraceWindowStaysAliveAndReportsItsMilestone() throws Exception {
        Actor alice = newUser("Alice");
        Actor bob = newUser("Bob");
        UUID conversationId = openDirect(alice, bob);
        insertChain(conversationId, alice, bob, 7, today().minusDays(2));

        JsonNode streak = streakOf(alice, conversationId);
        assertThat(streak.get("count").asInt()).isEqualTo(7);
        assertThat(streak.get("atRisk").asBoolean()).isTrue();
        assertThat(streak.get("freezeAvailable").asBoolean()).isTrue();
        assertThat(streak.get("milestone").asInt()).isEqualTo(7);
    }

    @Test
    void theReminderOptInRoundTripsThroughSettings() throws Exception {
        Actor alice = newUser("Alice");

        patchAs(alice, "/api/v1/users/me/settings", Map.of("streakReminders", true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.streakReminders").value(true));
        getAs(alice, "/api/v1/users/me/settings")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.streakReminders").value(true));
        assertThat(checkInSummary(alice).get("reminderEnabled").asBoolean()).isTrue();

        patchAs(alice, "/api/v1/users/me/settings", Map.of("streakReminders", false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.streakReminders").value(false));
        assertThat(checkInSummary(alice).get("reminderEnabled").asBoolean()).isFalse();
    }

    @Test
    void theReminderJobPushesOncePerUserPerDayForTheBestAtRiskChain() throws Exception {
        Actor alice = newUser("Alice");
        Actor bob = newUser("Bob");
        UUID conversationId = openDirect(alice, bob);
        insertChain(conversationId, alice, bob, 5, today().minusDays(1));
        patchAs(alice, "/api/v1/users/me/settings", Map.of("streakReminders", true))
                .andExpect(status().isOk());
        postAs(alice, "/api/v1/devices/token",
                Map.of("token", "streak-token-" + UUID.randomUUID(), "platform", "ANDROID"))
                .andExpect(status().is2xxSuccessful());
        fcmGateway.clear();

        Instant reminderHour = today().atTime(streakProperties.getReminderHour(), 0)
                .toInstant(ZoneOffset.UTC);
        assertThat(reminderJob.runAt(reminderHour)).isEqualTo(1);

        List<MockFcmGateway.SentPush> pushes = fcmGateway.getSentPushes().stream()
                .filter(push -> "STREAK_REMINDER".equals(push.data().get("type")))
                .toList();
        assertThat(pushes).hasSize(1);
        assertThat(pushes.get(0).data())
                .containsEntry("conversationId", conversationId.toString())
                .containsEntry("peerName", "Bob")
                .containsEntry("count", "5");

        // The same day is claimed: a second pass sends nothing.
        assertThat(reminderJob.runAt(reminderHour)).isZero();
        assertThat(fcmGateway.getSentPushes().stream()
                .filter(push -> "STREAK_REMINDER".equals(push.data().get("type")))).hasSize(1);
    }

    @Test
    void theReminderJobSkipsOptedOutMutedAndBlockedUsers() throws Exception {
        Actor alice = newUser("Alice");
        Actor bob = newUser("Bob");
        Actor carol = newUser("Carol");
        UUID aliceBob = openDirect(alice, bob);
        UUID carolBob = openDirect(carol, bob);
        insertChain(aliceBob, alice, bob, 5, today().minusDays(1));
        insertChain(carolBob, carol, bob, 5, today().minusDays(1));
        postAs(alice, "/api/v1/devices/token",
                Map.of("token", "opted-out-" + UUID.randomUUID(), "platform", "ANDROID"))
                .andExpect(status().is2xxSuccessful());
        postAs(carol, "/api/v1/devices/token",
                Map.of("token", "blocked-" + UUID.randomUUID(), "platform", "ANDROID"))
                .andExpect(status().is2xxSuccessful());
        // Alice never opted in; Carol opted in but Bob blocked her.
        patchAs(carol, "/api/v1/users/me/settings", Map.of("streakReminders", true))
                .andExpect(status().isOk());
        postAs(bob, "/api/v1/users/" + carol.id() + "/block", null)
                .andExpect(status().isNoContent());
        fcmGateway.clear();

        Instant reminderHour = today().atTime(streakProperties.getReminderHour(), 0)
                .toInstant(ZoneOffset.UTC);

        assertThat(reminderJob.runAt(reminderHour)).isZero();
        assertThat(fcmGateway.getSentPushes()).isEmpty();
    }

    @Test
    void theReminderJobDoesNothingOutsideTheConfiguredHour() throws Exception {
        Actor alice = newUser("Alice");
        Actor bob = newUser("Bob");
        UUID conversationId = openDirect(alice, bob);
        insertChain(conversationId, alice, bob, 5, today().minusDays(1));
        patchAs(alice, "/api/v1/users/me/settings", Map.of("streakReminders", true))
                .andExpect(status().isOk());
        postAs(alice, "/api/v1/devices/token",
                Map.of("token", "off-hour-" + UUID.randomUUID(), "platform", "ANDROID"))
                .andExpect(status().is2xxSuccessful());
        fcmGateway.clear();

        int otherHour = (streakProperties.getReminderHour() + 1) % 24;
        Instant outside = today().atTime(otherHour, 0).toInstant(ZoneOffset.UTC);

        assertThat(reminderJob.runAt(outside)).isZero();
        assertThat(fcmGateway.getSentPushes()).isEmpty();
    }

    @Test
    void aCrossMidnightRaceRecoversThroughTheStoreAndLegacyRowsWithoutFloorsBehaveAsBefore()
            throws Exception {
        // The race end to end: each record goes through the real store with
        // its own streak day, in the order the conversation lock can produce
        // around midnight (A's day-2 record stored before B's day-1 record).
        Actor alice = newUser("Alice");
        Actor bob = newUser("Bob");
        UUID conversationId = openDirect(alice, bob);
        LocalDate day0 = today().minusDays(2);
        LocalDate day1 = today().minusDays(1);
        LocalDate day2 = today();

        recordChain(conversationId, alice, bob, alice, day0);
        recordChain(conversationId, alice, bob, bob, day0);
        recordChain(conversationId, alice, bob, alice, day1);
        recordChain(conversationId, alice, bob, alice, day2);
        recordChain(conversationId, alice, bob, bob, day1);    // stale: behind A's day 2
        recordChain(conversationId, alice, bob, bob, day2);

        JsonNode streak = streakOf(alice, conversationId);
        assertThat(streak.get("count").asInt()).isEqualTo(3);
        assertThat(streak.get("bestCount").asInt()).isEqualTo(3);
        assertThat(streak.get("startedOn").asText()).isEqualTo(day0.toString());
        assertThat(streak.get("lastDay").asText()).isEqualTo(day2.toString());
        assertThat(streak.get("freezeAvailable").asBoolean()).isTrue();
        // The contiguous-run floors persist: the run starts on day 0 for both
        // sides of the recovered chain.
        assertThat(chainFloor(conversationId, "low_cont")).isEqualTo(day0);
        assertThat(chainFloor(conversationId, "high_cont")).isEqualTo(day0);

        // A row written before the floor columns existed keeps its behavior:
        // the day still completes once both pointers reach it and a stale
        // replay changes nothing.
        Actor carol = newUser("Carol");
        Actor dan = newUser("Dan");
        UUID legacyId = openDirect(carol, dan);
        LocalDate yesterday = today().minusDays(1);
        insertChain(legacyId, carol, dan, 1, yesterday);
        assertThat(chainFloor(legacyId, "low_cont")).isNull();
        assertThat(chainFloor(legacyId, "high_cont")).isNull();

        recordChain(legacyId, carol, dan, carol, today());
        assertThat(streakOf(carol, legacyId).get("count").asInt()).isEqualTo(1);

        recordChain(legacyId, carol, dan, dan, today());
        JsonNode legacy = streakOf(carol, legacyId);
        assertThat(legacy.get("count").asInt()).isEqualTo(2);
        assertThat(legacy.get("lastDay").asText()).isEqualTo(today().toString());
        assertThat(legacy.get("freezeAvailable").asBoolean()).isTrue();

        recordChain(legacyId, carol, dan, dan, yesterday);
        assertThat(streakOf(carol, legacyId).get("count").asInt()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ helpers

    /** Writes one side of a chain through the store with an explicit day. */
    private void recordChain(UUID conversationId, Actor a, Actor b, Actor sender, LocalDate day) {
        boolean aFirst = a.id().toString().compareTo(b.id().toString()) < 0;
        UUID low = aFirst ? a.id() : b.id();
        UUID high = aFirst ? b.id() : a.id();
        Side side = sender.id().equals(low) ? Side.A : Side.B;
        streakStore.applyChain(conversationId, low, high, side, day);
    }

    /** The stored contiguous-run floor of one side's column. */
    private LocalDate chainFloor(UUID conversationId, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM conversation_streaks WHERE conversation_id = ?",
                LocalDate.class, conversationId);
    }

    private void putDevice(Actor actor, UUID deviceId) throws Exception {
        byte[] key = new byte[32];
        key[0] = (byte) deviceId.hashCode();
        mockMvc.perform(put("/api/v1/e2ee/devices/" + deviceId)
                        .header("Authorization", bearer(actor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "publicKey", java.util.Base64.getUrlEncoder().withoutPadding()
                                        .encodeToString(key)))))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions sendEncrypted(
            UUID conversationId, Actor sender, UUID... deviceIds) throws Exception {
        List<Map<String, String>> envelopes = java.util.Arrays.stream(deviceIds)
                .map(deviceId -> Map.of("deviceId", deviceId.toString(),
                        "wrappedKey", "d3JhcHBlZA"))
                .toList();
        return mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/messages")
                .header("Authorization", bearer(sender))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "clientMessageId", UUID.randomUUID().toString(),
                        "type", "TEXT",
                        "content", "e2ee1:dGVzdA==",
                        "encryption", "E2EE_V1",
                        "e2eeEnvelopes", envelopes,
                        "attachmentIds", List.of()))));
    }

    /** Writes a chain whose last completed day is {@code lastDay}. */
    private void insertChain(UUID conversationId, Actor a, Actor b, int count, LocalDate lastDay) {
        String first = a.id().toString();
        String second = b.id().toString();
        UUID low = first.compareTo(second) < 0 ? a.id() : b.id();
        UUID high = first.compareTo(second) < 0 ? b.id() : a.id();
        jdbcTemplate.update("""
                INSERT INTO conversation_streaks
                    (conversation_id, user_low_id, user_high_id, low_day, high_day, count,
                     best_count, last_day, started_on, freeze_used_on)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)
                """, conversationId, low, high, lastDay, lastDay, count, count, lastDay,
                lastDay.minusDays(count - 1L));
    }

    private static JsonNode findConversation(JsonNode inbox, UUID conversationId) {
        for (JsonNode entry : inbox) {
            if (conversationId.toString().equals(entry.get("id").asText())) {
                return entry;
            }
        }
        throw new AssertionError("Conversation " + conversationId + " missing from the inbox");
    }
}
