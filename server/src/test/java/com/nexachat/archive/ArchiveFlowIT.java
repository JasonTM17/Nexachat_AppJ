package com.nexachat.archive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage of the storage browser ("Kho lưu trữ") against a real
 * PostgreSQL.
 *
 * <p>Proves the frozen contract: membership and token authZ, IMAGE/VIDEO vs
 * FILE classification with voice notes excluded, tombstone exclusion, link
 * extraction from TEXT content only, sender/date filters, strict parameter and
 * cursor validation, and keyset pagination that stays stable when a new
 * message is inserted between pages. Bulk fixtures are inserted through JDBC
 * (the REST send path has its own dedicated happy-path case) so the suite does
 * not fight the 30-messages/10s send rate limit.
 *
 * <p>{@code PER_CLASS}: users register once; the {@code +8495} phone prefix is
 * reserved for this suite.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ArchiveFlowIT {

    /** Minimal PNG (signature + IHDR start) — passes the magic-byte whitelist. */
    private static final byte[] PNG_BYTES = hex("89504E470D0A1A0A"
            + "0000000D4948445200000010000000100806000000");

    private static final Path STORAGE_ROOT = createTempStorageRoot();

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat"));
        registry.add("spring.datasource.username", () -> env("IT_DB_USER", "nexachat"));
        registry.add("spring.datasource.password", () -> env("IT_DB_PASSWORD", "nexachat"));
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
        registry.add("nexachat.storage.root", STORAGE_ROOT::toString);
    }

    private static Path createTempStorageRoot() {
        try {
            return Files.createTempDirectory("nexachat-archive-it-");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return (value == null || value.isBlank()) ? fallback : value;
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private record TestUser(UUID id, String phone, String displayName, String accessToken) {
    }

    private TestUser alice;
    private TestUser bob;
    private TestUser carol; // never joins a conversation — the non-member probe
    private TestUser dana;  // joins one conversation, then revokes her own token

    @BeforeAll
    void registerUsers() throws Exception {
        String like = "phone LIKE '+8495%'";
        jdbcTemplate.update(
                "DELETE FROM pinned_messages WHERE pinned_by IN (SELECT id FROM users WHERE " + like + ")");
        jdbcTemplate.update(
                "DELETE FROM conversations WHERE created_by IN (SELECT id FROM users WHERE " + like + ")");
        jdbcTemplate.update(
                "DELETE FROM message_attachments WHERE owner_id IN (SELECT id FROM users WHERE " + like + ")");
        jdbcTemplate.update("DELETE FROM users WHERE " + like);

        String tag = "ITA" + System.nanoTime();
        alice = registerUser(tag + " Alice");
        bob = registerUser(tag + " Bob");
        carol = registerUser("Zz" + tag + " Carol");
        dana = registerUser(tag + " Dana");
    }

    private static String randomPhone() {
        return "+8495" + String.format("%07d",
                ThreadLocalRandom.current().nextInt(10_000_000));
    }

    private TestUser registerUser(String displayName) throws Exception {
        String phone = randomPhone();
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("phone", phone, "password", "secret123",
                                        "displayName", displayName))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return new TestUser(
                UUID.fromString(body.get("user").get("id").asText()),
                phone, displayName, body.get("accessToken").asText());
    }

    // ============================================================== helpers

    private ResultActions getJson(String path, String accessToken) throws Exception {
        return mockMvc.perform(get(path).header("Authorization", "Bearer " + accessToken));
    }

    private ResultActions postJson(String path, String accessToken, Object body) throws Exception {
        return mockMvc.perform(post(path)
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private UUID openGroup(TestUser... members) throws Exception {
        List<String> memberIds = new ArrayList<>();
        for (TestUser member : members) {
            memberIds.add(member.id().toString());
        }
        MvcResult result = postJson("/api/v1/conversations/group", alice.accessToken(),
                Map.of("title", "Archive " + System.nanoTime(),
                        "memberIds", memberIds))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper
                .readTree(result.getResponse().getContentAsString())
                .get("id").asText());
    }

    private ResultActions archive(UUID conversationId, TestUser user, String query) throws Exception {
        String path = "/api/v1/conversations/" + conversationId + "/archive";
        if (query != null && !query.isBlank()) {
            path += "?" + query;
        }
        return getJson(path, user.accessToken());
    }

    private JsonNode archivePage(UUID conversationId, TestUser user, String query) throws Exception {
        MvcResult result = archive(conversationId, user, query)
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode uploadPng(TestUser user, String fileName, String blurHash) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", fileName, "image/png", PNG_BYTES);
        var builder = multipart("/api/v1/attachments").file(file)
                .param("width", "16")
                .param("height", "9");
        if (blurHash != null) {
            builder.param("blurHash", blurHash);
        }
        MvcResult result = mockMvc.perform(builder
                        .header("Authorization", "Bearer " + user.accessToken()))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private UUID sendRest(UUID conversationId, TestUser sender, String type,
                          String content, List<UUID> attachmentIds) throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("clientMessageId", UUID.randomUUID().toString());
        body.put("type", type);
        if (content != null) {
            body.put("content", content);
        }
        if (attachmentIds != null && !attachmentIds.isEmpty()) {
            body.put("attachmentIds", attachmentIds.stream().map(UUID::toString).toList());
        }
        MvcResult result = postJson("/api/v1/conversations/" + conversationId + "/messages",
                sender.accessToken(), body)
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper
                .readTree(result.getResponse().getContentAsString())
                .get("id").asText());
    }

    private UUID seedMessage(UUID conversationId, TestUser sender, String type,
                             String content, Instant createdAt) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO messages (conversation_id, sender_id, client_message_id,
                                      type, content, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                RETURNING id
                """, UUID.class,
                conversationId, sender.id(), UUID.randomUUID(), type, content,
                Timestamp.from(createdAt));
    }

    private UUID seedAttachment(UUID messageId, TestUser owner, String mimeType,
                                String fileName) {
        Integer durationMs = mimeType.startsWith("audio/") || mimeType.startsWith("video/")
                ? 4_500 : null;
        String blurHash = mimeType.startsWith("image/") ? "LEHV6nWB2yk8" : null;
        return jdbcTemplate.queryForObject("""
                INSERT INTO message_attachments (message_id, owner_id, storage_key,
                                                 original_name, mime_type, size_bytes,
                                                 width, height, duration_ms, blur_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """, UUID.class,
                messageId, owner.id(), "attachments/" + UUID.randomUUID(), fileName,
                mimeType, PNG_BYTES.length, 16, 9, durationMs, blurHash);
    }

    private UUID seedAttachmentMessage(UUID conversationId, TestUser sender, String type,
                                       String mimeType, String fileName, Instant createdAt) {
        UUID messageId = seedMessage(conversationId, sender, type, null, createdAt);
        seedAttachment(messageId, sender, mimeType, fileName);
        return messageId;
    }

    private long seqOf(UUID messageId) {
        Long seq = jdbcTemplate.queryForObject(
                "SELECT seq FROM messages WHERE id = ?", Long.class, messageId);
        return seq == null ? -1 : seq;
    }

    private List<JsonNode> items(JsonNode page) {
        List<JsonNode> result = new ArrayList<>();
        page.get("items").forEach(result::add);
        return result;
    }

    private static List<UUID> messageIds(JsonNode page) {
        List<UUID> ids = new ArrayList<>();
        page.get("items").forEach(item -> ids.add(UUID.fromString(item.get("messageId").asText())));
        return ids;
    }

    // ========================================================== authorization

    @Test
    void memberBrowsesOwnConversationAndNonMembersAreRejected() throws Exception {
        UUID conversationId = openGroup(bob);

        JsonNode uploaded = uploadPng(alice, "photo.png", "LEHV6nWB2yk8");
        UUID attachmentId = UUID.fromString(uploaded.get("id").asText());
        UUID messageId = sendRest(conversationId, alice, "IMAGE", null, List.of(attachmentId));

        JsonNode page = archivePage(conversationId, bob, "type=MEDIA");

        assertThat(items(page)).hasSize(1);
        JsonNode item = page.get("items").get(0);
        assertThat(item.get("messageId").asText()).isEqualTo(messageId.toString());
        assertThat(item.get("seq").asLong()).isEqualTo(seqOf(messageId));
        assertThat(item.get("senderId").asText()).isEqualTo(alice.id().toString());
        assertThat(item.get("createdAt").asText()).isNotBlank();
        assertThat(item.get("kind").asText()).isEqualTo("IMAGE");
        assertThat(item.get("attachment").get("id").asText()).isEqualTo(attachmentId.toString());
        assertThat(item.get("attachment").get("fileName").asText()).isEqualTo("photo.png");
        assertThat(item.get("attachment").get("mimeType").asText()).isEqualTo("image/png");
        assertThat(item.get("attachment").get("sizeBytes").asLong()).isEqualTo(PNG_BYTES.length);
        assertThat(item.get("attachment").get("width").asInt()).isEqualTo(16);
        assertThat(item.get("attachment").get("height").asInt()).isEqualTo(9);
        assertThat(item.get("attachment").get("blurHash").asText()).isEqualTo("LEHV6nWB2yk8");
        assertThat(item.get("attachment").get("durationMs").isNull()).isTrue();
        assertThat(item.get("link").isNull()).isTrue();
        assertThat(page.get("nextCursor").isNull()).isTrue();

        archive(conversationId, carol, "type=MEDIA")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_MEMBER"));

        archive(UUID.randomUUID(), alice, "type=MEDIA")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CONVERSATION_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/conversations/" + conversationId + "/archive?type=MEDIA"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void revokedAccessTokenIsRejected() throws Exception {
        UUID conversationId = openGroup(bob, dana);

        archive(conversationId, dana, "type=MEDIA").andExpect(status().isOk());

        // Access tokens carry second-granularity iat; let the cookie fall in a
        // previous second so the revocation cutoff is strictly later.
        Thread.sleep(1_100L);
        mockMvc.perform(post("/api/v1/auth/logout-all")
                        .header("Authorization", "Bearer " + dana.accessToken()))
                .andExpect(status().isNoContent());

        archive(conversationId, dana, "type=MEDIA")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("TOKEN_INVALID"));
    }

    // ========================================================= classification

    @Test
    void mediaFilesAndVoiceNotesAreClassifiedAndTombstonesExcluded() throws Exception {
        UUID conversationId = openGroup(bob);
        Instant base = Instant.parse("2026-06-01T10:00:00Z");

        UUID imageMessage = seedAttachmentMessage(conversationId, alice, "IMAGE",
                "image/png", "image.png", base);
        UUID imageAttachment = jdbcTemplate.queryForObject(
                "SELECT id FROM message_attachments WHERE message_id = ?", UUID.class, imageMessage);
        UUID videoMessage = seedAttachmentMessage(conversationId, alice, "VIDEO",
                "video/mp4", "clip.mp4", base.plusSeconds(1));
        UUID fileMessage = seedAttachmentMessage(conversationId, bob, "FILE",
                "application/pdf", "document.pdf", base.plusSeconds(2));
        UUID voiceMessage = seedAttachmentMessage(conversationId, alice, "AUDIO",
                "audio/mp4", "voice.m4a", base.plusSeconds(3));
        UUID voiceAttachment = jdbcTemplate.queryForObject(
                "SELECT id FROM message_attachments WHERE message_id = ?", UUID.class, voiceMessage);
        UUID deletedImage = seedAttachmentMessage(conversationId, bob, "IMAGE",
                "image/jpeg", "deleted.jpg", base.plusSeconds(4));
        jdbcTemplate.update(
                "UPDATE messages SET deleted_at = now() WHERE id = ?", deletedImage);

        JsonNode media = archivePage(conversationId, bob, "type=MEDIA&limit=50");
        assertThat(items(media)).hasSize(2);
        assertThat(media.get("items").get(0).get("kind").asText()).isEqualTo("VIDEO");
        assertThat(media.get("items").get(1).get("kind").asText()).isEqualTo("IMAGE");
        assertThat(media.get("items").get(1).get("messageId").asText())
                .isEqualTo(imageMessage.toString());
        assertThat(media.get("items").get(1).get("attachment").get("id").asText())
                .isEqualTo(imageAttachment.toString());
        assertThat(media.get("items").get(0).get("attachment").get("durationMs").asInt())
                .isEqualTo(4_500);
        assertThat(media.toString()).doesNotContain(voiceAttachment.toString());
        assertThat(media.toString()).doesNotContain(deletedImage.toString());

        JsonNode files = archivePage(conversationId, bob, "type=FILE&limit=50");
        assertThat(items(files)).hasSize(1);
        assertThat(files.get("items").get(0).get("messageId").asText())
                .isEqualTo(fileMessage.toString());
        assertThat(files.get("items").get(0).get("kind").asText()).isEqualTo("FILE");
        assertThat(files.get("items").get(0).get("attachment").get("mimeType").asText())
                .isEqualTo("application/pdf");
        assertThat(files.toString()).doesNotContain(voiceAttachment.toString());

        JsonNode links = archivePage(conversationId, bob, "type=LINK");
        assertThat(items(links)).isEmpty();

        jdbcTemplate.update("UPDATE messages SET deleted_at = now() WHERE id = ?", videoMessage);
        assertThat(items(archivePage(conversationId, bob, "type=MEDIA&limit=50"))).hasSize(1);
    }

    // ================================================================= links

    @Test
    void linksComeFromLiveTextMessagesOnlyWithTheDocumentedCap() throws Exception {
        UUID conversationId = openGroup(bob);
        Instant base = Instant.parse("2026-06-02T10:00:00Z");

        UUID older = seedMessage(conversationId, bob, "TEXT",
                "older https://old.example/page.", base);
        UUID deleted = seedMessage(conversationId, alice, "TEXT",
                "gone https://deleted.example/page", base.plusSeconds(1));
        jdbcTemplate.update("UPDATE messages SET deleted_at = now() WHERE id = ?", deleted);

        StringBuilder content = new StringBuilder("Links: https://example.com/0 repeat https://example.com/0 ")
                .append("(https://en.wikipedia.org/wiki/Foo_(bar)) ");
        for (int i = 1; i < 10; i++) {
            content.append("https://example.com/").append(i).append(' ');
        }
        content.append("javascript:alert(1) data:text/html;base64,PGgxPg==");
        UUID newest = seedMessage(conversationId, alice, "TEXT", content.toString(),
                base.plusSeconds(2));

        JsonNode page = archivePage(conversationId, bob, "type=LINK&limit=50");

        List<JsonNode> links = items(page);
        assertThat(links).hasSize(11);
        assertThat(links.get(0).get("kind").asText()).isEqualTo("LINK");
        assertThat(links.get(0).get("messageId").asText()).isEqualTo(newest.toString());
        assertThat(links.get(0).get("attachment").isNull()).isTrue();
        assertThat(links.get(0).get("link").get("url").asText())
                .isEqualTo("https://example.com/0");

        Set<String> urls = new HashSet<>();
        links.forEach(item -> urls.add(item.get("link").get("url").asText()));
        assertThat(urls).contains(
                "https://example.com/0", "https://example.com/8",
                "https://en.wikipedia.org/wiki/Foo_(bar)", "https://old.example/page");
        assertThat(urls).doesNotContain(
                "https://example.com/9", "https://example.com/10", "https://example.com/11");
        assertThat(urls.stream().filter("https://example.com/0"::equals)).hasSize(1);
        assertThat(page.toString()).doesNotContain("javascript:", "data:text/html",
                "https://deleted.example");
        assertThat(links.get(links.size() - 1).get("messageId").asText())
                .isEqualTo(older.toString());
    }

    // =============================================================== filters

    @Test
    void senderAndDateFiltersAreInclusiveAndValidated() throws Exception {
        UUID conversationId = openGroup(bob);
        Instant january = Instant.parse("2026-01-01T00:00:00Z");
        Instant june = Instant.parse("2026-06-01T00:00:00Z");
        Instant december = Instant.parse("2026-12-01T00:00:00Z");
        UUID aliceJanuary = seedAttachmentMessage(conversationId, alice, "IMAGE",
                "image/png", "jan.png", january);
        UUID bobJune = seedAttachmentMessage(conversationId, bob, "IMAGE",
                "image/png", "jun.png", june);
        UUID aliceDecember = seedAttachmentMessage(conversationId, alice, "IMAGE",
                "image/png", "dec.png", december);

        JsonNode bySender = archivePage(conversationId, bob,
                "type=MEDIA&senderId=" + bob.id());
        assertThat(messageIds(bySender)).containsExactly(bobJune);

        JsonNode byDate = archivePage(conversationId, bob,
                "type=MEDIA&from=2026-05-01T00:00:00Z&to=2026-07-01T00:00:00Z");
        assertThat(messageIds(byDate)).containsExactly(bobJune);

        JsonNode inclusiveBoundary = archivePage(conversationId, bob,
                "type=MEDIA&from=2026-06-01T00:00:00Z&to=2026-06-01T00:00:00Z");
        assertThat(messageIds(inclusiveBoundary)).containsExactly(bobJune);

        JsonNode combined = archivePage(conversationId, bob,
                "type=MEDIA&senderId=" + alice.id() + "&from=2026-07-01T00:00:00Z");
        assertThat(messageIds(combined)).containsExactly(aliceDecember);

        archive(conversationId, bob,
                "type=MEDIA&from=2026-12-01T00:00:00Z&to=2026-01-01T00:00:00Z")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        archive(conversationId, bob, "type=MEDIA&from=not-a-date")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        archive(conversationId, bob, "type=MEDIA&senderId=not-a-uuid")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        archive(conversationId, bob, "type=MEDIA&limit=0")
                .andExpect(status().isBadRequest());
        archive(conversationId, bob, "type=MEDIA&limit=51")
                .andExpect(status().isBadRequest());
        archive(conversationId, bob, "type=MEDIA&limit=abc")
                .andExpect(status().isBadRequest());
        archive(conversationId, bob, "type=VIDEO")
                .andExpect(status().isBadRequest());
        archive(conversationId, bob, "")
                .andExpect(status().isBadRequest());

        // Sanity: the unfiltered tab still has all three, newest first.
        JsonNode all = archivePage(conversationId, bob, "type=MEDIA");
        assertThat(messageIds(all)).containsExactly(aliceDecember, bobJune, aliceJanuary);
    }

    // ============================================================ pagination

    @Test
    void mediaPaginationIsStableWhenAMessageArrivesBetweenPages() throws Exception {
        UUID conversationId = openGroup(bob);
        Instant base = Instant.parse("2026-06-03T10:00:00Z");
        List<UUID> seeded = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            seeded.add(seedAttachmentMessage(conversationId, i % 2 == 0 ? alice : bob,
                    "IMAGE", "image/png", "p" + i + ".png", base.plusSeconds(i)));
        }

        JsonNode first = archivePage(conversationId, bob, "type=MEDIA&limit=3");
        assertThat(items(first)).hasSize(3);
        assertThat(first.get("nextCursor").asText()).isNotBlank();

        UUID inserted = seedAttachmentMessage(conversationId, alice, "IMAGE",
                "image/png", "new.png", base.plusSeconds(100));

        List<UUID> collected = new ArrayList<>(messageIds(first));
        String cursor = first.get("nextCursor").asText();
        while (cursor != null && !cursor.isBlank()) {
            JsonNode page = archivePage(conversationId, bob,
                    "type=MEDIA&limit=3&cursor=" + cursor);
            List<JsonNode> pageItems = items(page);
            assertThat(pageItems.size()).isLessThanOrEqualTo(3);
            collected.addAll(messageIds(page));
            cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asText();
        }

        assertThat(collected).hasSize(8);
        assertThat(collected).doesNotHaveDuplicates();
        assertThat(collected).doesNotContain(inserted);
        assertThat(collected).containsExactlyElementsOf(
                seeded.reversed());
    }

    @Test
    void linkPaginationIsStableWhenAMessageArrivesBetweenPages() throws Exception {
        UUID conversationId = openGroup(bob);
        Instant base = Instant.parse("2026-06-04T10:00:00Z");
        for (int i = 0; i < 5; i++) {
            seedMessage(conversationId, i % 2 == 0 ? alice : bob, "TEXT",
                    "https://m" + i + ".example/1 https://m" + i + ".example/2",
                    base.plusSeconds(i));
        }

        JsonNode first = archivePage(conversationId, bob, "type=LINK&limit=3");
        assertThat(items(first)).hasSize(3);
        String cursor = first.get("nextCursor").asText();

        seedMessage(conversationId, alice, "TEXT",
                "new https://new.example/x https://new.example/y", base.plusSeconds(100));

        List<String> urls = new ArrayList<>();
        items(first).forEach(item -> urls.add(item.get("link").get("url").asText()));
        while (!cursor.isBlank()) {
            JsonNode page = archivePage(conversationId, bob,
                    "type=LINK&limit=3&cursor=" + cursor);
            assertThat(items(page)).isNotEmpty();
            items(page).forEach(item -> urls.add(item.get("link").get("url").asText()));
            cursor = page.get("nextCursor").isNull() ? "" : page.get("nextCursor").asText();
        }

        assertThat(urls).hasSize(10);
        assertThat(urls).doesNotHaveDuplicates();
        assertThat(urls).doesNotContain("https://new.example/x");
    }

    @Test
    void cursorsAreBoundToTheConversationAndTabAndCannotBypassMembership() throws Exception {
        UUID conversationA = openGroup(bob);
        UUID conversationB = openGroup(bob);
        Instant base = Instant.parse("2026-06-05T10:00:00Z");
        for (int i = 0; i < 4; i++) {
            seedAttachmentMessage(conversationA, alice, "IMAGE",
                    "image/png", "a" + i + ".png", base.plusSeconds(i));
        }
        seedAttachmentMessage(conversationB, alice, "IMAGE",
                "image/png", "b.png", base);

        String cursor = archivePage(conversationA, bob, "type=MEDIA&limit=1")
                .get("nextCursor").asText();

        archive(conversationA, bob, "type=MEDIA&limit=1&cursor=" + tamper(cursor))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        archive(conversationA, bob, "type=LINK&limit=1&cursor=" + cursor)
                .andExpect(status().isBadRequest());
        archive(conversationB, bob, "type=MEDIA&limit=1&cursor=" + cursor)
                .andExpect(status().isBadRequest());

        // AuthZ is re-evaluated on every request: a valid cursor from a member
        // cannot make a non-member a member.
        archive(conversationA, carol, "type=MEDIA&limit=1&cursor=" + cursor)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_MEMBER"));
    }

    private static String tamper(String cursor) {
        char first = cursor.charAt(0);
        return (first == 'A' ? 'B' : 'A') + cursor.substring(1);
    }

    // ========================================================= populated DB

    @Test
    void populatedConversationPagesEveryTabToCompletion() throws Exception {
        UUID conversationId = openGroup(bob);
        Instant base = Instant.parse("2026-05-01T00:00:00Z");

        Set<UUID> mediaIds = new HashSet<>();
        for (int i = 0; i < 60; i++) {
            UUID message = seedAttachmentMessage(conversationId,
                    i % 3 == 0 ? bob : alice, "IMAGE", "image/png",
                    "img" + i + ".png", base.plusSeconds(i));
            mediaIds.add(message);
        }
        // One message with two attachments must page through its sub-cursor.
        UUID multi = seedMessage(conversationId, alice, "IMAGE", null, base.plusSeconds(60));
        seedAttachment(multi, alice, "image/png", "multi-1.png");
        seedAttachment(multi, alice, "image/png", "multi-2.png");

        int files = 0;
        for (int i = 0; i < 30; i++) {
            seedAttachmentMessage(conversationId, bob, "FILE",
                    "application/pdf", "doc" + i + ".pdf", base.plusSeconds(100 + i));
            files++;
        }
        for (int i = 0; i < 10; i++) {
            seedAttachmentMessage(conversationId, alice, "AUDIO",
                    "audio/mp4", "voice" + i + ".m4a", base.plusSeconds(200 + i));
        }
        for (int i = 0; i < 5; i++) {
            UUID deleted = seedAttachmentMessage(conversationId, bob, "IMAGE",
                    "image/jpeg", "deleted" + i + ".jpg", base.plusSeconds(300 + i));
            jdbcTemplate.update("UPDATE messages SET deleted_at = now() WHERE id = ?", deleted);
        }
        int linkMessages = 0;
        for (int i = 0; i < 40; i++) {
            seedMessage(conversationId, i % 2 == 0 ? alice : bob, "TEXT",
                    "https://p" + i + ".example/a https://p" + i + ".example/b",
                    base.plusSeconds(400 + i));
            linkMessages++;
        }

        List<String> mediaItems = new ArrayList<>();
        List<Long> mediaSeqs = new ArrayList<>();
        String cursor = null;
        do {
            JsonNode page = archivePage(conversationId, bob,
                    "type=MEDIA&limit=50" + (cursor == null ? "" : "&cursor=" + cursor));
            items(page).forEach(item -> {
                mediaItems.add(item.get("messageId").asText() + ":"
                        + item.get("attachment").get("id").asText());
                mediaSeqs.add(item.get("seq").asLong());
            });
            cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asText();
        } while (cursor != null);
        assertThat(mediaItems).hasSize(62); // 60 + two attachments on one message
        assertThat(mediaItems).doesNotHaveDuplicates();
        for (int i = 1; i < mediaSeqs.size(); i++) {
            assertThat(mediaSeqs.get(i)).isLessThanOrEqualTo(mediaSeqs.get(i - 1));
        }
        assertThat(mediaItems.stream().map(item -> UUID.fromString(
                        item.substring(0, item.indexOf(':')))).toList())
                .containsAll(mediaIds)
                .contains(multi);

        List<UUID> fileMessages = new ArrayList<>();
        cursor = null;
        do {
            JsonNode page = archivePage(conversationId, bob,
                    "type=FILE&limit=20" + (cursor == null ? "" : "&cursor=" + cursor));
            page.get("items").forEach(item ->
                    fileMessages.add(UUID.fromString(item.get("messageId").asText())));
            cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asText();
        } while (cursor != null);
        assertThat(fileMessages).hasSize(files);
        assertThat(fileMessages).doesNotHaveDuplicates();

        List<String> linkUrls = new ArrayList<>();
        cursor = null;
        do {
            JsonNode page = archivePage(conversationId, bob,
                    "type=LINK&limit=50" + (cursor == null ? "" : "&cursor=" + cursor));
            page.get("items").forEach(item ->
                    linkUrls.add(item.get("link").get("url").asText()));
            cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asText();
        } while (cursor != null);
        assertThat(linkUrls).hasSize(linkMessages * 2);
        assertThat(linkUrls).doesNotHaveDuplicates();
    }

    private static byte[] hex(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
