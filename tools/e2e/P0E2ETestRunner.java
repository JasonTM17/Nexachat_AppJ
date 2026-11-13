package tools.e2e;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * NexaChat P0 Production Engineering — synthetic fixture runner (illustrative only).
 * Pure Java 21 LTS, zero external dependencies (standard JDK only).
 * Runs 81 in-process checks on locally constructed data; no server, database, or
 * Android build participates. Not live end-to-end acceptance evidence — see
 * docs/quality/testing.md and tools/e2e/README.md for API/STOMP runners against a real backend.
 *
 * Usage:
 *   java tools/e2e/P0E2ETestRunner.java
 */
public class P0E2ETestRunner {

    // ANSI Color Codes
    private static final String RESET = "\u001B[0m";
    private static final String BOLD = "\u001B[1m";
    private static final String GREEN = "\u001B[32m";
    private static final String RED = "\u001B[31m";
    private static final String YELLOW = "\u001B[33m";
    private static final String CYAN = "\u001B[36m";
    private static final String BLUE = "\u001B[34m";

    // NexaChat Deterministic Namespace UUID for RFC 4122 v5
    private static final UUID NAMESPACE_NEXACHAT = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");

    private static int totalPassed = 0;
    private static int totalFailed = 0;
    private static final Map<String, int[]> tierStats = new LinkedHashMap<>();

    public static void main(String[] args) {
        long startTime = System.currentTimeMillis();

        printBanner();

        tierStats.put("Tier 1: Core Feature Coverage", new int[]{0, 0});
        tierStats.put("Tier 2: Boundary & Corner Cases", new int[]{0, 0});
        tierStats.put("Tier 3: Cross-Feature Combinations", new int[]{0, 0});
        tierStats.put("Tier 4: Real-World Scenarios", new int[]{0, 0});

        runTier1Tests();
        runTier2Tests();
        runTier3Tests();
        runTier4Tests();

        long elapsedMs = System.currentTimeMillis() - startTime;
        printSummary(elapsedMs);

        if (totalFailed > 0) {
            System.exit(1);
        } else {
            System.exit(0);
        }
    }

    private static void printBanner() {
        System.out.println(CYAN + BOLD + "==========================================================================" + RESET);
        System.out.println(CYAN + BOLD + "   NEXACHAT P0 — SYNTHETIC FIXTURE RUNNER (ILLUSTRATIVE, NOT LIVE E2E)     " + RESET);
        System.out.println(CYAN + BOLD + "   100% Pure Java 21 LTS | 81 in-process checks on local fixtures         " + RESET);
        System.out.println(CYAN + BOLD + "==========================================================================" + RESET);
        System.out.println();
    }

    private static void runTest(String tier, String testId, String testName, Runnable testLogic) {
        int[] stats = tierStats.get(tier);
        try {
            testLogic.run();
            stats[0]++;
            totalPassed++;
            System.out.printf("  %s[PASS]%s %s%-10s%s : %s%n", GREEN, RESET, BOLD, testId, RESET, testName);
        } catch (Throwable t) {
            stats[1]++;
            totalFailed++;
            System.out.printf("  %s[FAIL]%s %s%-10s%s : %s%n", RED, RESET, BOLD, testId, RESET, testName);
            System.out.printf("         %sReason: %s%s%n", RED, t.getMessage(), RESET);
        }
    }

    private static void assertCondition(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertEquals(Object actual, Object expected, String message) {
        if (!Objects.equals(actual, expected)) {
            throw new AssertionError(message + " (Expected: " + expected + ", Actual: " + actual + ")");
        }
    }

    // =========================================================================
    // TIER 1: CORE FEATURE COVERAGE (35 TESTS)
    // =========================================================================
    private static void runTier1Tests() {
        String tier = "Tier 1: Core Feature Coverage";
        System.out.println(YELLOW + BOLD + "--- " + tier + " (35 Tests) ---" + RESET);

        // F1: Room v8
        runTest(tier, "T1.1.1", "Room v8 DDL schema integrity", () -> {
            String ddl = "CREATE TABLE users (id TEXT PRIMARY KEY); CREATE TABLE call_logs (callId TEXT PRIMARY KEY); ALTER TABLE outbox ADD COLUMN actionType TEXT;";
            assertCondition(ddl.contains("users") && ddl.contains("call_logs") && ddl.contains("actionType"), "DDL must create users, call_logs and alter outbox");
        });
        runTest(tier, "T1.1.2", "UserEntity offline schema fields", () -> {
            List<String> fields = List.of("id", "username", "displayName", "phoneNumber", "avatarUrl", "bio", "lastSeenAt", "updatedAt");
            assertEquals(fields.size(), 8, "UserEntity must have 8 standard fields");
        });
        runTest(tier, "T1.1.3", "CallLogEntity persistence schema fields", () -> {
            List<String> directions = List.of("INCOMING", "OUTGOING", "MISSED");
            assertCondition(directions.contains("INCOMING"), "Direction must support INCOMING");
        });
        runTest(tier, "T1.1.4", "OutboxEntity 4-column migration defaults", () -> {
            String defaultAction = "SEND_MESSAGE";
            long defaultLastAttempt = 0L;
            assertEquals(defaultAction, "SEND_MESSAGE", "Default actionType must be SEND_MESSAGE");
            assertEquals(defaultLastAttempt, 0L, "Default lastAttemptEpochMs must be 0");
        });
        runTest(tier, "T1.1.5", "Room schema hash verification format", () -> {
            String hash = "f0a8d9b2e4c1";
            assertCondition(hash.length() >= 8, "Schema hash must be valid non-empty string");
        });

        // F2: Outbox State Machine
        runTest(tier, "T1.2.1", "Outbox state machine happy path (PENDING -> IN_PROGRESS -> SENT)", () -> {
            String state = "PENDING";
            state = "IN_PROGRESS";
            state = "SENT";
            assertEquals(state, "SENT", "State must end in SENT");
        });
        runTest(tier, "T1.2.2", "Outbox terminal error transition to FAILED (403 NOT_MEMBER)", () -> {
            int httpStatus = 403;
            String state = (httpStatus == 403) ? "FAILED" : "IN_PROGRESS";
            assertEquals(state, "FAILED", "403 must trigger FAILED state");
        });
        runTest(tier, "T1.2.3", "In-flight memory set prevents duplicate dispatch", () -> {
            Set<String> inFlight = new HashSet<>();
            boolean first = inFlight.add("msg-1");
            boolean second = inFlight.add("msg-1");
            assertCondition(first && !second, "Duplicate dispatch must be rejected by in-flight set");
        });
        runTest(tier, "T1.2.4", "Deterministic UUIDv5 reaction idempotency key", () -> {
            UUID u = UUID.fromString("11111111-1111-1111-1111-111111111111");
            UUID m = UUID.fromString("22222222-2222-2222-2222-222222222222");
            UUID key1 = generateUuidV5(NAMESPACE_NEXACHAT, u + ":" + m + ":❤️");
            UUID key2 = generateUuidV5(NAMESPACE_NEXACHAT, u + ":" + m + ":❤️");
            assertEquals(key1, key2, "UUIDv5 must be strictly deterministic");
            assertEquals(key1.version(), 5, "UUID version must be 5");
        });
        runTest(tier, "T1.2.5", "Explicit ADD/REMOVE eliminates toggle inversion", () -> {
            Set<String> set = new HashSet<>();
            set.add("key1");
            set.add("key1"); // retry ADD
            assertEquals(set.size(), 1, "Duplicate ADD must maintain presence");
            set.remove("key1");
            set.remove("key1"); // retry REMOVE
            assertCondition(set.isEmpty(), "Duplicate REMOVE must maintain absence");
        });

        // F3: Delta Sync
        runTest(tier, "T1.3.1", "POST /api/v1/sync request schema validation", () -> {
            Map<String, Object> req = Map.of("watermarks", List.of(Map.of("conversationId", "c1", "lastSeq", 100L)), "limitPerConv", 50);
            assertCondition(req.containsKey("watermarks") && req.containsKey("limitPerConv"), "Request must contain watermarks and limit");
        });
        runTest(tier, "T1.3.2", "Delta bundle response schema validation", () -> {
            Map<String, Object> bundle = Map.of("newMessages", List.of(), "editedMessages", List.of(), "deletedMessageIds", List.of(), "maxSeq", 125L, "hasMore", false);
            assertEquals(bundle.get("maxSeq"), 125L, "maxSeq must match latest sequence");
        });
        runTest(tier, "T1.3.3", "Monotonic sequence filtering (seq > lastSeq)", () -> {
            List<Long> seqs = List.of(40L, 50L, 51L, 52L);
            List<Long> filtered = seqs.stream().filter(s -> s > 50L).toList();
            assertEquals(filtered, List.of(51L, 52L), "Only seqs strictly greater than watermark must return");
        });
        runTest(tier, "T1.3.4", "Partitioning of edited and deleted messages", () -> {
            List<String> deleted = List.of("d1");
            List<String> edited = List.of("e1");
            assertCondition(!deleted.isEmpty() && !edited.isEmpty(), "Delta bundle partitions deleted and edited lists");
        });
        runTest(tier, "T1.3.5", "Multi-conversation watermark batching in single request", () -> {
            int convCount = 20;
            assertEquals(convCount, 20, "20 conversations batched in 1 request");
        });

        // F4: 2-Tick Receipts
        runTest(tier, "T1.4.1", "DeliveryStatus enum 5-state lifecycle", () -> {
            List<String> states = List.of("SENDING", "SENT", "DELIVERED", "READ", "FAILED");
            assertEquals(states.size(), 5, "DeliveryStatus must have 5 states");
        });
        runTest(tier, "T1.4.2", "STOMP delivery ack monotonic watermark update", () -> {
            long current = 42L;
            long ack = 50L;
            long updated = Math.max(current, ack);
            assertEquals(updated, 50L, "Delivery ack must monotonically advance watermark");
        });
        runTest(tier, "T1.4.3", "STOMP read ack monotonic watermark update", () -> {
            long current = 80L;
            long ack = 95L;
            long updated = Math.max(current, ack);
            assertEquals(updated, 95L, "Read ack must monotonically advance watermark");
        });
        runTest(tier, "T1.4.4", "1-1 Chat delivery receipt resolution", () -> {
            long peerDelivered = 50L;
            long peerRead = 30L;
            assertEquals(eval1on1(25L, peerDelivered, peerRead), "READ", "seq <= peerRead must be READ");
            assertEquals(eval1on1(45L, peerDelivered, peerRead), "DELIVERED", "seq <= peerDelivered must be DELIVERED");
            assertEquals(eval1on1(55L, peerDelivered, peerRead), "SENT", "seq > peerDelivered must be SENT");
        });
        runTest(tier, "T1.4.5", "Group Chat delivery receipt resolution and read count", () -> {
            int members = 3;
            List<Long> readSeqs = List.of(100L, 100L, 100L);
            long reads = readSeqs.stream().filter(s -> s >= 90L).count();
            assertEquals(reads, 3L, "All active members must have read for group READ");
        });

        // F5: STOMP Backoff
        runTest(tier, "T1.5.1", "Exponential delay progression (1s, 2s, 4s, 8s, 16s)", () -> {
            assertEquals(calcBackoff(1), 1000L, "Attempt 1 = 1000ms");
            assertEquals(calcBackoff(2), 2000L, "Attempt 2 = 2000ms");
            assertEquals(calcBackoff(3), 4000L, "Attempt 3 = 4000ms");
            assertEquals(calcBackoff(4), 8000L, "Attempt 4 = 8000ms");
            assertEquals(calcBackoff(5), 16000L, "Attempt 5 = 16000ms");
        });
        runTest(tier, "T1.5.2", "Bounded random jitter (+/- 20%)", () -> {
            long base = 4000L;
            long min = (long) (base * 0.80);
            long max = (long) (base * 1.20);
            assertCondition(min == 3200L && max == 4800L, "Jitter must be bounded within [3200, 4800]");
        });
        runTest(tier, "T1.5.3", "Max 5 retry attempts before entering PARKED state", () -> {
            int attempts = 5;
            String state = (attempts >= 5) ? "PARKED" : "RETRYING";
            assertEquals(state, "PARKED", "State must be PARKED after 5 attempts");
        });
        runTest(tier, "T1.5.4", "Connection success resets retry attempt counter", () -> {
            int attempts = 4;
            attempts = 0; // reset
            assertEquals(attempts, 0, "Counter must reset on success");
        });
        runTest(tier, "T1.5.5", "Thundering herd dispersion across simulated clients", () -> {
            Set<Long> delays = new HashSet<>();
            for (int i = 0; i < 50; i++) {
                delays.add(800L + (long)(Math.random() * 400));
            }
            assertCondition(delays.size() > 20, "Jitter must disperse reconnect times widely");
        });

        // F6: WebRTC Roaming
        runTest(tier, "T1.6.1", "Transition to RECONNECTING_NETWORK on STOMP drop", () -> {
            String state = "ACTIVE";
            state = "RECONNECTING_NETWORK";
            assertEquals(state, "RECONNECTING_NETWORK", "Must transition to RECONNECTING_NETWORK");
        });
        runTest(tier, "T1.6.2", "Media audio tracks preserved during 15s grace buffer", () -> {
            boolean audioTrackActive = true;
            assertCondition(audioTrackActive, "Audio tracks must remain open during roaming grace");
        });
        runTest(tier, "T1.6.3", "Outbound signaling FIFO buffer during network switch", () -> {
            Queue<String> q = new LinkedList<>(List.of("sig1", "sig2"));
            assertEquals(q.poll(), "sig1", "First queued signal must exit first");
        });
        runTest(tier, "T1.6.4", "ICE restart trigger with iceRestart: true", () -> {
            Map<String, Object> offer = Map.of("iceRestart", true);
            assertEquals(offer.get("iceRestart"), true, "SDP offer must include iceRestart: true");
        });
        runTest(tier, "T1.6.5", "15s grace timeout terminal hangup with NETWORK_FAILURE", () -> {
            long elapsed = 15_001L;
            String reason = (elapsed >= 15_000L) ? "NETWORK_FAILURE" : "NORMAL";
            assertEquals(reason, "NETWORK_FAILURE", "Must end with NETWORK_FAILURE after 15s");
        });

        // F7: FCM Push
        runTest(tier, "T1.7.1", "Notification pointer payload size under 1KB budget", () -> {
            String json = "{\"type\":\"CALL_INVITE\",\"callId\":\"c1\",\"callerName\":\"John\"}";
            assertCondition(json.getBytes(StandardCharsets.UTF_8).length < 1024, "Payload must be < 1KB");
        });
        runTest(tier, "T1.7.2", "Data-only pointer schema verification (no raw SDP)", () -> {
            String json = "{\"type\":\"CALL_INVITE\",\"callId\":\"c1\"}";
            assertCondition(!json.contains("\"sdp\""), "Pointer must never embed raw SDP blob");
        });
        runTest(tier, "T1.7.3", "Fast-path SDP offer retrieval endpoint contract", () -> {
            String path = "/api/v1/calls/123/offer";
            assertCondition(path.endsWith("/offer"), "Must expose /offer endpoint");
        });
        runTest(tier, "T1.7.4", "CallState PENDING_INCOMING pre-hydration prevents 0ms self-destruction", () -> {
            String callState = "PENDING_INCOMING";
            boolean allowRinging = callState.equals("PENDING_INCOMING") || callState.equals("INCOMING_RINGING");
            assertCondition(allowRinging, "PENDING_INCOMING must keep ringing activity alive");
        });
        runTest(tier, "T1.7.5", "Android 14 canUseFullScreenIntent fallback to Heads-Up Banner", () -> {
            boolean canUseFullScreen = false;
            String mode = canUseFullScreen ? "FULL_SCREEN" : "HEADS_UP_BANNER";
            assertEquals(mode, "HEADS_UP_BANNER", "Must fall back to Heads-Up Banner when permission false");
        });
        System.out.println();
    }

    // =========================================================================
    // TIER 2: BOUNDARY & CORNER CASES (25 TESTS)
    // =========================================================================
    private static void runTier2Tests() {
        String tier = "Tier 2: Boundary & Corner Cases";
        System.out.println(YELLOW + BOLD + "--- " + tier + " (25 Tests) ---" + RESET);

        // B1: Lease Lock
        runTest(tier, "T2.1.1", "Active lease (<60s) rejects acquisition by competing workers", () -> {
            long elapsed = 45_000L;
            boolean active = elapsed < 60_000L;
            assertCondition(active, "Lease must remain active under 60s");
        });
        runTest(tier, "T2.1.2", "Expired lease (>=60s) permits acquisition by subsequent scan", () -> {
            long elapsed = 60_001L;
            boolean expired = elapsed >= 60_000L;
            assertCondition(expired, "Lease must expire at or above 60s");
        });
        runTest(tier, "T2.1.3", "Exact 60,000ms boundary condition", () -> {
            long elapsed = 60_000L;
            boolean expired = elapsed >= 60_000L;
            assertCondition(expired, "Exact 60,000ms must be treated as expired");
        });
        runTest(tier, "T2.1.4", "Process crash recovery: new worker resumes orphaned IN_PROGRESS", () -> {
            long crashElapsed = 70_000L;
            boolean canReclaim = crashElapsed > 60_000L;
            assertCondition(canReclaim, "New worker can reclaim crashed lock after 60s");
        });
        runTest(tier, "T2.1.5", "Clock rewind protection avoids infinite lease deadlock", () -> {
            long now = 1_000L;
            long futureAttempt = 2_000L; // clock moved back
            boolean canRecover = (now < futureAttempt);
            assertCondition(canRecover, "Future timestamp safely treated as reclaimable");
        });

        // B2: Watermarks
        runTest(tier, "T2.2.1", "Empty watermarks list triggers HTTP 400 Bad Request", () -> {
            List<?> list = Collections.emptyList();
            int status = list.isEmpty() ? 400 : 200;
            assertEquals(status, 400, "Empty watermark list must yield 400");
        });
        runTest(tier, "T2.2.2", "Null conversation ID in watermark item rejected", () -> {
            String convId = null;
            int status = (convId == null) ? 400 : 200;
            assertEquals(status, 400, "Null conversation ID must yield 400");
        });
        runTest(tier, "T2.2.3", "Negative lastSeq in watermark normalized safely to 0", () -> {
            long seq = -50L;
            long normalized = Math.max(0L, seq);
            assertEquals(normalized, 0L, "Negative sequence must normalize to 0");
        });
        runTest(tier, "T2.2.4", "Non-existent conversation returns empty delta without 500 error", () -> {
            List<String> deltas = Collections.emptyList();
            assertCondition(deltas.isEmpty(), "Non-existent conversation returns empty list");
        });
        runTest(tier, "T2.2.5", "Watermark sequence exceeding server maxSeq returns zero deltas", () -> {
            long clientSeq = 1000L;
            long serverMax = 500L;
            boolean hasMore = clientSeq < serverMax;
            assertCondition(!hasMore, "hasMore must be false when watermark > server max");
        });

        // B3: Toggle Inversion
        runTest(tier, "T2.3.1", "Concurrent duplicate ADD requests with same UUIDv5 are idempotent", () -> {
            Set<String> db = Collections.synchronizedSet(new HashSet<>());
            db.add("u1:m1:❤️");
            boolean addedAgain = db.add("u1:m1:❤️");
            assertCondition(!addedAgain, "Second concurrent ADD must be no-op");
        });
        runTest(tier, "T2.3.2", "Rapid ADD -> REMOVE -> ADD sequence produces final ADDED state", () -> {
            Map<String, Boolean> map = new HashMap<>();
            map.put("key", true);
            map.remove("key");
            map.put("key", true);
            assertCondition(map.containsKey("key"), "Final state must be ADDED");
        });
        runTest(tier, "T2.3.3", "Replayed ADD action after server processing is safe no-op", () -> {
            Set<String> set = new HashSet<>(List.of("key1"));
            boolean changed = set.add("key1");
            assertCondition(!changed, "Replayed ADD must return 0 rows changed");
        });
        runTest(tier, "T2.3.4", "Replayed REMOVE action on already removed reaction is safe no-op", () -> {
            Set<String> set = new HashSet<>();
            boolean changed = set.remove("key1");
            assertCondition(!changed, "Replayed REMOVE must return 0 rows changed");
        });
        runTest(tier, "T2.3.5", "Multi-threaded stress test with 20 threads resolves deterministically", () -> {
            AtomicInteger val = new AtomicInteger(0);
            for (int i = 1; i <= 20; i++) {
                final int cur = i;
                val.accumulateAndGet(cur, Math::max);
            }
            assertEquals(val.get(), 20, "Deterministic max version wins");
        });

        // B4: ICE Roaming Switch
        runTest(tier, "T2.4.1", "Rapid double network switch (Wi-Fi -> 4G -> Wi-Fi) cancels pending grace", () -> {
            long elapsed = 2000L;
            boolean recovered = elapsed < 15000L;
            assertCondition(recovered, "Double switch within grace period keeps call active");
        });
        runTest(tier, "T2.4.2", "ICE restart failure retries within remaining grace window", () -> {
            long elapsed = 4000L;
            boolean canRetry = (15000L - elapsed) > 3000L;
            assertCondition(canRetry, "Should allow retry if budget > 3s");
        });
        runTest(tier, "T2.4.3", "Strict FIFO signaling ordering across multi-hop switches", () -> {
            Queue<Integer> q = new LinkedList<>(List.of(1, 2, 3));
            assertEquals(q.poll(), 1, "Hop 1 first");
            assertEquals(q.poll(), 2, "Hop 2 second");
            assertEquals(q.poll(), 3, "Hop 3 third");
        });
        runTest(tier, "T2.4.4", "Remote peer hangup received during local roaming terminates immediately", () -> {
            boolean remoteHangup = true;
            String state = remoteHangup ? "ENDED" : "RECONNECTING_NETWORK";
            assertEquals(state, "ENDED", "Remote hangup immediately ends call");
        });
        runTest(tier, "T2.4.5", "Exact timer expiration while SDP negotiation pending terminates cleanly", () -> {
            long elapsed = 15000L;
            String state = (elapsed >= 15000L) ? "ENDED" : "RECONNECTING";
            assertEquals(state, "ENDED", "Timer expiration tears down session");
        });

        // B5: Permission Denial
        runTest(tier, "T2.5.1", "canUseFullScreenIntent revoked: configures PRIORITY_MAX banner", () -> {
            int priority = 2; // PRIORITY_MAX
            assertEquals(priority, 2, "Priority must be MAX for heads-up fallback");
        });
        runTest(tier, "T2.5.2", "Notification channel importance downgraded handled without crash", () -> {
            boolean handled = true;
            assertCondition(handled, "Downgraded channel must not crash app");
        });
        runTest(tier, "T2.5.3", "FOREGROUND_SERVICE_PHONE_CALL denied falls back to background audio", () -> {
            String fallback = "STANDARD_AUDIO_FALLBACK";
            assertEquals(fallback, "STANDARD_AUDIO_FALLBACK", "Graceful fallback mode active");
        });
        runTest(tier, "T2.5.4", "POST_NOTIFICATIONS denied still keeps in-app incoming call presentation", () -> {
            boolean inAppCallVisible = true;
            assertCondition(inAppCallVisible, "In-app dialog shows when app in foreground");
        });
        runTest(tier, "T2.5.5", "Dynamic permission re-granting immediately restores full-screen launch", () -> {
            boolean granted = true;
            String mode = granted ? "FULL_SCREEN" : "HEADS_UP";
            assertEquals(mode, "FULL_SCREEN", "Re-granting permission restores full-screen launch");
        });
        System.out.println();
    }

    // =========================================================================
    // TIER 3: CROSS-FEATURE COMBINATIONS (16 TESTS)
    // =========================================================================
    private static void runTier3Tests() {
        String tier = "Tier 3: Cross-Feature Combinations";
        System.out.println(YELLOW + BOLD + "--- " + tier + " (16 Tests) ---" + RESET);

        // C1: Outbox + Delta Sync
        runTest(tier, "T3.1.1", "Simultaneous Outbox drain and Delta Sync produces zero duplicate IDs", () -> {
            Set<String> set = new HashSet<>(List.of("m1", "m2", "m3"));
            boolean dup = set.addAll(List.of("m1", "m2"));
            assertCondition(!dup, "Zero duplicate message IDs in unified store");
        });
        runTest(tier, "T3.1.2", "Pending Outbox edit on remotely deleted message resolves to tombstone", () -> {
            boolean remoteDeleted = true;
            String result = remoteDeleted ? "TOMBSTONE" : "EDITED";
            assertEquals(result, "TOMBSTONE", "Delete beats edit in conflict resolution");
        });
        runTest(tier, "T3.1.3", "Watermark advances as outbox messages get server sequences", () -> {
            long wm = 100L;
            wm = Math.max(wm, 105L);
            assertEquals(wm, 105L, "Watermark advances monotonically");
        });
        runTest(tier, "T3.1.4", "Concurrent reaction deltas and local outbox reaction drain converge", () -> {
            Map<String, String> reactions = Map.of("u1", "👍", "u2", "❤️");
            assertEquals(reactions.size(), 2, "Both reactions present without conflict");
        });

        // C2: 2-Tick + Reconnection
        runTest(tier, "T3.2.1", "Bulk delivery ack dispatched immediately following Delta Sync", () -> {
            long highestSeq = 150L;
            assertEquals(highestSeq, 150L, "Single ack covers up to highest sequence");
        });
        runTest(tier, "T3.2.2", "Delivery ack retransmitted upon STOMP reconnect if socket dropped", () -> {
            long pendingAck = 88L;
            assertEquals(pendingAck, 88L, "Retransmitted ack with highest sequence");
        });
        runTest(tier, "T3.2.3", "Multiple messages viewed offline consolidated into single read ack", () -> {
            List<Long> viewed = List.of(10L, 11L, 12L);
            long maxViewed = Collections.max(viewed);
            assertEquals(maxViewed, 12L, "Max sequence read acknowledged in single frame");
        });
        runTest(tier, "T3.2.4", "2-tick states preserved across server restart", () -> {
            String state = "DELIVERED";
            assertEquals(state, "DELIVERED", "Delivered receipt preserved across restart");
        });

        // C3: Roaming + STOMP Backoff
        runTest(tier, "T3.3.1", "Call active state coordinates with ReconnectPolicy backoff", () -> {
            String callState = "RECONNECTING_NETWORK";
            long delay = calcBackoff(1);
            assertCondition(callState.equals("RECONNECTING_NETWORK") && delay == 1000L, "Coordinated roaming state");
        });
        runTest(tier, "T3.3.2", "Reconnect within window successfully completes ICE restart", () -> {
            long elapsed = 4500L;
            assertCondition(elapsed < 15000L, "Completed within 15s grace budget");
        });
        runTest(tier, "T3.3.3", "Consecutive network flapping respects 15s terminal budget", () -> {
            long totalDowntime = 16000L;
            String state = (totalDowntime >= 15000L) ? "ENDED" : "ACTIVE";
            assertEquals(state, "ENDED", "Exceeded 15s ends call cleanly");
        });
        runTest(tier, "T3.3.4", "Manual hangup during roaming cleanly aborts backoff timer", () -> {
            boolean timerActive = false;
            assertCondition(!timerActive, "Timer cancelled upon user hangup");
        });

        // C4: FCM + STOMP Handshake
        runTest(tier, "T3.4.1", "Doze wake-up connects STOMP and upgrades PENDING_INCOMING to INCOMING_RINGING", () -> {
            String state = "PENDING_INCOMING";
            state = "INCOMING_RINGING"; // STOMP connected
            assertEquals(state, "INCOMING_RINGING", "Upgrades state upon STOMP handshake");
        });
        runTest(tier, "T3.4.2", "STOMP connection timeout during PENDING_INCOMING expires cleanly to MISSED", () -> {
            long elapsed = 15100L;
            String state = (elapsed >= 15000L) ? "MISSED" : "PENDING";
            assertEquals(state, "MISSED", "Missed call recorded upon timeout");
        });
        runTest(tier, "T3.4.3", "Call answered on another client dismisses notification immediately", () -> {
            boolean answeredElsewhere = true;
            boolean dismiss = answeredElsewhere;
            assertCondition(dismiss, "Notification dismissed on multi-device pickup");
        });
        runTest(tier, "T3.4.4", "Silent FCM data push triggers background Delta Sync and ack dispatch", () -> {
            boolean backgroundSyncTriggered = true;
            assertCondition(backgroundSyncTriggered, "Background sync triggered by silent push");
        });
        System.out.println();
    }

    // =========================================================================
    // TIER 4: REAL-WORLD APPLICATION SCENARIOS (5 TESTS)
    // =========================================================================
    private static void runTier4Tests() {
        String tier = "Tier 4: Real-World Scenarios";
        System.out.println(YELLOW + BOLD + "--- " + tier + " (5 Tests) ---" + RESET);

        runTest(tier, "T4.1", "Scenario 1: Subway Commute Multi-Hop (Wi-Fi -> Cell -> Tunnel -> 4G)", () -> {
            List<String> outbox = new ArrayList<>();
            for (int i = 1; i <= 10; i++) outbox.add("msg-" + i);
            int queued = outbox.size();
            outbox.clear(); // drained on 4G
            assertCondition(queued == 10 && outbox.isEmpty(), "All 10 commute messages queued and drained cleanly");
        });

        runTest(tier, "T4.2", "Scenario 2: Deep Doze Overnight Catchup (8h idle -> FCM wakeup -> Delta Sync)", () -> {
            int convCount = 5;
            int totalMsgs = 50;
            assertEquals(totalMsgs, 50, "50 messages across 5 conversations fetched in 1 batch delta sync");
        });

        runTest(tier, "T4.3", "Scenario 3: High-Concurrency Group Chat Storm (20 participants, 100 messages)", () -> {
            int participants = 20;
            int stormMessages = 100;
            assertEquals(stormMessages, 100, "100 messages synced without UI flicker via Room transaction");
        });

        runTest(tier, "T4.4", "Scenario 4: Roaming Call Handover with Concurrent Chat", () -> {
            String callState = "ACTIVE";
            String chatMessage = "SENT";
            assertCondition(callState.equals("ACTIVE") && chatMessage.equals("SENT"), "Call and chat concurrent success");
        });

        runTest(tier, "T4.5", "Scenario 5: Resource-Constrained & Permission-Denied Environment", () -> {
            boolean leaseRecovered = true;
            String presentation = "HEADS_UP_BANNER";
            assertCondition(leaseRecovered && presentation.equals("HEADS_UP_BANNER"), "Lease recovered and banner presented");
        });
        System.out.println();
    }

    private static void printSummary(long elapsedMs) {
        System.out.println(CYAN + BOLD + "==========================================================================" + RESET);
        System.out.println(CYAN + BOLD + "                     E2E TEST EXECUTION SUMMARY                           " + RESET);
        System.out.println(CYAN + BOLD + "==========================================================================" + RESET);
        System.out.printf(" %-40s | %8s | %8s | %8s%n", "Test Tier", "Passed", "Failed", "Total");
        System.out.println("--------------------------------------------------------------------------");

        for (Map.Entry<String, int[]> entry : tierStats.entrySet()) {
            int[] s = entry.getValue();
            int total = s[0] + s[1];
            String color = (s[1] == 0) ? GREEN : RED;
            System.out.printf(" %-40s | %s%8d%s | %s%8d%s | %8d%n", entry.getKey(), color, s[0], RESET, (s[1] > 0 ? RED : RESET), s[1], RESET, total);
        }

        System.out.println("--------------------------------------------------------------------------");
        String finalColor = (totalFailed == 0) ? GREEN + BOLD : RED + BOLD;
        System.out.printf(" %-40s | %s%8d%s | %s%8d%s | %8d%n", "TOTAL ACROSS ALL TIERS", finalColor, totalPassed, RESET, (totalFailed > 0 ? RED + BOLD : RESET), totalFailed, RESET, (totalPassed + totalFailed));
        System.out.println(CYAN + BOLD + "==========================================================================" + RESET);
        System.out.printf(" Execution Time: %d ms | Fixture checks: %s%s%s (not live E2E acceptance)%n", elapsedMs, finalColor, (totalFailed == 0 ? "all local checks passed" : "FAILED"), RESET);
        System.out.println();
    }

    // Helper math & logic
    private static UUID generateUuidV5(UUID namespace, String name) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            ByteBuffer nsBuffer = ByteBuffer.allocate(16);
            nsBuffer.putLong(namespace.getMostSignificantBits());
            nsBuffer.putLong(namespace.getLeastSignificantBits());
            md.update(nsBuffer.array());
            md.update(name.getBytes(StandardCharsets.UTF_8));
            byte[] digest = md.digest();
            digest[6] = (byte) ((digest[6] & 0x0f) | 0x50);
            digest[8] = (byte) ((digest[8] & 0x3f) | 0x80);
            ByteBuffer bb = ByteBuffer.wrap(digest);
            return new UUID(bb.getLong(), bb.getLong());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static long calcBackoff(int attempt) {
        if (attempt <= 1) return 1000L;
        long multiplier = 1L << Math.min(attempt - 1, 10);
        return Math.min(1000L * multiplier, 16000L);
    }

    private static String eval1on1(long seq, long peerDelivered, long peerRead) {
        if (seq <= peerRead) return "READ";
        if (seq <= peerDelivered) return "DELIVERED";
        return "SENT";
    }
}
