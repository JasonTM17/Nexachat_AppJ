package com.nexachat.invite;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.*;
import com.nexachat.invite.dto.CreateInviteLinkRequest;
import com.nexachat.invite.dto.JoinRequestResponse;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;

@Testcontainers
@SpringBootTest(properties = "spring.datasource.hikari.maximum-pool-size=4")
@ActiveProfiles("it")
class InviteReviewRaceIT {
    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16-alpine")
            .withImagePullPolicy(image -> false);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
    }

    @Autowired UserRepository users;
    @Autowired ConversationRepository conversations;
    @Autowired ConversationMemberRepository members;
    @MockitoSpyBean GroupJoinRequestRepository requests;
    @Autowired InviteService invites;
    @Autowired GroupInviteLinkRepository links;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    @ParameterizedTest
    @CsvSource({"approve,reject", "reject,approve", "approve,approve", "reject,reject"})
    void cachedPendingRequestCannotOverwriteWinningDecision(String winner, String loser) throws Exception {
        User first = users.saveAndFlush(User.createGoogleOnly("First admin", email(), Instant.now()));
        User second = users.saveAndFlush(User.createGoogleOnly("Second admin", email(), Instant.now()));
        User joining = users.saveAndFlush(User.createGoogleOnly("Joining user", email(), Instant.now()));
        UUID groupId = new TransactionTemplate(transactions).execute(status -> {
            Conversation group = conversations.saveAndFlush(Conversation.group(users.getReferenceById(first.getId()), "Review group"));
            members.saveAndFlush(ConversationMember.join(group, users.getReferenceById(first.getId()), ConversationMember.Role.ADMIN));
            members.saveAndFlush(ConversationMember.join(group, users.getReferenceById(second.getId()), ConversationMember.Role.ADMIN));
            return group.getId();
        });
        invites.createOrRotateInviteLink(first.getId(), groupId, new CreateInviteLinkRequest(null, 10, true));
        UUID linkId = jdbc.queryForObject("SELECT id FROM group_invite_links WHERE conversation_id=? AND revoked_at IS NULL", UUID.class, groupId);
        UUID requestId = new TransactionTemplate(transactions).execute(status -> requests.saveAndFlush(
                new GroupJoinRequest(conversations.getReferenceById(groupId),
                        links.getReferenceById(linkId), users.getReferenceById(joining.getId()))).getId());

        CountDownLatch read = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean once = new AtomicBoolean();
        var delegate = org.mockito.Mockito.mockingDetails(requests).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            Object result = delegate.answer(invocation);
            if (Thread.currentThread().getName().equals("review-race") && once.compareAndSet(false, true)) {
                read.countDown();
                assertThat(release.await(15, TimeUnit.SECONDS)).isTrue();
            }
            return result;
        }).when(requests).findById(requestId);

        ExecutorService executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "review-race"));
        try {
            Future<Object> pending = executor.submit(() -> {
                try {
                    return decide(loser, second.getId(), groupId, requestId);
                } catch (ApiException conflict) {
                    return conflict;
                }
            });
            assertThat(read.await(15, TimeUnit.SECONDS)).isTrue();
            JoinRequestResponse decision = decide(winner, first.getId(), groupId, requestId);
            // Compare the committed replay representation: PostgreSQL rounds
            // Instant nanoseconds to microseconds on the first transaction's commit.
            decision = decide(winner, first.getId(), groupId, requestId);
            release.countDown();
            Object result = pending.get(20, TimeUnit.SECONDS);
            if (winner.equals(loser)) {
                assertThat(result).isEqualTo(decision);
            } else {
                assertThat(result).isInstanceOf(ApiException.class);
                assertThat(((ApiException) result).getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
            }
            String terminal = winner.equals("approve") ? "APPROVED" : "REJECTED";
            assertThat(jdbc.queryForObject("SELECT status FROM group_join_requests WHERE id=?", String.class, requestId)).isEqualTo(terminal);
            assertThat(jdbc.queryForObject("SELECT reviewed_by FROM group_join_requests WHERE id=?", UUID.class, requestId)).isEqualTo(first.getId());
            assertThat(jdbc.queryForObject("SELECT use_count FROM group_invite_links WHERE id=?", Integer.class, linkId))
                    .isEqualTo(winner.equals("approve") ? 1 : 0);
            assertThat(members.existsByConversation_IdAndUser_Id(groupId, joining.getId())).isEqualTo(winner.equals("approve"));
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private JoinRequestResponse decide(String operation, UUID caller, UUID conversation, UUID request) {
        return operation.equals("approve") ? invites.approveJoinRequest(caller, conversation, request)
                : invites.rejectJoinRequest(caller, conversation, request);
    }

    private static String email() {
        return UUID.randomUUID() + "@example.test";
    }
}
