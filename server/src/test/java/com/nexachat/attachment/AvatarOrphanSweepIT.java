package com.nexachat.attachment;

import com.nexachat.ops.ScheduledLockIds;
import com.nexachat.conversation.GroupService;
import com.nexachat.conversation.dto.PatchGroupRequest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Populated-PostgreSQL coverage for the opt-in orphaned-avatar sweep.
 *
 * <p>Files live under a per-run temporary storage root; rows live in the
 * private IT database selected by {@code IT_DB_URL}. The sweep is invoked
 * synchronously so every assertion is deterministic.</p>
 */
@SpringBootTest
@ActiveProfiles("it")
@TestPropertySource(properties = "nexachat.storage.root=${java.io.tmpdir}/nexachat-avatar-sweep-it")
class AvatarOrphanSweepIT {

    private static final byte[] PNG_BYTES = new byte[] {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52
    };

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat_test"));
        registry.add("spring.datasource.username", () -> env("IT_DB_USER", "nexachat"));
        registry.add("spring.datasource.password", () -> env("IT_DB_PASSWORD", "nexachat"));
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @Autowired
    private AvatarOrphanSweepTask sweepTask;
    @Autowired
    private AvatarSweepProperties properties;
    @Autowired
    private StorageService storageService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private MeterRegistry meterRegistry;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private GroupService groupService;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID firstUserId;
    private UUID secondUserId;
    private UUID conversationId;
    private final List<String> storedKeys = new ArrayList<>();

    @BeforeEach
    void createBaseRows() {
        firstUserId = insertUser("first");
        secondUserId = insertUser("second");
        conversationId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO conversations (id, type, title, created_by)
                VALUES (?, 'GROUP', 'avatar sweep fixture', ?)
                """, conversationId, firstUserId);
        jdbcTemplate.update("""
                INSERT INTO conversation_members (conversation_id, user_id, role)
                VALUES (?, ?, 'ADMIN'), (?, ?, 'MEMBER')
                """, conversationId, firstUserId, conversationId, secondUserId);
        properties.setEnabled(false);
        properties.setDryRun(true);
        properties.setBatchSize(100);
        properties.setMinAgeHours(24);
    }

    @AfterEach
    void removeSyntheticRowsAndFiles() throws Exception {
        jdbcTemplate.update("UPDATE users SET avatar_url = NULL WHERE id IN (?, ?)",
                firstUserId, secondUserId);
        jdbcTemplate.update("UPDATE conversations SET avatar_url = NULL WHERE id = ?",
                conversationId);
        jdbcTemplate.update("DELETE FROM conversations WHERE id = ?", conversationId);
        jdbcTemplate.update("DELETE FROM users WHERE id IN (?, ?)", firstUserId, secondUserId);
        for (String key : storedKeys) {
            Files.deleteIfExists(storagePath(key));
        }
        storedKeys.clear();
    }

    @Test
    void disabledByDefaultDoesNothing() throws Exception {
        String orphan = storeOldAvatar("disabled.png", Duration.ofDays(2));

        assertThat(sweepTask.runOnce()).isEmpty();
        assertThat(storagePath(orphan)).exists();
    }

    @Test
    void dryRunCountsOldUnreferencedAvatarsWithoutDeleting() throws Exception {
        properties.setEnabled(true);
        properties.setDryRun(true);
        String oldOrphan = storeOldAvatar("orphan.png", Duration.ofDays(2));
        String youngOrphan = storeOldAvatar("young.png", Duration.ofHours(1));
        String userReferenced = storeOldAvatar("user.png", Duration.ofDays(2));
        String groupReferenced = storeOldAvatar("group.png", Duration.ofDays(2));
        String foreign = storeOldFile("keep-me.txt", Duration.ofDays(2));
        jdbcTemplate.update("UPDATE users SET avatar_url = ? WHERE id = ?",
                "/api/v1/files/" + userReferenced, firstUserId);
        jdbcTemplate.update("UPDATE conversations SET avatar_url = ? WHERE id = ?",
                "/api/v1/files/" + groupReferenced + "?v=1#frag", conversationId);
        double observedBefore = counter("nexachat.avatar.sweep.files.observed");

        AvatarOrphanSweepTask.SweepResult result = sweepTask.runOnce().orElseThrow();

        assertThat(result.dryRun()).isTrue();
        assertThat(result.observed()).isEqualTo(1);
        assertThat(result.deleted()).isZero();
        assertThat(result.keptReferenced()).isEqualTo(2);
        assertThat(result.keptRecent()).isEqualTo(1);
        assertThat(result.ignored()).isEqualTo(1);
        assertThat(result.errors()).isZero();
        assertThat(storagePath(oldOrphan)).exists();
        assertThat(storagePath(youngOrphan)).exists();
        assertThat(storagePath(userReferenced)).exists();
        assertThat(storagePath(groupReferenced)).exists();
        assertThat(storagePath(foreign)).exists();
        assertThat(counter("nexachat.avatar.sweep.files.observed"))
                .isEqualTo(observedBefore + 1);
    }

    @Test
    void deletesOnlyOldUnreferencedServerGeneratedAvatars() throws Exception {
        properties.setEnabled(true);
        properties.setDryRun(false);
        String oldOrphan = storeOldAvatar("orphan.png", Duration.ofDays(2));
        String youngOrphan = storeOldAvatar("young.png", Duration.ofHours(1));
        String userReferenced = storeOldAvatar("user.png", Duration.ofDays(2));
        String groupReferenced = storeOldAvatar("group.png", Duration.ofDays(2));
        String foreign = storeOldFile("keep-me.txt", Duration.ofDays(2));
        jdbcTemplate.update("UPDATE users SET avatar_url = ? WHERE id = ?",
                "/api/v1/files/" + userReferenced, firstUserId);
        jdbcTemplate.update("UPDATE conversations SET avatar_url = ? WHERE id = ?",
                "/api/v1/files/" + groupReferenced, conversationId);
        double deletedBefore = counter("nexachat.avatar.sweep.files.deleted");

        AvatarOrphanSweepTask.SweepResult result = sweepTask.runOnce().orElseThrow();

        assertThat(result.dryRun()).isFalse();
        assertThat(result.observed()).isEqualTo(1);
        assertThat(result.deleted()).isEqualTo(1);
        assertThat(storagePath(oldOrphan)).doesNotExist();
        assertThat(storagePath(youngOrphan)).exists();
        assertThat(storagePath(userReferenced)).exists();
        assertThat(storagePath(groupReferenced)).exists();
        assertThat(storagePath(foreign)).exists();
        assertThat(counter("nexachat.avatar.sweep.files.deleted"))
                .isEqualTo(deletedBefore + 1);
    }

    @Test
    void keepsAbsoluteAndEncodedLegacyReferences() throws Exception {
        properties.setEnabled(true);
        properties.setDryRun(false);
        String referenced = storeOldAvatar("legacy.png", Duration.ofDays(2));
        // The first character of the UUID is encoded; URI path normalization
        // must still recognize the reference.
        String key = referenced.substring("avatars/".length());
        String encodedKey = "avatars/%" + Integer.toHexString(key.charAt(0)) + key.substring(1);
        jdbcTemplate.update("UPDATE conversations SET avatar_url = ? WHERE id = ?",
                "https://nexachat.example/api/v1/files/" + encodedKey + "?v=legacy",
                conversationId);

        AvatarOrphanSweepTask.SweepResult result = sweepTask.runOnce().orElseThrow();

        assertThat(result.deleted()).isZero();
        assertThat(result.keptReferenced()).isEqualTo(1);
        assertThat(storagePath(referenced)).exists();
    }

    @Test
    void boundedBatchDeletesAtMostBatchSizeAndContinuesNextRun() throws Exception {
        properties.setEnabled(true);
        properties.setDryRun(false);
        properties.setBatchSize(2);
        String oldest = storeOldAvatar("oldest.png", Duration.ofDays(4));
        String middle = storeOldAvatar("middle.png", Duration.ofDays(3));
        String newest = storeOldAvatar("newest.png", Duration.ofDays(2));

        AvatarOrphanSweepTask.SweepResult first = sweepTask.runOnce().orElseThrow();

        assertThat(first.observed()).isEqualTo(2);
        assertThat(first.deleted()).isEqualTo(2);
        assertThat(storagePath(oldest)).doesNotExist();
        assertThat(storagePath(middle)).doesNotExist();
        assertThat(storagePath(newest)).exists();

        AvatarOrphanSweepTask.SweepResult second = sweepTask.runOnce().orElseThrow();

        assertThat(second.observed()).isEqualTo(1);
        assertThat(second.deleted()).isEqualTo(1);
        assertThat(storagePath(newest)).doesNotExist();
    }

    @Test
    void advisoryLockPreventsAConcurrentRun() throws Exception {
        properties.setEnabled(true);
        properties.setDryRun(false);
        String orphan = storeOldAvatar("locked.png", Duration.ofDays(2));

        try (Connection connection = dataSource.getConnection();
             PreparedStatement acquire = connection.prepareStatement("SELECT pg_advisory_lock(?)")) {
            acquire.setLong(1, ScheduledLockIds.AVATAR_ORPHAN_SWEEP);
            acquire.execute();

            assertThat(sweepTask.runOnce()).isEmpty();
            assertThat(storagePath(orphan)).exists();

            try (PreparedStatement release = connection.prepareStatement(
                    "SELECT pg_advisory_unlock(?)")) {
                release.setLong(1, ScheduledLockIds.AVATAR_ORPHAN_SWEEP);
                release.execute();
            }
        }

        assertThat(sweepTask.runOnce().orElseThrow().deleted()).isEqualTo(1);
        assertThat(storagePath(orphan)).doesNotExist();
    }

    @Test
    void concurrentGroupAvatarReferenceIsSerializedWithSweep() throws Exception {
        properties.setEnabled(true);
        properties.setDryRun(false);
        String oldOrphan = storeOldAvatar("concurrent-group.png", Duration.ofDays(2));
        CountDownLatch referenceWritten = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> update = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                groupService.updateGroup(firstUserId, conversationId,
                        new PatchGroupRequest(null, "/api/v1/files/" + oldOrphan));
                referenceWritten.countDown();
                try {
                    if (!allowCommit.await(10, TimeUnit.SECONDS)) {
                        throw new AssertionError("Timed out waiting to commit the group avatar reference");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return null;
            }));

            assertThat(referenceWritten.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(sweepTask.runOnce()).isEmpty();
            assertThat(storagePath(oldOrphan)).exists();

            allowCommit.countDown();
            update.get(10, TimeUnit.SECONDS);
        } finally {
            allowCommit.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        AvatarOrphanSweepTask.SweepResult result = sweepTask.runOnce().orElseThrow();
        assertThat(result.deleted()).isZero();
        assertThat(result.keptReferenced()).isEqualTo(1);
        assertThat(storagePath(oldOrphan)).exists();
    }

    private String storeOldAvatar(String filename, Duration age) throws Exception {
        StorageService.StoredFile stored = storageService.store(
                "avatars", filename, "image/png", PNG_BYTES);
        storedKeys.add(stored.storageKey());
        Files.setLastModifiedTime(storagePath(stored.storageKey()),
                FileTime.from(Instant.now().minus(age)));
        return stored.storageKey();
    }

    private String storeOldFile(String filename, Duration age) throws Exception {
        Path file = storagePath("avatars/" + filename);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "hand-placed file");
        storedKeys.add("avatars/" + filename);
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(age)));
        return "avatars/" + filename;
    }

    private UUID insertUser(String label) {
        UUID id = UUID.randomUUID();
        String phoneTail = Long.toString(Math.floorMod(
                UUID.randomUUID().getMostSignificantBits(), 10_000_000_000L));
        phoneTail = "0".repeat(10 - phoneTail.length()) + phoneTail;
        jdbcTemplate.update("""
                INSERT INTO users (id, phone, password_hash, display_name)
                VALUES (?, ?, ?, ?)
                """, id, "+849" + phoneTail, "x".repeat(60), "avatar-sweep-" + label);
        return id;
    }

    private double counter(String name) {
        var counter = meterRegistry.find(name).counter();
        return counter == null ? 0 : counter.count();
    }

    private Path storagePath(String key) {
        return Path.of(System.getProperty("java.io.tmpdir"), "nexachat-avatar-sweep-it", key);
    }
}
