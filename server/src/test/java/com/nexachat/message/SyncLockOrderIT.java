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

        // AccountDeletionService.java:95 locks the user, GroupService.java:308
        // locks the conversation. Both are held in one open transaction.
        Connection deletionPrefix = openHolder();
        lockRowForUpdate(deletionPrefix, "users", alice.id());
        lockRowForUpdate(deletionPrefix, "conversations", conversationId);

        Future<String> reaction = submit(() ->
                reactionService.add(alice.id(), message.id(), "👍"));
        int reactionPid = awaitBlockedBy(pidOf(deletionPrefix));

        assertThat(rowLockBlockers("messages", message.id()))
                .as("reaction %s must not hold the message row while it waits behind the deletion", reactionPid)
                .doesNotContain(reactionPid);
        assertHoldsRow(reactionPid, "users", alice.id());

        quietRollback(deletionPrefix);
        assertThat(failures(reaction))
                .as("reaction must complete once the deletion prefix releases its locks")
                .isEmpty();
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

    // ------------------------------------------------------------- helpers

    private TestUser register(String tag) {
        String phone = "+8491" + (1 + ThreadLocalRandom.current().nextInt(9))
                + String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
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

    private int awaitBlockedBy(int blockerPid) {
        AtomicInteger blocked = new AtomicInteger();
        Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(25))
                .untilAsserted(() -> {
                    List<Integer> pids = jdbcTemplate.queryForList("""
                            SELECT pid FROM pg_stat_activity
                            WHERE datname = current_database()
                              AND CAST(? AS int) = ANY(pg_blocking_pids(pid))
                            """, Integer.class, blockerPid);
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
        Connection probe;
        try {
            probe = openHolder();
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
                        "/* sync-lock-probe */ SELECT id FROM " + table + " WHERE id = ? FOR UPDATE")) {
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
                                  AND query LIKE '%sync-lock-probe%'
                                  AND pid <> pg_backend_pid()
                                """, Integer.class);
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
                probe.close();
            } catch (SQLException ignored) {
                // Disposable probe connection.
            }
            future.cancel(true);
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

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
