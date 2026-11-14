package com.nexachat.conversation;

import com.nexachat.auth.JwtService;
import com.nexachat.conversation.dto.CreateGroupRequest;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** A private PostgreSQL server and explicit blocker-graph schedules, never the application DB. */
@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("it")
public abstract class ConcurrentDatabaseITSupport {
    private static final AtomicInteger PHONES = new AtomicInteger();
    private static final AtomicInteger CLIENT_IPS = new AtomicInteger();
    protected String clientIp;

    private static final class Database {
        static final PostgreSQLContainer<?> POSTGRES = start();

        private static PostgreSQLContainer<?> start() {
            PostgreSQLContainer<?> container = new PostgreSQLContainer<>("postgres:16-alpine");
            container.start();
            System.out.println("Concurrency test PostgreSQL: " + container.getContainerId());
            Runtime.getRuntime().addShutdownHook(new Thread(container::stop, "concurrency-test-postgres-stop"));
            return container;
        }
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) throws Exception {
        properties.add("spring.datasource.url", Database.POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", Database.POSTGRES::getUsername);
        properties.add("spring.datasource.password", Database.POSTGRES::getPassword);
        properties.add("spring.datasource.hikari.maximum-pool-size", () -> "8");
        properties.add("spring.datasource.hikari.minimum-idle", () -> "0");
        properties.add("nexachat.storage.root", () -> storageRoot);
        properties.add("nexachat.rag.index.backfill-enabled", () -> "false");
    }

    private static final String storageRoot = temporaryStorage();

    private static String temporaryStorage() {
        try {
            return Files.createTempDirectory("nexachat-concurrency-uploads-").toString();
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected DataSource dataSource;
    @Autowired protected UserRepository users;
    @Autowired protected GroupService groups;
    @Autowired protected JwtService jwt;

    @BeforeEach
    void clearPrivateDatabase() {
        jdbc.execute("TRUNCATE users CASCADE");
        // Database fixtures are isolated per case; give the real IP limiter the same isolation.
        int address = CLIENT_IPS.incrementAndGet();
        clientIp = "198.18." + ((address >>> 8) & 255) + "." + (address & 255);
    }

    protected User user(String name) {
        return users.save(User.create("+84790" + String.format("%06d", PHONES.incrementAndGet()),
                "x".repeat(60), name));
    }

    protected UUID group(User owner, User... members) {
        return groups.createGroup(owner.getId(), new CreateGroupRequest("Original group",
                java.util.Arrays.stream(members).map(User::getId).toList())).id();
    }

    protected String access(User user) {
        return jwt.generateAccessToken(user);
    }

    protected long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    protected boolean member(UUID conversation, UUID user) {
        return count("SELECT count(*) FROM conversation_members WHERE conversation_id=? AND user_id=?",
                conversation, user) == 1;
    }

    /** Own a row lock; SQL performed on this connection commits before the waiting operation resumes. */
    protected Gate gate(String table, UUID id) throws Exception {
        if (!List.of("conversations", "users", "group_invite_links").contains(table)) {
            throw new IllegalArgumentException("Unsupported gate table");
        }
        Connection connection = dataSource.getConnection();
        connection.setAutoCommit(false);
        try (PreparedStatement lock = connection.prepareStatement("SELECT id FROM " + table + " WHERE id=? FOR UPDATE")) {
            lock.setObject(1, id);
            try (ResultSet rows = lock.executeQuery()) {
                assertThat(rows.next()).isTrue();
            }
        }
        return new Gate(connection);
    }

    protected Gate reactionGate(UUID conversation) throws Exception {
        Connection connection = dataSource.getConnection();
        connection.setAutoCommit(false);
        try (PreparedStatement lock = connection.prepareStatement(
                "SELECT pg_advisory_xact_lock(hashtextextended(CAST(? AS text),0))")) {
            lock.setObject(1, conversation);
            lock.executeQuery().close();
        }
        return new Gate(connection);
    }

    protected final class Gate implements AutoCloseable {
        public final Connection connection;
        public final int pid;
        private boolean completed;

        private Gate(Connection connection) throws Exception {
            this.connection = connection;
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT pg_backend_pid()")) {
                rows.next();
                pid = rows.getInt(1);
            }
        }

        public void update(String sql, Object... args) throws Exception {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (int index = 0; index < args.length; index++) statement.setObject(index + 1, args[index]);
                assertThat(statement.executeUpdate()).isEqualTo(1);
            }
        }

        public void commit() throws Exception {
            connection.commit();
            completed = true;
        }

        @Override public void close() throws Exception {
            try {
                if (!completed) connection.rollback();
            } finally {
                connection.close();
            }
        }
    }

    /** Poll a factual DB wait, with no elapsed-time assertion and no sleep-based scheduling. */
    protected void awaitBlocked(int blockerPid, int expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        try (Connection observer = dataSource.getConnection(); PreparedStatement query = observer.prepareStatement("""
                WITH RECURSIVE blocked(pid) AS (
                    SELECT pid FROM pg_stat_activity
                    WHERE datname=current_database() AND ? = ANY(pg_blocking_pids(pid))
                    UNION
                    SELECT activity.pid FROM pg_stat_activity activity JOIN blocked
                      ON blocked.pid = ANY(pg_blocking_pids(activity.pid))
                    WHERE activity.datname=current_database()
                ) SELECT count(DISTINCT pid) FROM blocked
                """)) {
            query.setInt(1, blockerPid);
            while (System.nanoTime() < deadline) {
                try (ResultSet rows = query.executeQuery()) {
                    rows.next();
                    if (rows.getInt(1) >= expected) return;
                }
                Thread.onSpinWait();
            }
        }
        throw new AssertionError("Expected " + expected + " transaction(s) blocked by backend " + blockerPid);
    }

    protected static final class Workers implements AutoCloseable {
        private final ExecutorService executor = Executors.newFixedThreadPool(3);

        public Workers() { }

        public <T> Future<T> submit(Callable<T> action) {
            return executor.submit(action);
        }

        @Override public void close() throws Exception {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).as("all owned request threads stopped").isTrue();
        }
    }

    protected static <T> T result(Future<T> future) throws Exception {
        return future.get(30, TimeUnit.SECONDS);
    }
}
