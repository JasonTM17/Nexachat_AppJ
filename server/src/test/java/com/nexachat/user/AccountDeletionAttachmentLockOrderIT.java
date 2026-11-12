package com.nexachat.user;

import com.nexachat.testutil.ItPhoneRanges;

import com.nexachat.conversation.Conversation;
import com.nexachat.conversation.ConversationMember;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.conversation.ConversationRepository;
import com.nexachat.message.MessageAttachment;
import com.nexachat.message.MessageAttachmentRepository;
import com.nexachat.message.MessageService;
import com.nexachat.message.dto.SendMessageRequest;
import com.nexachat.testutil.TestWaits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * Deterministic two-transaction proof for the account-deletion / attachment
 * send lock inversion.
 *
 * <p>Deletion holds the user row (FOR UPDATE). A send by the same user locks
 * its unbound attachment row first, then the message insert needs a key-share
 * on that user row. A deletion that waits for the attachment row closes the
 * cycle and PostgreSQL raises 40P01. Deletion must skip an attachment row an
 * in-flight send already owns, and must only report files whose rows it
 * actually removed.</p>
 */
@SpringBootTest
@ActiveProfiles("it")
@TestPropertySource(properties = {
        "spring.datasource.hikari.maximum-pool-size=6",
        "spring.datasource.hikari.minimum-idle=0"
})
class AccountDeletionAttachmentLockOrderIT {

    private static final String PASSWORD = "secret123";

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
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
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ConversationRepository conversationRepository;
    @Autowired
    private ConversationMemberRepository memberRepository;
    @Autowired
    private MessageAttachmentRepository attachmentRepository;
    @Autowired
    private MessageService messageService;
    @Autowired
    private AccountDeletionService accountDeletionService;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @MockitoSpyBean
    private AccountDeletionJdbcSupport jdbcSupport;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private UUID deletingUserId;
    private UUID conversationId;
    private UUID attachmentId;

    @BeforeEach
    void reset() {
        ItPhoneRanges.clean(jdbcTemplate, AccountDeletionAttachmentLockOrderIT.class);
    }

    @AfterEach
    void stopExecutor() {
        executor.shutdownNow();
    }

    @Test
    void deletionSkipsAnAttachmentAnInFlightSendOwnsInsteadOfDeadlocking() throws Exception {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            User deletingUser = userRepository.save(
                    User.create(ItPhoneRanges.phoneFor(AccountDeletionAttachmentLockOrderIT.class, "+84905553291"), passwordEncoder.encode(PASSWORD), "Deleting User"));
            User otherMember = userRepository.save(
                    User.create(ItPhoneRanges.phoneFor(AccountDeletionAttachmentLockOrderIT.class, "+84905553292"), passwordEncoder.encode(PASSWORD), "Other Member"));
            deletingUserId = deletingUser.getId();

            Conversation conversation = conversationRepository.save(
                    Conversation.group(deletingUser, "lock order"));
            conversationId = conversation.getId();
            memberRepository.saveAll(List.of(
                    ConversationMember.join(conversation, deletingUser, ConversationMember.Role.ADMIN),
                    ConversationMember.join(conversation, otherMember, ConversationMember.Role.MEMBER)));

            MessageAttachment attachment = attachmentRepository.save(MessageAttachment.upload(
                    deletingUser,
                    "attachments/" + UUID.randomUUID() + ".png",
                    "attachment.png", "image/png", 8L, null, null, null, null));
            attachmentId = attachment.getId();
        });

        CountDownLatch deletionAtAttachments = new CountDownLatch(1);
        CountDownLatch resumeDeletion = new CountDownLatch(1);
        doAnswer(invocation -> {
            deletionAtAttachments.countDown();
            assertThat(resumeDeletion.await(30, TimeUnit.SECONDS)).isTrue();
            return invocation.callRealMethod();
        }).when(jdbcSupport).deleteUnboundAttachments(any(UUID.class));

        Future<?> deletion = executor.submit(() ->
                accountDeletionService.deleteAccount(deletingUserId, PASSWORD));
        assertThat(deletionAtAttachments.await(30, TimeUnit.SECONDS)).isTrue();

        boolean conversationLocked = conversationRowIsLocked(conversationId);

        Future<?> send = executor.submit(() -> messageService.send(
                deletingUserId, conversationId,
                new SendMessageRequest(UUID.randomUUID(), "IMAGE", null, null,
                        List.of(attachmentId))));

        // The send locks its attachment row before the message INSERT needs a
        // key-share on the deleting user row. Wait until it either owns the row
        // and waits (pre-fix, group row held) or has finished binding it
        // (post-fix); releasing deletion before that would let the DELETE win
        // the race and make the send fail as not-found.
        TestWaits.until("the send to claim its attachment or finish",
                () -> send.isDone() || lockWaiterCount() > 0);

        resumeDeletion.countDown();
        try {
            deletion.get(30, TimeUnit.SECONDS);
            send.get(30, TimeUnit.SECONDS);
        } finally {
            resumeDeletion.countDown();
        }

        assertThat(conversationLocked)
                .as("unbound attachment rows must be settled before group conversation rows")
                .isFalse();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT message_id IS NOT NULL FROM message_attachments WHERE id = ?
                """, Boolean.class, attachmentId))
                .as("the in-flight send keeps the upload it won")
                .isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT deleted_at IS NOT NULL FROM users WHERE id = ?", Boolean.class, deletingUserId))
                .isTrue();
    }

    private boolean conversationRowIsLocked(UUID conversationId) throws SQLException {
        try (Connection probe = directConnection();
             PreparedStatement statement = probe.prepareStatement(
                     "SELECT 1 FROM conversations WHERE id = ? FOR UPDATE NOWAIT")) {
            statement.setObject(1, conversationId);
            statement.executeQuery().close();
            return false;
        } catch (SQLException failure) {
            if ("55P03".equals(failure.getSQLState())) {
                return true;
            }
            throw failure;
        }
    }

    private int lockWaiterCount() {
        try (Connection probe = directConnection();
             PreparedStatement statement = probe.prepareStatement("""
                     SELECT count(*) FROM pg_stat_activity
                     WHERE datname = current_database()
                       AND state = 'active'
                       AND wait_event_type = 'Lock'
                     """);
             ResultSet result = statement.executeQuery()) {
            result.next();
            return result.getInt(1);
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static Connection directConnection() throws SQLException {
        return DriverManager.getConnection(
                env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat_test"),
                env("IT_DB_USER", "nexachat"),
                env("IT_DB_PASSWORD", "nexachat"));
    }
}
