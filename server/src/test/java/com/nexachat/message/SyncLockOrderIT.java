package com.nexachat.message;

import com.nexachat.auth.AuthService;
import com.nexachat.auth.dto.RegisterRequest;
import com.nexachat.call.CallService;
import com.nexachat.call.CallSignal;
import com.nexachat.conversation.ConversationService;
import com.nexachat.conversation.GroupService;
import com.nexachat.conversation.dto.AddMemberRequest;
import com.nexachat.conversation.dto.CreateGroupRequest;
import com.nexachat.conversation.dto.OpenDirectRequest;
import com.nexachat.conversation.dto.PatchGroupRequest;
import com.nexachat.message.dto.MessageResponse;
import com.nexachat.message.dto.PinRequest;
import com.nexachat.message.dto.SendMessageRequest;
import com.nexachat.message.dto.SyncRequest;
import com.nexachat.poll.PollService;
import com.nexachat.poll.dto.CreatePollRequest;
import com.nexachat.reminder.ReminderService;
import com.nexachat.reminder.dto.CreateReminderRequest;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic lock-schedule regressions for the sync-writer prerequisites
 * (Sol's F01-F06). Every schedule runs against real PostgreSQL:
 *
 * <ul>
 *   <li>a transaction-scoped advisory gate pauses one real service transaction
 *       at a named point (a trigger installed only for that test);</li>
 *   <li>the competing real service transaction (or the account-deletion lock
 *       prefix) is started and observed through {@code pg_blocking_pids};</li>
 *   <li>while the first transaction is paused, a probe transaction requests
 *       {@code FOR UPDATE} on each predecessor row: the paused backend appears
 *       among {@code pg_blocking_pids} only when it really holds that row; and</li>
 *   <li>the gate is released and the schedule must terminate without SQLSTATE
 *       {@code 40P01}.</li>
 * </ul>
 *
 * <p>Canonical order under test: avatar advisory -&gt; attachment -&gt; user -&gt;
 * conversation -&gt; message -&gt; derived rows -&gt; reaction-event advisory.
 *
 * <p><b>Row-lock modes matter.</b> Spring Data's {@code @Lock(PESSIMISTIC_WRITE)}
 * emits {@code FOR NO KEY UPDATE} on PostgreSQL, which is intentionally
 * compatible with the {@code FOR KEY SHARE} an FK check takes. The predecessor
 * assertions therefore prove that the required row lock is <em>held</em> at the
 * gate; they are stronger than "it happened to block" and do not depend on
 * {@code deadlock_timeout}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SyncLockOrderIT {

    private static final long GATE_BASE = 918_273_640L;

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat_test"));
        registry.add("spring.datasource.username", () -> env("IT_DB_USER", "nexachat"));
        registry.add("spring.datasource.password", () -> env("IT_DB_PASSWORD", "nexachat"));
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "8");
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @Autowired
    private AuthService authService;
    @Autowired
    private MessageService messageService;
    @Autowired
    private ReactionService reactionService;
    @Autowired
    private PinService pinService;
    @Autowired
    private GroupService groupService;
    @Autowired
    private ConversationService conversationService;
    @Autowired
    private com.nexachat.attachment.AvatarController avatarController;
    @Autowired
    private CallService callService;
    @Autowired
    private PollService pollService;
    @Autowired
    private ReminderService reminderService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ExecutorService executor;
    private final List<Connection> holderConnections = new ArrayList<>();
    private final List<String[]> installedTriggers = new ArrayList<>();

    @BeforeEach
    void openExecutor() {
        executor = Executors.newFixedThreadPool(4);
    }

    @AfterEach
    void cleanup() {
        holderConnections.forEach(SyncLockOrderIT::quietRollback);
        holderConnections.clear();
        for (String[] trigger : installedTriggers) {
            try {
                jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + trigger[0] + " ON " + trigger[1]);
                jdbcTemplate.execute("DROP FUNCTION IF EXISTS " + trigger[0] + "_fn()");
            } catch (RuntimeException cleanupFailure) {
                System.err.println("Lock-schedule trigger cleanup deferred: " + cleanupFailure.getMessage());
            }
        }
        installedTriggers.clear();
        executor.shutdownNow();
    }

    // --------------------------------------------------------------- S1

    @Test
    void reactionVsRecall() throws Exception {
        TestUser alice = register("reactionVsRecall");
        TestUser bob = register("reactionVsRecallPeer");
        UUID conversationId = createGroup(alice, bob, "reaction-vs-recall " + System.nanoTime());
        MessageResponse message = sendText(alice, conversationId, "reaction-vs-recall");

        long key = installGate("sync_lock_gate_reaction_recall", "reaction_events", "BEFORE INSERT", "");
        try (Gate gate = openGate(key)) {
            Future<String> reaction = submit(() ->
                    reactionService.add(bob.id(), message.id(), "👍"));
            int reactionPid = awaitBlockedBy(gate.pid());

            assertHoldsRow(reactionPid, "users", bob.id());
            assertHoldsRow(reactionPid, "conversations", conversationId);

            Future<String> recall = submit(() ->
                    messageService.delete(alice.id(), conversationId, message.id()));
            assertThat(awaitBlockedBy(reactionPid)).isPositive();

            gate.release();
            assertThat(failures(reaction, recall))
                    .as("reaction/recall must terminate without a deadlock")
                    .isEmpty();
        }
    }

    // --------------------------------------------------------------- S2

    @Test
    void reactionVsAccountDeletion() throws Exception {
        TestUser alice = register("reactionVsDeletion");
        TestUser bob = register("reactionVsDeletionPeer");
        UUID conversationId = createGroup(alice, bob, "reaction-vs-deletion " + System.nanoTime());
        MessageResponse message = sendText(bob, conversationId, "reaction-vs-deletion");

        // FOR NO KEY UPDATE is compatible with the reaction path's FOR KEY SHARE,
        // so it lets the pre-fix reaction slip through without reaching the oracle.
        Connection deletionPrefix = openHolder();
        Future<String> reaction = null;
        try {
            // Match AccountDeletionService's FOR UPDATE locks so both the fixed
            // and pre-fix reaction are deterministically held at the parent row.
            lockRowForUpdate(deletionPrefix, "users", alice.id());
            lockRowForUpdate(deletionPrefix, "conversations", conversationId);

            reaction = submit(() -> reactionService.add(alice.id(), message.id(), "👍"));
            int reactionPid = awaitBlockedBy(pidOf(deletionPrefix));

            assertThat(rowLockBlockers("messages", message.id()))
                    .as("reaction %s must not hold the message row while it waits behind the deletion", reactionPid)
                    .doesNotContain(reactionPid);

            quietRollback(deletionPrefix);
            assertThat(failures(reaction))
                    .as("reaction must complete once the deletion prefix releases its locks")
                    .isEmpty();
        } finally {
            quietRollback(deletionPrefix);
            settle(reaction);
        }
    }

    // --------------------------------------------------------------- S3

    @Test
    void pinVsGroupAdd() throws Exception {
        TestUser alice = register("pinVsGroupAdd");
        TestUser bob = register("pinVsGroupAddPeer");
        TestUser charlie = register("pinVsGroupAddThird");
        UUID conversationId = createGroup(alice, bob, "pin-vs-add " + System.nanoTime());
        MessageResponse message = sendText(bob, conversationId, "pin-vs-add");

        long key = installGate("sync_lock_gate_pin_add", "pinned_messages", "BEFORE INSERT", "");
        try (Gate gate = openGate(key)) {
            Future<String> pin = submit(() ->
                    pinService.pin(alice.id(), conversationId, new PinRequest(message.id())));
            int pinPid = awaitBlockedBy(gate.pid());

            assertHoldsRow(pinPid, "users", alice.id());
            assertHoldsRow(pinPid, "conversations", conversationId);

            Future<String> add = submit(() ->
                    groupService.addMembers(alice.id(), conversationId,
                            new AddMemberRequest(List.of(charlie.id()))));
            assertThat(awaitBlockedBy(pinPid)).isPositive();

            gate.release();
            assertThat(failures(pin, add))
                    .as("pin/group-add must terminate without a deadlock")
                    .isEmpty();
        }
    }

    // --------------------------------------------------------------- S4

    @Test
    void avatarUploadVsGroupAvatarRename() throws Exception {
        TestUser alice = register("avatarVsRename");
        TestUser bob = register("avatarVsRenamePeer");
        UUID conversationId = createGroup(alice, bob, "avatar-vs-rename " + System.nanoTime());

        long key = installGate("sync_lock_gate_avatar", "messages", "BEFORE INSERT",
                "WHEN (NEW.type = 'SYSTEM' AND NEW.content LIKE '%renamed the group%')");
        try (Gate gate = openGate(key)) {
            Future<String> rename = submit(() ->
                    groupService.updateGroup(alice.id(), conversationId,
                            new PatchGroupRequest("renamed " + System.nanoTime(), "")));
            int renamePid = awaitBlockedBy(gate.pid());

            Future<String> upload = submit(() -> avatarController.uploadAvatar(
                    new com.nexachat.common.dto.JwtPrincipal(alice.id()), List.of(pngFile())));
            int uploadPid = awaitBlockedBy(renamePid);
            assertThat(rowLockBlockers("users", alice.id()))
                    .as("avatar upload %s must take the avatar advisory before the user row", uploadPid)
                    .doesNotContain(uploadPid);

            gate.release();
            assertThat(failures(rename, upload))
                    .as("group rename/avatar upload must terminate without a deadlock")
                    .isEmpty();
        }
    }

    // --------------------------------------------------------------- S5

    @Test
    void pollReminderVsAccountDeletion() throws Exception {
        TestUser alice = register("pollReminderVsDeletion");
        TestUser bob = register("pollReminderVsDeletionPeer");
        UUID conversationId = createGroup(alice, bob, "poll-reminder-vs-del " + System.nanoTime());

        Connection deletionPrefix = openHolder();
        lockRowForUpdate(deletionPrefix, "users", alice.id());
        lockRowForUpdate(deletionPrefix, "conversations", conversationId);

        long pollKey = installGate("sync_lock_gate_poll_del", "polls", "BEFORE INSERT", "");
        try (Gate pollGate = openGate(pollKey)) {
            Future<String> poll = submit(() -> pollService.create(alice.id(), conversationId,
                    new CreatePollRequest("lock-order poll?", List.of("yes", "no"),
                            false, false, null)));
            int pollPid = awaitBlockedBy(pidOf(deletionPrefix));

            assertThat(rowLockBlockers("conversations", conversationId))
                    .as("poll must not hold the conversation row while blocked behind deletion")
                    .doesNotContain(pollPid);

            quietRollback(deletionPrefix);
            pollGate.release();
            assertThat(failures(poll))
                    .as("poll create must complete once deletion releases its locks")
                    .isEmpty();
        } finally {
            quietRollback(deletionPrefix);
        }

        deletionPrefix = openHolder();
        lockRowForUpdate(deletionPrefix, "users", alice.id());
        lockRowForUpdate(deletionPrefix, "conversations", conversationId);

        long reminderKey = installGate("sync_lock_gate_reminder_del", "conversation_reminders",
                "BEFORE INSERT", "");
        try (Gate reminderGate = openGate(reminderKey)) {
            Future<String> reminder = submit(() -> reminderService.create(alice.id(), conversationId,
                    new CreateReminderRequest("lock-order reminder",
                            Instant.now().plusSeconds(3600), "NONE"), null));
            int reminderPid = awaitBlockedBy(pidOf(deletionPrefix));

            assertThat(rowLockBlockers("conversations", conversationId))
                    .as("reminder must not hold the conversation row while blocked behind deletion")
                    .doesNotContain(reminderPid);

            quietRollback(deletionPrefix);
            reminderGate.release();
            assertThat(failures(reminder))
                    .as("reminder create must complete once deletion releases its locks")
                    .isEmpty();
        } finally {
            quietRollback(deletionPrefix);
        }
    }

    // --------------------------------------------------------------- S6

    @Test
    void systemRemoveLeaveVsGroupAdd() throws Exception {
        TestUser bob = register("systemLeaveVsAddAdmin");
        TestUser alice = register("systemLeaveVsAdd");
        TestUser charlie = register("systemLeaveVsAddThird");
        // bob owns the group so alice may leave without violating the sole-admin rule.
        UUID conversationId = createGroup(bob, alice, "leave-vs-add " + System.nanoTime());

        long addKey = installGate("sync_lock_gate_member_insert", "conversation_members",
                "AFTER INSERT", "");
        long leaveKey = installGate("sync_lock_gate_leave_system", "messages",
                "BEFORE INSERT", "WHEN (NEW.type = 'SYSTEM' AND NEW.content LIKE '%left the group%')");
        Future<String> add = null;
        Future<String> leave = null;
        try (Gate addGate = openGate(addKey); Gate leaveGate = openGate(leaveKey)) {
            try {
                add = submit(() -> groupService.addMembers(bob.id(), conversationId,
                        new AddMemberRequest(List.of(charlie.id()))));
                int addPid = awaitBlockedBy(addGate.pid());
                assertHoldsRow(addPid, "users", bob.id());

                leave = submit(() -> groupService.leave(alice.id(), conversationId));
                BlockingWait firstWait = awaitBlockedByEither(addPid, leaveGate.pid(), leave);
                boolean leaveAtGate = firstWait.blockerPid() == leaveGate.pid();
                // The add and leave can both hold compatible locks on distinct
                // user rows. Finish add before probing the leave's row holders.
                addGate.release();
                assertThat(failures(add))
                        .as("group add must complete before checking the leave row holders")
                        .isEmpty();
                int gatedLeave = leaveAtGate
                        ? firstWait.blockedPid() : awaitBlockedBy(leaveGate.pid());
                assertHoldsRow(gatedLeave, "users", alice.id());
                assertHoldsRow(gatedLeave, "conversations", conversationId);

                leaveGate.release();
                assertThat(failures(add, leave))
                        .as("group add/leave must terminate without a deadlock")
                        .isEmpty();
            } finally {
                addGate.release();
                leaveGate.release();
                settle(add, leave);
            }
        }
    }

    // --------------------------------------------------------------- S7

    @Test
    void callParticipantsVsAccountDeletion() throws Exception {
        TestUser alice = register("callVsDeletion");
        TestUser bob = register("callVsDeletionPeer");
        UUID conversationId = conversationService.openDirect(alice.id(),
                new OpenDirectRequest(null, bob.id())).body().id();

        // Account deletion holds the participant row FOR UPDATE before it
        // touches any call record. The call must take the participant rows
        // first, so it may not reach the call-record insert while one is held.
        Connection participantLock = openHolder();
        Future<String> call = null;
        Gate gate = null;
        try {
            lockRowForUpdate(participantLock, "users", bob.id());
            long key = installGate("sync_lock_gate_call_record", "call_records", "BEFORE INSERT", "");
            gate = openGate(key);
            UUID callId = UUID.randomUUID();
            call = submit(() -> callService.signal(conversationId, alice.id(),
                    new CallSignal(CallSignal.Type.INVITE, callId, null, null)));

            BlockingWait firstWait = awaitBlockedByEither(pidOf(participantLock), gate.pid(), call);
            if (firstWait.blockerPid() == gate.pid()) {
                // On the pre-fix tree, the call reaches the record flush before
                // taking its participant parent lock. This row-holder oracle
                // must fail there, rather than waiting for a poll timeout.
                assertHoldsRow(firstWait.blockedPid(), "users", bob.id());
            } else {
                assertThat(firstWait.blockerPid())
                        .as("the call must wait on the participant row before flushing its call record")
                        .isEqualTo(pidOf(participantLock));
                assertThat(backendsWaitingOn(gate.pid()))
                        .as("no call transaction may reach the call-record flush while a participant row is held")
                        .isEmpty();
            }

            quietRollback(participantLock);
            gate.release();
            assertThat(failures(call))
                    .as("call invite must complete once the participant lock is released")
                    .isEmpty();
        } finally {
            quietRollback(participantLock);
            if (gate != null) {
                gate.release();
                gate.close();
            }
            settle(call);
        }
    }

    // --------------------------------------------------------------- S8

    @Test
    void reversedWatermarkReactionLocks() throws Exception {
        TestUser alice = register("reversedWatermark");
        TestUser bobA = register("reversedWatermarkA");
        TestUser bobB = register("reversedWatermarkB");
        UUID convA = createGroup(alice, bobA, "wm-a " + System.nanoTime());
        UUID convB = createGroup(alice, bobB, "wm-b " + System.nanoTime());
        MessageResponse msgA = sendText(bobA, convA, "wm-a");
        MessageResponse msgB = sendText(bobB, convB, "wm-b");
        reactionService.add(alice.id(), msgA.id(), "👍");
        reactionService.add(alice.id(), msgB.id(), "❤️");
        Instant since = Instant.now().minusSeconds(120);

        long seqA = messageService.syncDelta(alice.id(), new SyncRequest(
                List.of(new SyncRequest.ConversationWatermark(convA, 0L)),
                50, since)).deltas().get(0).maxSeq();
        long seqB = messageService.syncDelta(alice.id(), new SyncRequest(
                List.of(new SyncRequest.ConversationWatermark(convB, 0L)),
                50, since)).deltas().get(0).maxSeq();

        Future<String> ab = submit(() -> messageService.syncDelta(alice.id(), new SyncRequest(
                List.of(
                        new SyncRequest.ConversationWatermark(convA, seqA),
                        new SyncRequest.ConversationWatermark(convB, seqB)),
                50, since)));
        Future<String> ba = submit(() -> messageService.syncDelta(alice.id(), new SyncRequest(
                List.of(
                        new SyncRequest.ConversationWatermark(convB, seqB),
                        new SyncRequest.ConversationWatermark(convA, seqA)),
                50, since)));
        assertThat(failures(ab, ba))
                .as("two sync requests with reversed watermarks must not deadlock "
                        + "on the reaction-event advisories")
                .isEmpty();
    }

    // --------------------------------------------------------------- S9

    @Test
    void groupAddAndRenameDoNotUpgradeParentLocksOutOfOrder() throws Exception {
        TestUser alice = register("groupAddRename");
        TestUser bob = register("groupAddRenamePeer");
        TestUser charlie = register("groupAddRenameThird");
        UUID conversationId = createGroup(alice, bob, "add-and-rename " + System.nanoTime());
        assertThat(jdbcTemplate.update(
                "UPDATE conversation_members SET role = 'ADMIN' WHERE conversation_id = ? AND user_id = ?",
                conversationId, bob.id()))
                .as("fixture must give the add and rename paths distinct admins")
                .isEqualTo(1);

        long addKey = installGate("sync_lock_gate_rename_add", "conversation_members", "AFTER INSERT", "");
        long renameKey = installGate("sync_lock_gate_rename_system", "messages", "BEFORE INSERT",
                "WHEN (NEW.type = 'SYSTEM' AND NEW.content LIKE '%renamed the group%')");
        Future<String> add = null;
        Future<String> rename = null;
        try (Gate addGate = openGate(addKey); Gate renameGate = openGate(renameKey)) {
            try {
                add = submit(() -> groupService.addMembers(bob.id(), conversationId,
                        new AddMemberRequest(List.of(charlie.id()))));
                int addPid = awaitBlockedBy(addGate.pid());
                assertHoldsRow(addPid, "users", bob.id());
                assertHoldsRow(addPid, "conversations", conversationId);

                rename = submit(() -> groupService.updateGroup(alice.id(), conversationId,
                        new PatchGroupRequest("add-and-rename " + System.nanoTime(), null)));
                BlockingWait firstWait = awaitBlockedByEither(addPid, renameGate.pid(), rename);
                boolean renameAtGate = firstWait.blockerPid() == renameGate.pid();
                // Check the actor at the first real rename wait, before awaiting
                // add: on the old order rename can already own the conversation
                // while parked at its message gate, so add cannot finish yet.
                // Distinct admins keep add from masking rename's missing lock.
                assertHoldsRow(firstWait.blockedPid(), "users", alice.id());
                if (!renameAtGate) {
                    addGate.release();
                    assertThat(failures(add))
                            .as("group add must complete before checking the rename row holders")
                            .isEmpty();
                    int gatedRename = awaitBlockedBy(renameGate.pid());
                    assertHoldsRow(gatedRename, "users", alice.id());
                    assertHoldsRow(gatedRename, "conversations", conversationId);
                }

                renameGate.release();
                addGate.release();
                assertThat(failures(add, rename))
                        .as("group add/rename must take the user parent before the conversation write")
                        .isEmpty();
            } finally {
                addGate.release();
                renameGate.release();
                settle(add, rename);
            }
        }
    }

    // ------------------------------------------------------------- helpers

    private TestUser register(String tag) {
        // +84993 belongs to this suite: sibling ITs clean +8490/+8491/+8492/
        // +8495/+8497/+8498, and a shared prefix would let their FK-ordered
        // cleanup collide with these users' messages.
        String phone = "+84993" + String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
        var response = authService.register(
                new RegisterRequest(phone, "secret123", tag + " " + System.nanoTime()), "it");
        return new TestUser(response.user().id(), phone);
    }

    private UUID createGroup(TestUser creator, TestUser member, String title) {
        return groupService.createGroup(creator.id(),
                new CreateGroupRequest(title, List.of(member.id()))).id();
    }

    private MessageResponse sendText(TestUser sender, UUID conversationId, String content) {
        return messageService.send(sender.id(), conversationId,
                new SendMessageRequest(UUID.randomUUID(), "TEXT", content, null, List.of())).message();
    }

    private static MockMultipartFile pngFile() {
        byte[] png = new byte[] {
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0, 0, 0, 0x0D, 0x49, 0x48, 0x44, 0x52,
        };
        return new MockMultipartFile("file", "avatar.png", "image/png", png);
    }

    private Future<String> submit(ThrowingRunnable operation) {
        return executor.submit(() -> {
            try {
                operation.run();
                return null;
            } catch (Throwable failure) {
                return describe(failure);
            }
        });
    }

    private List<String> failures(Future<String>... futures) throws Exception {
        List<String> failures = new ArrayList<>();
        for (Future<String> future : futures) {
            try {
                String failure = future.get(30, TimeUnit.SECONDS);
                if (failure != null) {
                    failures.add(failure);
                }
            } catch (ExecutionException wrapped) {
                failures.add(describe(wrapped.getCause()));
            }
        }
        return failures;
    }

    private BlockingWait awaitBlockedByEither(int firstBlockerPid, int secondBlockerPid, Future<?> operation) {
        AtomicReference<BlockingWait> observed = new AtomicReference<>();
        Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(25))
                .until(() -> {
                    List<Map<String, Object>> waiters = jdbcTemplate.queryForList("""
                            SELECT pid,
                                   CAST(? AS int) = ANY(pg_blocking_pids(pid)) AS waits_for_first,
                                   CAST(? AS int) = ANY(pg_blocking_pids(pid)) AS waits_for_second
                            FROM pg_stat_activity
                            WHERE datname = current_database()
                              AND pid <> pg_backend_pid()
                              AND query NOT LIKE '%sync-lock-probe%'
                              AND query NOT LIKE '%pg_stat_activity%'
                              AND (CAST(? AS int) = ANY(pg_blocking_pids(pid))
                                   OR CAST(? AS int) = ANY(pg_blocking_pids(pid)))
                            """, firstBlockerPid, secondBlockerPid, firstBlockerPid, secondBlockerPid);
                    if (!waiters.isEmpty()) {
                        Map<String, Object> waiter = waiters.get(0);
                        int blockedPid = ((Number) waiter.get("pid")).intValue();
                        int blockerPid = Boolean.TRUE.equals(waiter.get("waits_for_first"))
                                ? firstBlockerPid : secondBlockerPid;
                        observed.set(new BlockingWait(blockedPid, blockerPid));
                        return true;
                    }
                    // Surface a fast completion as an assertion failure instead
                    // of reporting that a pg_blocking_pids poll timed out.
                    return operation.isDone();
                });
        assertThat(observed.get())
                .as("operation completed before blocking on either lock owner %s or %s",
                        firstBlockerPid, secondBlockerPid)
                .isNotNull();
        return observed.get();
    }

    private void settle(Future<?>... futures) {
        for (Future<?> future : futures) {
            if (future == null) {
                continue;
            }
            try {
                future.get(30, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                future.cancel(true);
                return;
            } catch (Exception completedOrTimedOut) {
                if (!future.isDone()) {
                    future.cancel(true);
                }
            }
        }
    }

    private static String describe(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String sqlState = root instanceof SQLException sql ? sql.getSQLState() : "-";
        return failure.getClass().getSimpleName() + ": " + failure.getMessage()
                + " | root=" + root.getClass().getSimpleName() + ": " + root.getMessage()
                + " | sqlState=" + sqlState;
    }

    private Gate openGate(long key) throws SQLException {
        Connection connection = openHolder();
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
            statement.setLong(1, key);
            statement.execute();
        }
        return new Gate(connection, key);
    }

    private Connection openHolder() throws SQLException {
        Connection connection = DriverManager.getConnection(
                env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat_test"),
                env("IT_DB_USER", "nexachat"),
                env("IT_DB_PASSWORD", "nexachat"));
        connection.setAutoCommit(false);
        holderConnections.add(connection);
        return connection;
    }

    private Connection openSessionHolder() throws SQLException {
        Connection connection = DriverManager.getConnection(
                env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat_test"),
                env("IT_DB_USER", "nexachat"),
                env("IT_DB_PASSWORD", "nexachat"));
        connection.setAutoCommit(true);
        holderConnections.add(connection);
        return connection;
    }

    private long advisoryKey(UUID conversationId) {
        Long key = jdbcTemplate.queryForObject(
                "SELECT hashtextextended(CAST(? AS text), 0)", Long.class, conversationId.toString());
        return key == null ? 0L : key;
    }

    private void sessionLock(Connection connection, long key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_lock(?)")) {
            statement.setLong(1, key);
            statement.execute();
        }
    }

    private void awaitAdvisoryHeld(long key, int holderPid) {
        Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(25))
                .untilAsserted(() -> {
                    Integer held = jdbcTemplate.queryForObject("""
                            SELECT count(*) FROM pg_locks
                            WHERE locktype = 'advisory' AND granted
                              AND classid::text = ? AND objid::text = ?
                              AND objsubid = 1 AND pid <> ?
                            """, Integer.class,
                            Long.toString((key >>> 32) & 0xFFFFFFFFL),
                            Long.toString(key & 0xFFFFFFFFL),
                            holderPid);
                    assertThat(held).isNotNull();
                    assertThat(held).isGreaterThanOrEqualTo(1);
                });
    }

    private void sessionUnlock(Connection connection, long key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            statement.setLong(1, key);
            statement.execute();
        }
    }

    private int pidOf(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_backend_pid()");
             var rows = statement.executeQuery()) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private void lockRowForUpdate(Connection connection, String table, UUID id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM " + table + " WHERE id = ? FOR UPDATE")) {
            statement.setObject(1, id);
            statement.executeQuery();
        }
    }

    private List<Integer> backendsWaitingOn(int blockerPid) {
        return jdbcTemplate.queryForList("""
                SELECT pid FROM pg_stat_activity
                WHERE datname = current_database()
                  AND CAST(? AS int) = ANY(pg_blocking_pids(pid))
                  AND query NOT LIKE '%sync-lock-probe%'
                  AND query NOT LIKE '%pg_stat_activity%'
                """, Integer.class, blockerPid);
    }

    private void lockRowForNoKeyUpdate(Connection connection, String table, UUID id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM " + table + " WHERE id = ? FOR NO KEY UPDATE")) {
            statement.setObject(1, id);
            statement.executeQuery();
        }
    }

    private int awaitBlockedBy(int blockerPid) {
        return awaitBlockedBy(blockerPid, null);
    }

    private int awaitBlockedBy(int blockerPid, String queryFragment) {
        AtomicInteger blocked = new AtomicInteger();
        Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(25))
                .untilAsserted(() -> {
                    List<Integer> pids = jdbcTemplate.queryForList("""
                            SELECT pid FROM pg_stat_activity
                            WHERE datname = current_database()
                              AND CAST(? AS int) = ANY(pg_blocking_pids(pid))
                              AND query NOT LIKE '%sync-lock-probe%'
                              AND query NOT LIKE '%pg_stat_activity%'
                              AND (CAST(? AS text) IS NULL OR query LIKE '%' || CAST(? AS text) || '%')
                            """, Integer.class, blockerPid, queryFragment, queryFragment);
                    assertThat(pids).as("a backend blocked by %s", blockerPid).isNotEmpty();
                    blocked.set(pids.get(0));
                });
        return blocked.get();
    }

    /**
     * Attempts {@code FOR UPDATE} on one row from a probe transaction and
     * returns every backend that blocked it. An empty set means the probe
     * acquired the row immediately: no other transaction holds it.
     */
    private Set<Integer> rowLockBlockers(String table, UUID id) {
        String marker = "sync-lock-probe-" + UUID.randomUUID();
        Connection probe;
        int probePid;
        try {
            probe = openHolder();
            probePid = pidOf(probe);
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
        Future<?> future = executor.submit(() -> {
            try (probe) {
                try (PreparedStatement timeout = probe.prepareStatement(
                        "SET LOCAL lock_timeout = '2500ms'")) {
                    timeout.execute();
                }
                try (PreparedStatement statement = probe.prepareStatement(
                        "/* " + marker + " */ SELECT id FROM " + table + " WHERE id = ? FOR UPDATE")) {
                    statement.setObject(1, id);
                    statement.executeQuery();
                }
                probe.rollback();
            } catch (SQLException expectedWhenLocked) {
                // lock_timeout or aborted probe: the row was held by another transaction.
            }
            return null;
        });
        try {
            AtomicReference<Set<Integer>> blockers = new AtomicReference<>(Set.of());
            Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(25))
                    .until(() -> {
                        if (future.isDone()) {
                            return true;
                        }
                        List<Integer> pids = jdbcTemplate.queryForList("""
                                SELECT pid FROM pg_stat_activity
                                WHERE datname = current_database()
                                  AND query LIKE ?
                                  AND pid <> pg_backend_pid()
                                """, Integer.class, "%" + marker + "%");
                        if (pids.isEmpty()) {
                            return false;
                        }
                        Set<Integer> found = new HashSet<>(jdbcTemplate.queryForList(
                                "SELECT unnest(pg_blocking_pids(?))", Integer.class, pids.get(0)));
                        if (!found.isEmpty()) {
                            blockers.set(found);
                            return true;
                        }
                        return false;
                    });
            return blockers.get();
        } finally {
            try {
                // Closing a JDBC connection while its query is blocked does not
                // immediately remove the server's tuple-lock waiter. Cancel this
                // exact owned backend and await its exit before another probe can
                // mistake it for the service transaction holding the row.
                if (!future.isDone()) {
                    jdbcTemplate.queryForObject("SELECT pg_cancel_backend(?)", Boolean.class, probePid);
                }
                future.get(10, TimeUnit.SECONDS);
                Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(25))
                        .until(() -> jdbcTemplate.queryForObject("""
                                SELECT count(*) FROM pg_stat_activity
                                WHERE datname = current_database() AND pid = ?
                                """, Integer.class, probePid) == 0);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted awaiting row-probe cleanup", interrupted);
            } catch (Exception cleanupFailure) {
                throw new IllegalStateException("Row-probe backend did not exit", cleanupFailure);
            } finally {
                quietRollback(probe);
                future.cancel(true);
            }
        }
    }

    private void assertHoldsRow(int pid, String table, UUID id) {
        assertThat(rowLockBlockers(table, id))
                .as("backend %s must hold the %s row %s before the next lock in the order", pid, table, id)
                .contains(pid);
    }

    private long installGate(String name, String table, String timing, String whenClause) {
        long key = GATE_BASE + installedTriggers.size() + 1;
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION %s_fn() RETURNS trigger LANGUAGE plpgsql AS $fn$
                BEGIN
                    PERFORM pg_advisory_xact_lock(%d);
                    RETURN NEW;
                END;
                $fn$
                """.formatted(name, key));
        jdbcTemplate.execute("CREATE TRIGGER " + name + " " + timing + " ON " + table
                + " FOR EACH ROW " + whenClause + " EXECUTE FUNCTION " + name + "_fn()");
        installedTriggers.add(new String[] {name, table});
        return key;
    }

    private static void quietRollback(Connection connection) {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.rollback();
                connection.close();
            }
        } catch (SQLException ignored) {
            // Disposable connection; the transaction dies with it.
        }
    }

    private final class Gate implements AutoCloseable {
        private final Connection connection;
        private final int pid;

        private Gate(Connection connection, long key) throws SQLException {
            this.connection = connection;
            this.pid = pidOf(connection);
        }

        private int pid() {
            return pid;
        }

        private void release() {
            quietRollback(connection);
        }

        @Override
        public void close() {
            quietRollback(connection);
        }
    }

    private record TestUser(UUID id, String phone) {
    }

    private record BlockingWait(int blockedPid, int blockerPid) {
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
