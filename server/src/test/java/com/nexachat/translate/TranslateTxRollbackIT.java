package com.nexachat.translate;

import com.nexachat.conversation.ConversationService;
import com.nexachat.conversation.dto.OpenDirectRequest;
import com.nexachat.message.MessageService;
import com.nexachat.message.dto.SendMessageRequest;
import com.nexachat.testutil.ItPhoneRanges;
import com.nexachat.translate.dto.GlossaryTermDto;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Runtime proof of the translate transaction boundaries that
 * {@link TranslateServiceTxContractTest} only asserts by reflection (that
 * reflection check stays as the smoke test). A forced SQL exception
 * mid-flow — a BEFORE trigger that RAISEs, the injection style
 * {@code StorageOutsideTransactionIT} uses — must roll the whole Spring
 * transaction back on a real PostgreSQL, leaving no partial state:
 *
 * <ul>
 *   <li>{@code TranslateService.replaceGlossary}: the DELETE plus any inserts
 *       that already ran before the poisoned insert vanish; the previous
 *       glossary survives untouched.</li>
 *   <li>{@code TranslationCacheRepository.save}: the upsert and the cap trim
 *       stand or fall together in the cache write's own transaction — the
 *       atomicity the non-transactional translate flow depends on.</li>
 *   <li>{@code TranslateRateLimiter.tryConsume}: a crash inside the
 *       minute-token refund cannot leave the consumed token behind — the
 *       refund really runs in the same transaction as the consume.</li>
 * </ul>
 *
 * <p>Runs on a disposable PostgreSQL container, so it needs no
 * {@code IT_DB_URL} and never touches a developer or CI service database.</p>
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("it")
class TranslateTxRollbackIT {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("nexachat_translate_tx_it")
                    .withUsername("nexachat")
                    .withPassword("nexachat");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ConversationService conversationService;
    @Autowired
    private MessageService messageService;
    @Autowired
    private TranslateService translateService;
    @Autowired
    private TranslationCacheRepository cacheRepository;
    @Autowired
    private TranslationGlossaryRepository glossaryRepository;
    @Autowired
    private TranslateRateLimiter rateLimiter;
    @Autowired
    private TranslateProperties translateProperties;

    private User owner;
    private UUID conversationId;
    private UUID messageId;

    @BeforeEach
    void createFixture() {
        owner = userRepository.save(User.create(
                ItPhoneRanges.nextPhone(TranslateTxRollbackIT.class), "x".repeat(60),
                "Translate tx rollback owner"));
        User peer = userRepository.save(User.create(
                ItPhoneRanges.nextPhone(TranslateTxRollbackIT.class), "x".repeat(60),
                "Translate tx rollback peer"));
        conversationId = conversationService
                .openDirect(owner.getId(), new OpenDirectRequest(null, peer.getId()))
                .body().id();
        messageId = messageService.send(owner.getId(), conversationId,
                        new SendMessageRequest(UUID.randomUUID(), "TEXT",
                                "tx rollback fixture message", null, List.of()))
                .message().id();
    }

    @AfterEach
    void removeFixture() {
        // Failure injection is schema state on the shared container database:
        // always uninstall it, then drop the suite's rows in FK order (the
        // translate tables cascade from users / conversations / messages).
        dropInjection("ttx_glossary_reject", "translation_glossary_terms",
                "ttx_glossary_reject_fn");
        dropInjection("ttx_trim_reject", "message_translations", "ttx_trim_reject_fn");
        dropInjection("ttx_refund_reject", "translate_request_limits",
                "ttx_refund_reject_fn");
        ItPhoneRanges.clean(jdbcTemplate, TranslateTxRollbackIT.class);
    }

    @Test
    void glossaryReplaceFailureMidFlowLeavesThePreviousGlossaryUntouched() {
        translateService.replaceGlossary(owner.getId(), conversationId, List.of(
                new GlossaryTermDto("keep-alpha", "Keep Alpha"),
                new GlossaryTermDto("keep-beta", null)));
        jdbcTemplate.execute("""
                CREATE FUNCTION ttx_glossary_reject_fn() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'ttx glossary insert rejected';
                END $$;
                """);
        // The poisoned term passes every service-side check, so the failure
        // lands mid-flow: after the DELETE and the first INSERT have already
        // run inside the still-open transaction.
        jdbcTemplate.execute("""
                CREATE TRIGGER ttx_glossary_reject BEFORE INSERT ON translation_glossary_terms
                FOR EACH ROW WHEN (NEW.term = 'ttx-poison-term')
                EXECUTE FUNCTION ttx_glossary_reject_fn();
                """);

        Throwable failure = catchThrowable(() -> translateService.replaceGlossary(
                owner.getId(), conversationId, List.of(
                        new GlossaryTermDto("fresh-first", "Fresh"),
                        new GlossaryTermDto("ttx-poison-term", null),
                        new GlossaryTermDto("fresh-third", null))));

        assertThat(failure).as("the poisoned insert must surface as a SQL failure")
                .hasStackTraceContaining("ttx glossary insert rejected");
        assertThat(glossaryRepository.findTerms(conversationId, owner.getId()))
                .as("the DELETE and the insert that already ran before the failure rolled back")
                .extracting(TranslationGlossaryRepository.GlossaryTerm::term)
                .containsExactlyInAnyOrder("keep-alpha", "keep-beta");
    }

    @Test
    void cacheSaveFailureMidFlowDropsTheUpsertAndKeepsThePriorRevisionRow() {
        // One cached row at an older revision: saving a newer revision must
        // try to trim it away — the failure lands on that second statement.
        cacheRepository.save(messageId, 6L, "en", GlossaryHasher.EMPTY_HASH,
                new TranslationCacheRepository.CacheEntry("hello", "vi",
                        "it-rollback-model", "local"),
                Instant.now().plusSeconds(3600), 8);
        jdbcTemplate.execute("""
                CREATE FUNCTION ttx_trim_reject_fn() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'ttx cache trim rejected';
                END $$;
                """);
        jdbcTemplate.execute(("""
                CREATE TRIGGER ttx_trim_reject BEFORE DELETE ON message_translations
                FOR EACH ROW WHEN (OLD.message_id = '%s')
                EXECUTE FUNCTION ttx_trim_reject_fn();
                """).formatted(messageId));

        Throwable failure = catchThrowable(() -> cacheRepository.save(messageId, 7L, "fr",
                GlossaryHasher.EMPTY_HASH,
                new TranslationCacheRepository.CacheEntry("salut", "en",
                        "it-rollback-model", "local"),
                Instant.now().plusSeconds(3600), 8));

        assertThat(failure).as("the cap trim must surface as a SQL failure")
                .hasStackTraceContaining("ttx cache trim rejected");
        assertThat(cachedRows(7L, "fr"))
                .as("the upsert that ran before the failing trim rolled back").isZero();
        assertThat(cachedRows(6L, "en"))
                .as("the old-revision row the trim tried to delete survived the rollback")
                .isEqualTo(1);
    }

    @Test
    void refundFailureMidFlowConsumesNoToken() {
        // Day windows at their cap: the next consume is refused after the
        // minute window already took its token, so the limiter refunds — and
        // a crash inside that refund must not leave the consumed token behind.
        // Yesterday/tomorrow rows keep the seeded cap valid even if the UTC
        // day rolls between seeding and the call.
        UUID userId = owner.getId();
        int cap = translateProperties.getRateLimitPerDay();
        LocalDate today = LocalDate.ofInstant(Instant.now(), ZoneOffset.UTC);
        for (LocalDate day : List.of(today.minusDays(1), today, today.plusDays(1))) {
            jdbcTemplate.update("""
                    INSERT INTO translate_request_limits
                        (user_id, window_type, window_start, request_count)
                    VALUES (?, 'DAY', ?, ?)
                    """, userId, OffsetDateTime.ofInstant(
                    day.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC), cap);
        }
        jdbcTemplate.execute("""
                CREATE FUNCTION ttx_refund_reject_fn() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'ttx refund delete rejected';
                END $$;
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER ttx_refund_reject BEFORE DELETE ON translate_request_limits
                FOR EACH ROW WHEN (OLD.window_type = 'MINUTE')
                EXECUTE FUNCTION ttx_refund_reject_fn();
                """);

        Throwable failure = catchThrowable(() -> rateLimiter.tryConsume(userId, 1));

        assertThat(failure).as("the refund must surface as a SQL failure")
                .hasStackTraceContaining("ttx refund delete rejected");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM translate_request_limits
                WHERE user_id = ? AND window_type = 'MINUTE'
                """, Integer.class, userId))
                .as("the minute token consumed before the refund crash rolled back")
                .isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM translate_request_limits
                WHERE user_id = ? AND window_type = 'DAY' AND request_count = ?
                """, Integer.class, userId, cap))
                .as("the seeded day windows are untouched")
                .isEqualTo(3);
    }

    private int cachedRows(long changeVersion, String targetLang) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM message_translations
                WHERE message_id = ? AND change_version = ? AND target_lang = ?
                """, Integer.class, messageId, changeVersion, targetLang);
    }

    private void dropInjection(String trigger, String table, String function) {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + trigger + " ON " + table);
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS " + function + "()");
    }
}
