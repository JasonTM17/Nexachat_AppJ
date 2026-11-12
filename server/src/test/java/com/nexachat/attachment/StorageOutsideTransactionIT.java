package com.nexachat.attachment;

import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.GroupService;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real PostgreSQL and real local files; only store timing and cleanup failure are injected. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
@ExtendWith(OutputCaptureExtension.class)
class StorageOutsideTransactionIT {
    enum PathKind { ATTACHMENT, PERSONAL_AVATAR, GROUP_AVATAR }

    private static final Path ROOT = temporaryRoot();
    private static final byte[] PNG = java.util.HexFormat.of().parseHex("89504e470d0a1a0a0000000d49484452");

    private static Path temporaryRoot() {
        try { return Files.createTempDirectory("nexachat-s2-"); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat_sol_s2"));
        r.add("spring.datasource.username", () -> env("IT_DB_USER", "nexachat"));
        r.add("spring.datasource.password", () -> env("IT_DB_PASSWORD", "nexachat"));
        r.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
        r.add("spring.datasource.hikari.data-source-properties.ApplicationName", () -> "nexachat-s2-it");
        r.add("nexachat.storage.root", () -> ROOT.toString());
        r.add("nexachat.avatar-sweep.enabled", () -> "false");
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v;
    }

    @Autowired AttachmentService attachments;
    @Autowired AvatarController avatars;
    @Autowired GroupService groups;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoSpyBean StorageService storage;

    private UUID owner;
    private UUID group;
    private ExecutorService executor;
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicReference<String> storedKey = new AtomicReference<>();

    @BeforeEach
    void fixture() {
        owner = UUID.randomUUID();
        group = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,phone,password_hash,display_name) VALUES (?,?,?,'s2 owner')",
                owner, "+8" + Long.toString(System.nanoTime()).substring(3), "x".repeat(60));
        jdbc.update("INSERT INTO conversations(id,type,title,created_by) VALUES (?,'GROUP','s2 group',?)", group, owner);
        jdbc.update("INSERT INTO conversation_members(conversation_id,user_id,role) VALUES (?,?,'ADMIN')", group, owner);
        executor = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void cleanup() throws Exception {
        release.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        reset(storage);
        jdbc.execute("DROP TRIGGER IF EXISTS s2_bind_reject ON users");
        jdbc.execute("DROP TRIGGER IF EXISTS s2_bind_reject ON conversations");
        jdbc.execute("DROP TRIGGER IF EXISTS s2_bind_reject ON message_attachments");
        jdbc.execute("DROP FUNCTION IF EXISTS s2_bind_reject_fn()");
        jdbc.update("DELETE FROM message_attachments WHERE owner_id=?", owner);
        jdbc.update("DELETE FROM conversations WHERE id=?", group);
        jdbc.update("DELETE FROM users WHERE id=?", owner);
        for (String dir : List.of("attachments", "avatars")) {
            for (StorageService.StoredFileInfo file : storage.list(dir)) storage.deleteIfExists(file.storageKey());
        }
    }

    private Object upload(PathKind kind) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "s2.png", "image/png", PNG);
        return switch (kind) {
            case ATTACHMENT -> attachments.upload(owner, file, null, null, null, null);
            case PERSONAL_AVATAR -> avatars.uploadAvatar(new JwtPrincipal(owner), List.of(file));
            case GROUP_AVATAR -> groups.setGroupAvatar(owner, group, file);
        };
    }

    private Future<Throwable> blockedUpload(PathKind kind) throws Exception {
        doAnswer(call -> {
            entered.countDown();
            if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("store gate timed out");
            StorageService.StoredFile file = (StorageService.StoredFile) call.callRealMethod();
            storedKey.set(file.storageKey());
            return file;
        }).when(storage).store(anyString(), any(), any(), any(byte[].class));
        Future<Throwable> result = executor.submit(() -> catchThrowable(() -> upload(kind)));
        assertThat(entered.await(10, TimeUnit.SECONDS)).as("store reached").isTrue();
        return result;
    }

    private Connection observer() throws SQLException {
        return DriverManager.getConnection(env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat_sol_s2"),
                env("IT_DB_USER", "nexachat"), env("IT_DB_PASSWORD", "nexachat"));
    }

    private int count(Connection c, String sql) throws SQLException {
        try (var s = c.createStatement(); var rows = s.executeQuery(sql)) { rows.next(); return rows.getInt(1); }
    }

    @ParameterizedTest
    @EnumSource(PathKind.class)
    void blockedStoreHoldsNoConnectionOrUserLock(PathKind kind) throws Exception {
        HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
        await().atMost(Duration.ofSeconds(5)).until(() -> pool.getHikariPoolMXBean().getActiveConnections() == 0);
        Future<Throwable> result = blockedUpload(kind);
        try (Connection c = observer()) {
            int active = pool.getHikariPoolMXBean().getActiveConnections();
            int transactions = count(c, "SELECT count(*) FROM pg_stat_activity WHERE application_name='nexachat-s2-it' AND xact_start IS NOT NULL");
            int locks = count(c, "SELECT count(*) FROM pg_locks l JOIN pg_stat_activity a USING(pid) WHERE a.application_name='nexachat-s2-it' AND l.relation='users'::regclass AND l.mode IN ('RowShareLock','RowExclusiveLock')");
            Throwable probe = catchThrowable(() -> {
                c.setAutoCommit(false);
                try (var s = c.prepareStatement("SELECT id FROM users WHERE id=? FOR UPDATE NOWAIT")) {
                    s.setObject(1, owner); s.executeQuery().close();
                } finally { c.rollback(); }
            });
            assertSoftly(s -> {
                s.assertThat(active).as("Hikari checked out during %s store", kind).isZero();
                s.assertThat(transactions).as("pg_stat_activity open transactions").isZero();
                s.assertThat(locks).as("pg_locks users write/row-share locks").isZero();
                s.assertThat(probe).as("users FOR UPDATE NOWAIT").isNull();
            });
        } finally { release.countDown(); }
        assertThat(result.get(10, TimeUnit.SECONDS)).isNull();
    }

    @ParameterizedTest
    @EnumSource(PathKind.class)
    void ownerDeletedDuringStoreRejectsBindAndDeletesObject(PathKind kind) throws Exception {
        Future<Throwable> result = blockedUpload(kind);
        try (Connection c = observer(); var s = c.createStatement()) {
            s.execute("SET lock_timeout='1s'");
            try (var update = c.prepareStatement("UPDATE users SET deleted_at=now() WHERE id=?")) {
                update.setObject(1, owner); assertThat(update.executeUpdate()).isOne();
            }
        } finally { release.countDown(); }
        assertThat(result.get(10, TimeUnit.SECONDS)).isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode()).isEqualTo(ApiErrorCode.USER_NOT_FOUND);
        assertCompensated();
    }

    @Test
    void removedAdminDuringStoreRejectsBindAndDeletesObject() throws Exception {
        Future<Throwable> result = blockedUpload(PathKind.GROUP_AVATAR);
        jdbc.update("UPDATE conversation_members SET role='MEMBER' WHERE conversation_id=? AND user_id=?", group, owner);
        release.countDown();
        assertThat(result.get(10, TimeUnit.SECONDS)).isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode()).isEqualTo(ApiErrorCode.NOT_ADMIN);
        assertCompensated();
    }

    @Test
    void removedMembershipDuringStoreRejectsBindAndDeletesObject() throws Exception {
        Future<Throwable> result = blockedUpload(PathKind.GROUP_AVATAR);
        jdbc.update("DELETE FROM conversation_members WHERE conversation_id=? AND user_id=?", group, owner);
        release.countDown();
        assertThat(result.get(10, TimeUnit.SECONDS)).isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode()).isEqualTo(ApiErrorCode.NOT_MEMBER);
        assertCompensated();
    }

    @ParameterizedTest
    @EnumSource(value = PathKind.class, names = {"PERSONAL_AVATAR", "GROUP_AVATAR"})
    void avatarRemovedBeforeReferenceLockIsNotBound(PathKind kind) throws Exception {
        // Models a sweep finishing before the bind acquires its advisory fence.
        doAnswer(call -> {
            StorageService.StoredFile file = (StorageService.StoredFile) call.callRealMethod();
            storedKey.set(file.storageKey());
            storage.deleteIfExists(file.storageKey());
            return file;
        }).when(storage).store(anyString(), any(), any(), any(byte[].class));
        assertThat(catchThrowable(() -> upload(kind))).isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode()).isEqualTo(ApiErrorCode.FILE_NOT_FOUND);
        assertThat(jdbc.queryForObject("SELECT avatar_url FROM users WHERE id=?", String.class, owner)).isNull();
        assertThat(jdbc.queryForObject("SELECT avatar_url FROM conversations WHERE id=?", String.class, group)).isNull();
    }

    private void rejectBind(PathKind kind) {
        jdbc.execute("CREATE FUNCTION s2_bind_reject_fn() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 's2 bind rejected'; END $$");
        String table = switch (kind) {
            case ATTACHMENT -> "message_attachments";
            case PERSONAL_AVATAR -> "users";
            case GROUP_AVATAR -> "conversations";
        };
        jdbc.execute("CREATE TRIGGER s2_bind_reject BEFORE INSERT OR UPDATE ON " + table
                + " FOR EACH ROW EXECUTE FUNCTION s2_bind_reject_fn()");
    }

    @ParameterizedTest
    @EnumSource(PathKind.class)
    void commitFailureCompensates(PathKind kind) throws Exception {
        rejectBind(kind);
        Future<Throwable> result = blockedUpload(kind);
        release.countDown();
        assertThat(result.get(10, TimeUnit.SECONDS)).hasStackTraceContaining("s2 bind rejected");
        assertCompensated();
    }

    @ParameterizedTest
    @EnumSource(PathKind.class)
    void cleanupFailureIsLoggedWithoutMaskingBindFailure(PathKind kind, CapturedOutput output) throws Exception {
        rejectBind(kind);
        doThrow(new IllegalStateException("s2 cleanup failed")).when(storage).deleteIfExists(anyString());
        Future<Throwable> result = blockedUpload(kind);
        release.countDown();
        assertThat(result.get(10, TimeUnit.SECONDS)).hasStackTraceContaining("s2 bind rejected")
                .isNotInstanceOf(IllegalStateException.class);
        assertThat(output.getOut()).contains("Could not delete stored file", "s2 cleanup failed");
        assertThat(Files.exists(ROOT.resolve(storedKey.get()))).isTrue();
    }

    @ParameterizedTest
    @EnumSource(PathKind.class)
    void crashAfterStoreBeforeBindLeavesInventoriableObject(PathKind kind) {
        // Fixture models process death: storage returns, no Java compensation or DB bind runs.
        String dir = kind == PathKind.ATTACHMENT ? "attachments" : "avatars";
        StorageService.StoredFile file = storage.store(dir, "crash.png", "image/png", PNG);
        assertThat(storage.list(dir)).extracting(StorageService.StoredFileInfo::storageKey).contains(file.storageKey());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM message_attachments WHERE storage_key=?", Long.class, file.storageKey())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE avatar_url=?", Long.class, storage.resolveUrl(file.storageKey()))).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM conversations WHERE avatar_url=?", Long.class, storage.resolveUrl(file.storageKey()))).isZero();
    }

    @ParameterizedTest
    @EnumSource(PathKind.class)
    void callerTransactionIsSuspendedWhileStoreRuns(PathKind kind) throws Exception {
        // The caller's transaction is suspended, so store has no active transaction.
        // A caller-owned suspended connection is outside the upload's own budget.
        doAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return call.callRealMethod();
        }).when(storage).store(anyString(), any(), any(), any(byte[].class));
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            try { upload(kind); } catch (Exception e) { throw new IllegalStateException(e); }
        });
    }

    private void assertCompensated() {
        assertThat(storedKey.get()).isNotNull();
        assertThat(Files.exists(ROOT.resolve(storedKey.get()))).isFalse();
        verify(storage).deleteIfExists(storedKey.get());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM message_attachments WHERE owner_id=?", Long.class, owner)).isZero();
        assertThat(jdbc.queryForObject("SELECT avatar_url FROM users WHERE id=?", String.class, owner)).isNull();
        assertThat(jdbc.queryForObject("SELECT avatar_url FROM conversations WHERE id=?", String.class, group)).isNull();
    }
}
