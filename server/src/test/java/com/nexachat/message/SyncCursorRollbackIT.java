package com.nexachat.message;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.testutil.PostgresTestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Executable local forward-recovery drill. It starts a packaged V32 server,
 * switches to the frozen V31 server artifact, and rebuilds cursor generations
 * transactionally on one uniquely named disposable PostgreSQL database.
 */
class SyncCursorRollbackIT {

    private static final String V31_SOURCE_SHA = "9216773920fc59684564170eebde13b33ca7fbc1";
    private static final int PAGE_LIMIT = 2;
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    @Test
    void frozenV31RollbackRebuildIsForwardAndAtomic() throws Exception {
        Path currentJar = currentServerArtifact();
        Path v31Jar = Path.of(requiredEnvironment("V31_SERVER_JAR")).toAbsolutePath().normalize();
        String sourceSha = requiredEnvironment("V31_SOURCE_SHA");
        assertThat(sourceSha).isEqualTo(V31_SOURCE_SHA);
        assertThat(Files.isRegularFile(v31Jar)).isTrue();
        assertThat(Files.isRegularFile(currentJar)).isTrue();
        String currentJarSha = sha256(currentJar);
        String v31JarSha = sha256(v31Jar);
        System.out.printf("SYNC_CURSOR_ROLLBACK_ARTIFACT current_jar_sha256=%s v31_source_sha=%s v31_jar_sha256=%s%n",
                currentJarSha, sourceSha, v31JarSha);

        try (PostgresTestDatabase database = PostgresTestDatabase.create("sync-cursor-rollback")) {
            JdbcTemplate jdbc = new JdbcTemplate(database.dataSource());
            Fixture fixture;

            // Step 1: boot the packaged V32 runtime, write through public APIs,
            // then retain a real cursor/G pair and a complete client state.
            try (RuntimeServer v32 = startServer(currentJar, database, true, false)) {
                assertFlywayReady(v32);
                assertSchemaV32(jdbc);
                TestUser alice = register(v32, "Rollback Alice");
                TestUser bob = register(v32, "Rollback Bob");
                TestUser cara = register(v32, "Rollback Cara");
                UUID conversationId = createGroup(v32, alice, bob);

                UUID editedId = send(v32, alice, conversationId, "seed-before-rollback");
                UUID recalledId = send(v32, alice, conversationId, "recalled-before-rollback");
                UUID v31TargetId = send(v32, alice, conversationId, "v31-recall-target");
                edit(v32, alice, conversationId, editedId, "seed-edited-before-rollback");
                recall(v32, alice, conversationId, recalledId);

                Drain initial = drainV2(v32, alice, conversationId, 0L, null);
                assertThat(initial.lastHasMore()).isFalse();
                assertThat(initial.states()).isEqualTo(databaseMessageStates(jdbc, conversationId));
                assertThat(initial.states()).containsKeys(editedId, recalledId, v31TargetId);
                JsonNode delayedOldGenerationRow = initial.messages().get(editedId);
                assertThat(delayedOldGenerationRow).isNotNull();
                fixture = new Fixture(alice, bob, cara, conversationId, editedId,
                        recalledId, v31TargetId, initial.cursor(), initial.generation(),
                        delayedOldGenerationRow.deepCopy());
            }

            // Step 2: the configured switch is the production property. V1
            // requests work, while a V2-shaped request has no cursor envelope.
            try (RuntimeServer disabled = startServer(currentJar, database, false, false)) {
                assertFlywayReady(disabled);
                JsonNode v1 = syncV1(disabled, fixture.alice(), fixture.conversationId());
                JsonNode v2Fallback = syncV2(disabled, fixture.alice(), fixture.conversationId(),
                        fixture.savedCursor(), fixture.savedGeneration());
                assertLegacyShape(v1);
                assertLegacyShape(v2Fallback);
                assertThat(v1.path("deltas").get(0).path("newMessages").size()).isGreaterThan(0);
            }

            // Step 3: the frozen V31 binary starts on V32 with Flyway's explicit
            // ignore-unknown-future-migration setting and performs real writes.
            long priorHigh = fixture.savedCursor();
            long priorGeneration = fixture.savedGeneration();
            long editedRevision = messageRevision(jdbc, fixture.editedId());
            long recalledTargetRevision = messageRevision(jdbc, fixture.v31TargetId());
            UUID legacyInsertId;
            try (RuntimeServer v31 = startServer(v31Jar, database, false, true)) {
                assertFlywayReady(v31);
                JsonNode legacyEdit = edit(v31, fixture.alice(), fixture.conversationId(),
                        fixture.editedId(), "edited-by-v31");
                assertThat(legacyEdit.has("changeVersion")).isFalse();
                assertThat(legacyEdit.has("generation")).isFalse();
                recall(v31, fixture.alice(), fixture.conversationId(), fixture.v31TargetId());
                legacyInsertId = send(v31, fixture.alice(), fixture.conversationId(), "inserted-by-v31");
                addMember(v31, fixture.alice(), fixture.conversationId(), fixture.cara().id());

                JsonNode legacySync = syncV1(v31, fixture.alice(), fixture.conversationId());
                assertLegacyShape(legacySync);
                assertThat(legacySync.path("deltas").get(0).path("newMessages").size())
                        .isGreaterThanOrEqualTo(1);
            }
            assertThat(messageRevision(jdbc, fixture.editedId())).isEqualTo(editedRevision);
            assertThat(messageRevision(jdbc, fixture.v31TargetId())).isEqualTo(recalledTargetRevision);
            assertThat(messageRevision(jdbc, legacyInsertId)).isZero();
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM messages
                    WHERE conversation_id = ? AND type = 'SYSTEM' AND change_version = 0
                    """, Integer.class, fixture.conversationId())).isEqualTo(1);
            assertThat(conversationRevision(jdbc, fixture.conversationId()))
                    .isEqualTo(new ConversationRevision(priorHigh, priorGeneration));

            // Step 4: rebuild every row revision, including V31 default-zero
            // rows, then recompute H/G under one C-then-M locked transaction.
            DbSnapshot beforeFirstRebuild = databaseSnapshot(jdbc);
            long firstRebuildMillis = rebuildCursors(database, false);
            DbSnapshot afterFirstRebuild = databaseSnapshot(jdbc);
            assertInventoryUnchanged(beforeFirstRebuild, afterFirstRebuild);
            assertDeterministicRevisions(jdbc);
            long generationOne = afterFirstRebuild.conversations()
                    .get(fixture.conversationId()).generation();
            long cursorOne = afterFirstRebuild.conversations()
                    .get(fixture.conversationId()).highWater();
            assertThat(generationOne).isEqualTo(priorGeneration + 1);
            assertThat(cursorOne).isEqualTo(messageCount(jdbc, fixture.conversationId()));
            System.out.printf("SYNC_CURSOR_REBUILD run=1 pg=%s rows=%d conversations=%d elapsed_ms=%d old_generation=%d new_generation=%d%n",
                    postgresVersion(jdbc), afterFirstRebuild.messageVersions().size(),
                    afterFirstRebuild.conversations().size(), firstRebuildMillis,
                    priorGeneration, generationOne);

            // Step 5: restart V32 with the switch off and verify V32 data first;
            // then enable it and prove old-G reset, full reconciliation and the
            // ahead-cursor reset. A delayed old-generation row is discarded.
            try (RuntimeServer disabled = startServer(currentJar, database, false, false)) {
                assertFlywayReady(disabled);
                assertSchemaV32(jdbc);
                assertLegacyShape(syncV1(disabled, fixture.alice(), fixture.conversationId()));
                assertLegacyShape(syncV2(disabled, fixture.alice(), fixture.conversationId(),
                        fixture.savedCursor(), fixture.savedGeneration()));
            }

            Drain generationOneDrain;
            JsonNode generationOneDelayedRow;
            try (RuntimeServer enabled = startServer(currentJar, database, true, false)) {
                JsonNode oldCursorReset = syncV2(enabled, fixture.alice(), fixture.conversationId(),
                        fixture.savedCursor(), fixture.savedGeneration());
                assertResetOnly(oldCursorReset, fixture.conversationId(), cursorOne, generationOne);

                generationOneDrain = drainV2(enabled, fixture.alice(), fixture.conversationId(), 0L, null);
                assertThat(generationOneDrain.states()).isEqualTo(databaseMessageStates(jdbc, fixture.conversationId()));
                assertThat(generationOneDrain.lastHasMore()).isFalse();
                JsonNode aheadReset = syncV2(enabled, fixture.alice(), fixture.conversationId(),
                        cursorOne + 100, generationOne);
                assertResetOnly(aheadReset, fixture.conversationId(), cursorOne, generationOne);

                Map<UUID, MessageState> clientFixture = new LinkedHashMap<>(generationOneDrain.states());
                Map<UUID, MessageState> beforeDelayedResponse = new LinkedHashMap<>(clientFixture);
                assertThat(applyGenerationChecked(fixture.delayedOldGenerationRow(), generationOne,
                        clientFixture)).isFalse();
                assertThat(clientFixture).isEqualTo(beforeDelayedResponse);
                generationOneDelayedRow = generationOneDrain.messages().get(fixture.editedId()).deepCopy();
            }

            // Step 6: inject a failure after SQL writes but before COMMIT. Verify
            // complete rollback and switch-off fallback, then repeat the rebuild
            // successfully and reconcile from the prior generation.
            DbSnapshot beforeInjectedFailure = databaseSnapshot(jdbc);
            assertThatThrownBy(() -> rebuildCursors(database, true))
                    .isInstanceOf(InjectedRebuildFailure.class);
            assertThat(databaseSnapshot(jdbc)).isEqualTo(beforeInjectedFailure);

            try (RuntimeServer stillDisabled = startServer(currentJar, database, false, false)) {
                assertFlywayReady(stillDisabled);
                assertLegacyShape(syncV1(stillDisabled, fixture.alice(), fixture.conversationId()));
                assertLegacyShape(syncV2(stillDisabled, fixture.alice(), fixture.conversationId(),
                        generationOneDrain.cursor(), generationOneDrain.generation()));
            }

            long secondRebuildMillis = rebuildCursors(database, false);
            DbSnapshot afterSecondRebuild = databaseSnapshot(jdbc);
            assertInventoryUnchanged(beforeInjectedFailure, afterSecondRebuild);
            assertDeterministicRevisions(jdbc);
            long generationTwo = afterSecondRebuild.conversations()
                    .get(fixture.conversationId()).generation();
            long cursorTwo = afterSecondRebuild.conversations()
                    .get(fixture.conversationId()).highWater();
            assertThat(generationTwo).isEqualTo(generationOne + 1);
            System.out.printf("SYNC_CURSOR_REBUILD run=2 pg=%s rows=%d conversations=%d elapsed_ms=%d old_generation=%d new_generation=%d failure_injection=rolled_back%n",
                    postgresVersion(jdbc), afterSecondRebuild.messageVersions().size(),
                    afterSecondRebuild.conversations().size(), secondRebuildMillis,
                    generationOne, generationTwo);

            try (RuntimeServer recovered = startServer(currentJar, database, true, false)) {
                assertFlywayReady(recovered);
                assertResetOnly(syncV2(recovered, fixture.alice(), fixture.conversationId(),
                        generationOneDrain.cursor(), generationOne),
                        fixture.conversationId(), cursorTwo, generationTwo);
                assertResetOnly(syncV2(recovered, fixture.alice(), fixture.conversationId(),
                        fixture.savedCursor(), fixture.savedGeneration()),
                        fixture.conversationId(), cursorTwo, generationTwo);

                Drain finalDrain = drainV2(recovered, fixture.alice(), fixture.conversationId(), 0L, null);
                assertThat(finalDrain.states()).isEqualTo(databaseMessageStates(jdbc, fixture.conversationId()));
                int expectedConversationMessages = Math.toIntExact(afterSecondRebuild.messageVersions().keySet().stream()
                        .filter(id -> afterSecondRebuild.inventory().get(id).conversationId()
                                .equals(fixture.conversationId())).count());
                assertThat(finalDrain.states().size()).isEqualTo(expectedConversationMessages);
                assertThat(finalDrain.rowCount()).isEqualTo(finalDrain.states().size());

                Map<UUID, MessageState> clientFixture = new LinkedHashMap<>(finalDrain.states());
                Map<UUID, MessageState> beforeDelayedResponse = new LinkedHashMap<>(clientFixture);
                assertThat(applyGenerationChecked(generationOneDelayedRow, generationTwo,
                        clientFixture)).isFalse();
                assertThat(clientFixture).isEqualTo(beforeDelayedResponse);
                assertInventoryUnchanged(beforeInjectedFailure, databaseSnapshot(jdbc));
            }
        }
    }

    private static RuntimeServer startServer(Path jar,
                                             PostgresTestDatabase database,
                                             boolean changeCursorsEnabled,
                                             boolean frozenV31) throws Exception {
        assertThat(Files.isRegularFile(jar)).as("server artifact exists").isTrue();
        int port = availablePort();
        Path runtimeDirectory = Files.createTempDirectory("nexachat-sync-cursor-runtime-");
        Path logFile = runtimeDirectory.resolve("server.log");
        Path storageRoot = runtimeDirectory.resolve("uploads");
        List<String> command = new ArrayList<>(List.of(
                javaExecutable(), "-jar", jar.toAbsolutePath().toString(),
                "--server.port=" + port,
                "--nexachat.sync.change-cursors-enabled=" + changeCursorsEnabled,
                "--spring.flyway.validate-on-migrate=true"));
        if (frozenV31) {
            // V31 cannot resolve the later V32 history row. Ignore only
            // migrations newer than this binary; startup validation remains on.
            command.add("--spring.flyway.ignore-migration-patterns=*:future");
        }

        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(runtimeDirectory.toFile())
                .redirectErrorStream(true)
                .redirectOutput(logFile.toFile());
        Map<String, String> environment = builder.environment();
        environment.put("DATABASE_URL", database.jdbcUrl());
        environment.put("DATABASE_USER", database.username());
        environment.put("DATABASE_PASSWORD", database.password());
        environment.put("JWT_SECRET", "local-sync-cursor-rollback-secret-0123456789abcdef");
        environment.put("STORAGE_ROOT", storageRoot.toString());
        environment.put("FCM_ENABLED", "false");
        environment.put("NEXACHAT_SYNC_CHANGE_CURSORS_ENABLED", Boolean.toString(changeCursorsEnabled));
        environment.remove("FCM_PROJECT_ID");
        environment.remove("FCM_SERVICE_ACCOUNT_JSON");

        Process process = builder.start();
        RuntimeServer runtime = new RuntimeServer(process, port, runtimeDirectory, logFile,
                changeCursorsEnabled, frozenV31);
        long deadline = System.nanoTime() + STARTUP_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new IllegalStateException("server exited before readiness; inspect the temporary runtime log");
            }
            try {
                HttpResponse<String> response = rawRequest(runtime, "GET", "/actuator/health", null, null);
                if (response.statusCode() == 200 && JSON.readTree(response.body()).path("status").asText().equals("UP")) {
                    return runtime;
                }
            } catch (IOException | InterruptedException | RuntimeException notReady) {
                if (notReady instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    throw notReady;
                }
            }
            Thread.sleep(250);
        }
        runtime.close();
        throw new IllegalStateException("server did not become healthy before the local startup deadline");
    }

    private static void assertFlywayReady(RuntimeServer runtime) throws Exception {
        JsonNode health = JSON.readTree(rawRequest(runtime, "GET", "/actuator/health", null, null).body());
        assertThat(health.path("status").asText()).isEqualTo("UP");
        if (health.path("components").has("flyway")) {
            assertThat(health.path("components").path("flyway").path("status").asText()).isEqualTo("UP");
        }
    }

    private static TestUser register(RuntimeServer runtime, String displayName) throws Exception {
        String phone = "+8497" + (1 + ThreadLocalRandom.current().nextInt(9))
                + String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
        JsonNode response = requestJson(runtime, "POST", "/api/v1/auth/register", null,
                Map.of("phone", phone, "password", "secret123", "displayName", displayName), 201);
        return new TestUser(UUID.fromString(response.get("user").get("id").asText()),
                response.get("accessToken").asText());
    }

    private static UUID createGroup(RuntimeServer runtime, TestUser admin, TestUser member) throws Exception {
        JsonNode response = requestJson(runtime, "POST", "/api/v1/conversations/group", admin,
                Map.of("title", "sync-rollback-" + UUID.randomUUID(),
                        "memberIds", List.of(member.id().toString())), 201);
        return UUID.fromString(response.get("id").asText());
    }

    private static UUID send(RuntimeServer runtime, TestUser user, UUID conversationId, String content)
            throws Exception {
        JsonNode response = requestJson(runtime, "POST",
                "/api/v1/conversations/" + conversationId + "/messages", user,
                Map.of("clientMessageId", UUID.randomUUID().toString(),
                        "type", "TEXT", "content", content, "attachmentIds", List.of()), 201);
        return UUID.fromString(response.get("id").asText());
    }

    private static JsonNode edit(RuntimeServer runtime, TestUser user, UUID conversationId,
                                 UUID messageId, String content) throws Exception {
        return requestJson(runtime, "PATCH", "/api/v1/conversations/" + conversationId
                        + "/messages/" + messageId, user, Map.of("content", content), 200);
    }

    private static void recall(RuntimeServer runtime, TestUser user, UUID conversationId, UUID messageId)
            throws Exception {
        request(runtime, "DELETE", "/api/v1/conversations/" + conversationId
                + "/messages/" + messageId, user, null, 204);
    }

    private static void addMember(RuntimeServer runtime, TestUser admin,
                                  UUID conversationId, UUID userId) throws Exception {
        request(runtime, "POST", "/api/v1/conversations/" + conversationId + "/members",
                admin, Map.of("userIds", List.of(userId.toString())), 204);
    }

    private static JsonNode syncV1(RuntimeServer runtime, TestUser user, UUID conversationId)
            throws Exception {
        Map<String, Object> watermark = new LinkedHashMap<>();
        watermark.put("conversationId", conversationId.toString());
        watermark.put("lastSeq", 0L);
        return requestJson(runtime, "POST", "/api/v1/sync", user,
                Map.of("watermarks", List.of(watermark), "limitPerConv", 50), 200);
    }

    private static JsonNode syncV2(RuntimeServer runtime, TestUser user, UUID conversationId,
                                   long cursor, Long generation) throws Exception {
        Map<String, Object> watermark = new LinkedHashMap<>();
        watermark.put("conversationId", conversationId.toString());
        watermark.put("lastSeq", 0L);
        watermark.put("changeCursor", cursor);
        if (generation != null) {
            watermark.put("generation", generation);
        }
        return requestJson(runtime, "POST", "/api/v1/sync", user,
                Map.of("syncVersion", 2, "watermarks", List.of(watermark),
                        "limitPerConv", PAGE_LIMIT), 200);
    }

    private static Drain drainV2(RuntimeServer runtime, TestUser user, UUID conversationId,
                                long initialCursor, Long initialGeneration) throws Exception {
        long cursor = initialCursor;
        Long generation = initialGeneration;
        int rowCount = 0;
        int pages = 0;
        boolean hasMore;
        Map<UUID, MessageState> states = new LinkedHashMap<>();
        Map<UUID, JsonNode> messages = new LinkedHashMap<>();
        Set<UUID> seen = new LinkedHashSet<>();
        while (pages < 100) {
            JsonNode response = syncV2(runtime, user, conversationId, cursor, generation);
            assertThat(response.path("syncVersion").asInt()).isEqualTo(2);
            JsonNode delta = singleDelta(response);
            assertThat(delta.path("reset").asBoolean()).isFalse();
            JsonNode changes = delta.path("changes");
            assertThat(changes.size()).isLessThanOrEqualTo(PAGE_LIMIT);
            long upperBound = delta.path("changeUpperBound").asLong();
            long nextCursor = delta.path("changeCursor").asLong();
            long responseGeneration = delta.path("generation").asLong();
            assertThat(responseGeneration).isPositive();
            for (JsonNode message : changes) {
                UUID id = UUID.fromString(message.path("id").asText());
                assertThat(seen.add(id)).as("each message id appears once during a complete drain").isTrue();
                assertThat(message.path("changeVersion").asLong()).isGreaterThan(cursor).isLessThanOrEqualTo(upperBound);
                assertThat(message.path("generation").asLong()).isEqualTo(responseGeneration);
                states.put(id, stateFromMessage(message));
                messages.put(id, message.deepCopy());
                rowCount++;
            }
            pages++;
            hasMore = delta.path("changeHasMore").asBoolean();
            assertThat(delta.hasNonNull("reactionCursor")).isFalse();
            assertThat(delta.path("hasMore").asBoolean()).isEqualTo(hasMore);
            assertThat(delta.path("reactionsFullRefresh").asBoolean()).isFalse();
            if (hasMore) {
                assertThat(nextCursor).isGreaterThan(cursor);
                cursor = nextCursor;
                generation = responseGeneration;
            } else {
                assertThat(nextCursor).isEqualTo(upperBound);
                cursor = nextCursor;
                generation = responseGeneration;
                return new Drain(cursor, generation, rowCount, pages, false,
                        Map.copyOf(states), Map.copyOf(messages));
            }
        }
        throw new AssertionError("v2 message drain exceeded 100 pages");
    }

    private static MessageState stateFromMessage(JsonNode message) {
        boolean deleted = !message.path("deletedAt").isMissingNode() && !message.path("deletedAt").isNull();
        JsonNode content = message.path("content");
        return new MessageState(message.path("seq").asLong(),
                deleted || content.isNull() ? null : content.asText(), deleted);
    }

    private static boolean applyGenerationChecked(JsonNode message, long currentGeneration,
                                                  Map<UUID, MessageState> clientState) {
        if (message.path("generation").asLong(-1) != currentGeneration) {
            return false;
        }
        clientState.put(UUID.fromString(message.path("id").asText()), stateFromMessage(message));
        return true;
    }

    private static MessageState databaseMessageState(ResultSet row) throws SQLException {
        boolean deleted = row.getBoolean("deleted");
        return new MessageState(row.getLong("seq"), deleted ? null : row.getString("content"), deleted);
    }

    private static Map<UUID, MessageState> databaseMessageStates(JdbcTemplate jdbc, UUID conversationId) {
        return jdbc.query("""
                SELECT id, seq, content, (deleted_at IS NOT NULL) AS deleted
                FROM messages WHERE conversation_id = ? ORDER BY seq, id
                """, rs -> {
            Map<UUID, MessageState> states = new LinkedHashMap<>();
            while (rs.next()) {
                states.put((UUID) rs.getObject("id"), databaseMessageState(rs));
            }
            return Map.copyOf(states);
        }, conversationId);
    }

    private static DbSnapshot databaseSnapshot(JdbcTemplate jdbc) {
        Map<UUID, ConversationRevision> conversations = jdbc.query("""
                SELECT id, change_version, sync_generation FROM conversations ORDER BY id
                """, rs -> {
            Map<UUID, ConversationRevision> result = new LinkedHashMap<>();
            while (rs.next()) {
                result.put((UUID) rs.getObject("id"),
                        new ConversationRevision(rs.getLong("change_version"), rs.getLong("sync_generation")));
            }
            return Map.copyOf(result);
        });
        Map<UUID, Long> messageVersions = jdbc.query("""
                SELECT id, change_version FROM messages ORDER BY id
                """, rs -> {
            Map<UUID, Long> result = new LinkedHashMap<>();
            while (rs.next()) {
                result.put((UUID) rs.getObject("id"), rs.getLong("change_version"));
            }
            return Map.copyOf(result);
        });
        Map<UUID, MessageInventory> inventory = jdbc.query("""
                SELECT id, conversation_id, seq, sender_id, client_message_id, type, content,
                       created_at, edited_at, deleted_at
                FROM messages ORDER BY conversation_id, seq, id
                """, rs -> {
            Map<UUID, MessageInventory> result = new LinkedHashMap<>();
            while (rs.next()) {
                UUID id = (UUID) rs.getObject("id");
                result.put(id, new MessageInventory(id, (UUID) rs.getObject("conversation_id"),
                        rs.getLong("seq"), (UUID) rs.getObject("sender_id"),
                        (UUID) rs.getObject("client_message_id"), rs.getString("type"),
                        rs.getString("content"), timestamp(rs, "created_at"),
                        timestamp(rs, "edited_at"), timestamp(rs, "deleted_at")));
            }
            return Map.copyOf(result);
        });
        return new DbSnapshot(conversations, messageVersions, inventory);
    }

    private static Instant timestamp(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static ConversationRevision conversationRevision(JdbcTemplate jdbc, UUID conversationId) {
        return jdbc.queryForObject("""
                SELECT change_version, sync_generation FROM conversations WHERE id = ?
                """, (rs, rowNum) -> new ConversationRevision(
                rs.getLong("change_version"), rs.getLong("sync_generation")), conversationId);
    }

    private static long messageRevision(JdbcTemplate jdbc, UUID messageId) {
        return jdbc.queryForObject("SELECT change_version FROM messages WHERE id = ?", Long.class, messageId);
    }

    private static long messageCount(JdbcTemplate jdbc, UUID conversationId) {
        return jdbc.queryForObject("SELECT count(*) FROM messages WHERE conversation_id = ?",
                Long.class, conversationId);
    }

    private static void assertSchemaV32(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM flyway_schema_history WHERE version = '32' AND success
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'messages' AND column_name = 'change_version'
                """, Integer.class)).isEqualTo(1);
    }

    private static void assertInventoryUnchanged(DbSnapshot before, DbSnapshot after) {
        assertThat(after.inventory()).isEqualTo(before.inventory());
    }

    private static void assertDeterministicRevisions(JdbcTemplate jdbc) {
        Integer mismatchCount = jdbc.queryForObject("""
                SELECT count(*) FROM (
                    SELECT change_version,
                           row_number() OVER (PARTITION BY conversation_id ORDER BY seq, id) AS expected
                    FROM messages
                ) ranked
                WHERE change_version <> expected
                """, Integer.class);
        assertThat(mismatchCount).isZero();
        Integer highWaterMismatchCount = jdbc.queryForObject("""
                SELECT count(*) FROM conversations c
                WHERE c.change_version <> COALESCE((
                    SELECT max(m.change_version) FROM messages m WHERE m.conversation_id = c.id
                ), 0)
                """, Integer.class);
        assertThat(highWaterMismatchCount).isZero();
    }

    private static long rebuildCursors(PostgresTestDatabase database, boolean injectFailure) throws SQLException {
        long started = System.nanoTime();
        try (Connection connection = DriverManager.getConnection(
                database.jdbcUrl(), database.username(), database.password())) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET LOCAL lock_timeout = '10s'");
                // Maintenance follows the normal C -> M lock direction and a
                // deterministic order, holding both sets through COMMIT.
                drainLockQuery(statement, "SELECT id FROM conversations ORDER BY id FOR UPDATE");
                drainLockQuery(statement,
                        "SELECT id FROM messages ORDER BY conversation_id, seq, id FOR UPDATE");
                statement.executeUpdate("""
                        WITH ranked AS (
                            SELECT id,
                                   row_number() OVER (
                                       PARTITION BY conversation_id ORDER BY seq, id
                                   ) AS revision
                            FROM messages
                        )
                        UPDATE messages m
                        SET change_version = ranked.revision
                        FROM ranked
                        WHERE ranked.id = m.id
                        """);
                statement.executeUpdate("""
                        UPDATE conversations c
                        SET change_version = COALESCE((
                                SELECT max(m.change_version)
                                FROM messages m WHERE m.conversation_id = c.id
                            ), 0),
                            sync_generation = c.sync_generation + 1
                        """);
                if (injectFailure) {
                    throw new InjectedRebuildFailure();
                }
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        }
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private static void drainLockQuery(Statement statement, String sql) throws SQLException {
        try (ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                rows.getObject(1);
            }
        }
    }

    private static long postgresVersion(JdbcTemplate jdbc) {
        String version = jdbc.queryForObject("SHOW server_version_num", String.class);
        return Long.parseLong(version);
    }

    private static void assertLegacyShape(JsonNode response) {
        assertThat(response.has("deltas")).isTrue();
        assertThat(response.has("serverTime")).isTrue();
        assertThat(response.has("syncVersion")).isFalse();
        JsonNode delta = singleDelta(response);
        for (String field : List.of("conversationId", "newMessages", "editedMessages",
                "deletedMessageIds", "reactionUpdates", "maxSeq", "hasMore",
                "reactionsFullRefresh", "muted", "archived", "pinned")) {
            assertThat(delta.has(field)).as("legacy field %s is present", field).isTrue();
        }
        for (String field : List.of("changes", "changeCursor", "changeHasMore",
                "changeUpperBound", "reset", "generation")) {
            assertThat(delta.has(field)).as("v2 field %s is absent on fallback", field).isFalse();
        }
    }

    private static void assertResetOnly(JsonNode response, UUID conversationId,
                                        long upperBound, long generation) {
        assertThat(response.path("syncVersion").asInt()).isEqualTo(2);
        JsonNode delta = singleDelta(response);
        assertThat(UUID.fromString(delta.path("conversationId").asText())).isEqualTo(conversationId);
        assertThat(delta.path("reset").asBoolean()).isTrue();
        assertThat(delta.path("changes").size()).isZero();
        assertThat(delta.path("changeCursor").asLong()).isZero();
        assertThat(delta.path("changeUpperBound").asLong()).isEqualTo(upperBound);
        assertThat(delta.path("changeHasMore").asBoolean()).isFalse();
        assertThat(delta.path("generation").asLong()).isEqualTo(generation);
        assertThat(delta.path("reactionUpdates").size()).isZero();
        assertThat(delta.hasNonNull("reactionCursor")).isFalse();
        assertThat(delta.path("hasMore").asBoolean()).isFalse();
        assertThat(delta.path("reactionsFullRefresh").asBoolean()).isFalse();
        assertThat(delta.path("newMessages").size()).isZero();
        assertThat(delta.path("editedMessages").size()).isZero();
        assertThat(delta.path("deletedMessageIds").size()).isZero();
    }

    private static JsonNode singleDelta(JsonNode response) {
        assertThat(response.path("deltas").size()).isEqualTo(1);
        return response.path("deltas").get(0);
    }

    private static JsonNode requestJson(RuntimeServer runtime, String method, String path,
                                        TestUser user, Object body, int expectedStatus) throws Exception {
        HttpResponse<String> response = request(runtime, method, path, user, body, expectedStatus);
        return JSON.readTree(response.body());
    }

    private static HttpResponse<String> request(RuntimeServer runtime, String method, String path,
                                                TestUser user, Object body, int expectedStatus) throws Exception {
        String encodedBody = body == null ? null : JSON.writeValueAsString(body);
        HttpResponse<String> response = rawRequest(runtime, method, path, user, encodedBody);
        assertThat(response.statusCode()).as("HTTP %s %s", method, path).isEqualTo(expectedStatus);
        return response;
    }

    private static HttpResponse<String> rawRequest(RuntimeServer runtime, String method, String path,
                                                   TestUser user, String body) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(runtime.baseUrl() + path))
                .timeout(REQUEST_TIMEOUT);
        if (user != null) {
            request.header("Authorization", "Bearer " + user.accessToken());
        }
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        try {
            return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (java.net.ConnectException connectionFailure) {
            throw connectionFailure;
        }
    }

    private static int availablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static String javaExecutable() {
        String executable = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }

    private static Path currentServerArtifact() {
        Path workingDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path serverDirectory = Files.isRegularFile(workingDirectory.resolve("pom.xml"))
                ? workingDirectory
                : workingDirectory.resolve("server");
        return serverDirectory.resolve("target/nexachat-server-0.0.1-SNAPSHOT.jar").normalize();
    }

    private static String requiredEnvironment(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required rollback drill input is missing: " + key);
        }
        return value;
    }

    private static String sha256(Path file) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
        return HexFormat.of().formatHex(digest);
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted((left, right) -> right.compareTo(left)).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Runtime directories are unique to this test and best-effort cleanup is safe.
                }
            });
        } catch (IOException ignored) {
            // Keep the test result primary if the OS has a transient file lock.
        }
    }

    private record TestUser(UUID id, String accessToken) {
    }

    private record MessageState(long seq, String content, boolean deleted) {
    }

    private record MessageInventory(UUID id, UUID conversationId, long seq, UUID senderId,
                                    UUID clientMessageId, String type, String content,
                                    Instant createdAt, Instant editedAt, Instant deletedAt) {
    }

    private record ConversationRevision(long highWater, long generation) {
    }

    private record DbSnapshot(Map<UUID, ConversationRevision> conversations,
                              Map<UUID, Long> messageVersions,
                              Map<UUID, MessageInventory> inventory) {
    }

    private record Drain(long cursor, long generation, int rowCount, int pages,
                         boolean lastHasMore, Map<UUID, MessageState> states,
                         Map<UUID, JsonNode> messages) {
    }

    private record Fixture(TestUser alice, TestUser bob, TestUser cara, UUID conversationId,
                           UUID editedId, UUID recalledId, UUID v31TargetId,
                           long savedCursor, long savedGeneration,
                           JsonNode delayedOldGenerationRow) {
    }

    private static final class InjectedRebuildFailure extends RuntimeException {
    }

    private static final class RuntimeServer implements AutoCloseable {
        private final Process process;
        private final int port;
        private final Path runtimeDirectory;
        private final Path logFile;
        private final boolean changeCursorsEnabled;
        private final boolean frozenV31;

        private RuntimeServer(Process process, int port, Path runtimeDirectory, Path logFile,
                              boolean changeCursorsEnabled, boolean frozenV31) {
            this.process = process;
            this.port = port;
            this.runtimeDirectory = runtimeDirectory;
            this.logFile = logFile;
            this.changeCursorsEnabled = changeCursorsEnabled;
            this.frozenV31 = frozenV31;
        }

        private String baseUrl() {
            return "http://127.0.0.1:" + port;
        }

        @Override
        public void close() {
            if (process.isAlive()) {
                process.destroy();
                try {
                    if (!process.waitFor(30, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                        process.waitFor(10, TimeUnit.SECONDS);
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                }
            }
            if (process.isAlive()) {
                throw new IllegalStateException("local rollback server did not stop: "
                        + (frozenV31 ? "V31" : "V32") + " switch=" + changeCursorsEnabled);
            }
            deleteTree(runtimeDirectory);
        }
    }
}
