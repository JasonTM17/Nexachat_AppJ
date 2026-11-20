package com.nexachat.invite;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.Conversation;
import com.nexachat.conversation.ConversationMember;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.conversation.ConversationRepository;
import com.nexachat.invite.dto.CreateInviteLinkRequest;
import com.nexachat.invite.dto.InviteLinkResponse;
import com.nexachat.invite.dto.JoinRequestResponse;
import com.nexachat.testutil.TestWaits;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency guard for the join-request approval cap section
 * (review finding 1.5): {@code lockReview} must hold the conversation row
 * FOR UPDATE across the member-cap count, the link-use consumption and the
 * member insert, so N concurrent approvals cannot oversubscribe either the
 * member cap or a link's maxUses.
 *
 * <p>Both racing approvals act for different admins on different requests,
 * so their user-row locks are disjoint and both park on the conversation
 * row the test holds. Releasing the row makes the two transactions run
 * strictly one after the other against the same count — exactly the
 * interleaving that would over-fill the group if the lock were missing.
 */
@Testcontainers
@SpringBootTest(properties = {
        "spring.datasource.hikari.maximum-pool-size=4",
        "nexachat.group.max-members=4"
})
@ActiveProfiles("it")
class InviteApprovalCapConcurrencyIT {

    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16-alpine")
            .withImagePullPolicy(image -> false);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
    }

    @Autowired
    private UserRepository users;
    @Autowired
    private ConversationRepository conversations;
    @Autowired
    private ConversationMemberRepository members;
    @Autowired
    private GroupJoinRequestRepository requests;
    @Autowired
    private GroupInviteLinkRepository links;
    @Autowired
    private InviteService invites;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactions;

    @Test
    void concurrentApprovalsCannotExceedTheMemberCap() throws Exception {
        // Seeded one seat below the cap: the winner lands the group at
        // exactly cap, the loser must get the same contract error the join
        // path gives, without a second member row or link use.
        RaceFixture fixture = seedGroupAndTwoPendingRequests(null, 3);

        ExecutorService approvals = racingApprovals();
        try (Connection lock = holdConversationRow(fixture.groupId())) {
            CompletableFuture<Object> first = approveAsync(approvals, fixture, 0);
            CompletableFuture<Object> second = approveAsync(approvals, fixture, 1);
            awaitBlockedApprovals(2);

            lock.commit();
            Object winner = first.get(20, TimeUnit.SECONDS);
            Object loser = second.get(20, TimeUnit.SECONDS);

            // Exactly one approval lands the group at the cap; the loser
            // gets the same contract error the join path gives.
            JoinRequestResponse approved = asApproved(winner, loser);
            ApiException refused = asRefused(winner, loser, ApiErrorCode.VALIDATION_FAILED);
            assertThat(refused.getDetails()).containsEntry("limit", 4);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM conversation_members WHERE conversation_id = ?",
                    Integer.class, fixture.groupId()))
                    .isEqualTo(4);
            assertThat(jdbc.queryForObject(
                    "SELECT use_count FROM group_invite_links WHERE id = ?",
                    Integer.class, fixture.linkId()))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT status FROM group_join_requests WHERE id = ?",
                    String.class, approved.id()))
                    .isEqualTo("APPROVED");
            assertThat(jdbc.queryForObject(
                    "SELECT status FROM group_join_requests WHERE id = ?",
                    String.class, refusedRequestId(fixture, winner, loser)))
                    .isEqualTo("PENDING");
        } finally {
            approvals.shutdownNow();
        }
    }

    @Test
    void concurrentApprovalsCannotExceedTheLinkMaxUses() throws Exception {
        // maxUses=1 with the member cap not binding (two seats free): the
        // link is the binding constraint — the loser must pass the member
        // cap and then find the link exhausted.
        RaceFixture fixture = seedGroupAndTwoPendingRequests(1, 2);

        ExecutorService approvals = racingApprovals();
        try (Connection lock = holdConversationRow(fixture.groupId())) {
            CompletableFuture<Object> first = approveAsync(approvals, fixture, 0);
            CompletableFuture<Object> second = approveAsync(approvals, fixture, 1);
            awaitBlockedApprovals(2);

            lock.commit();
            Object winner = first.get(20, TimeUnit.SECONDS);
            Object loser = second.get(20, TimeUnit.SECONDS);

            asApproved(winner, loser);
            asRefused(winner, loser, ApiErrorCode.NOT_FOUND);
            assertThat(jdbc.queryForObject(
                    "SELECT use_count FROM group_invite_links WHERE id = ?",
                    Integer.class, fixture.linkId()))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM conversation_members WHERE conversation_id = ?",
                    Integer.class, fixture.groupId()))
                    .isEqualTo(3);
        } finally {
            approvals.shutdownNow();
        }
    }

    // -------------------------------------------------------------- fixture

    private record RaceFixture(
            UUID groupId, UUID linkId, UUID adminA, UUID adminB,
            UUID firstRequestId, UUID secondRequestId) {
    }

    /**
     * Two admins (so the racing transactions hold disjoint user rows and
     * contend on the conversation row) plus {@code seedMembers - 2} plain
     * members, and two PENDING requests seeded directly — a pending request
     * cannot be created through the API once the member cap check matters
     * here.
     */
    private RaceFixture seedGroupAndTwoPendingRequests(Integer maxUses, int seedMembers) {
        User adminA = users.saveAndFlush(
                User.createGoogleOnly("Cap race admin A", email(), Instant.now()));
        User adminB = users.saveAndFlush(
                User.createGoogleOnly("Cap race admin B", email(), Instant.now()));
        User firstJoiner = users.saveAndFlush(
                User.createGoogleOnly("Cap race joiner one", email(), Instant.now()));
        User secondJoiner = users.saveAndFlush(
                User.createGoogleOnly("Cap race joiner two", email(), Instant.now()));
        List<User> extraMembers = new ArrayList<>(Math.max(0, seedMembers - 2));
        for (int i = 0; i < seedMembers - 2; i++) {
            extraMembers.add(users.saveAndFlush(
                    User.createGoogleOnly("Cap race seed " + i, email(), Instant.now())));
        }

        UUID groupId = new TransactionTemplate(transactions).execute(status -> {
            Conversation group = conversations.saveAndFlush(
                    Conversation.group(users.getReferenceById(adminA.getId()), "Cap race group"));
            members.saveAndFlush(ConversationMember.join(
                    group, users.getReferenceById(adminA.getId()), ConversationMember.Role.ADMIN));
            members.saveAndFlush(ConversationMember.join(
                    group, users.getReferenceById(adminB.getId()), ConversationMember.Role.ADMIN));
            for (User extra : extraMembers) {
                members.saveAndFlush(ConversationMember.join(
                        group, users.getReferenceById(extra.getId()), ConversationMember.Role.MEMBER));
            }
            return group.getId();
        });

        InviteLinkResponse link = invites.createOrRotateInviteLink(
                adminA.getId(), groupId, new CreateInviteLinkRequest(null, maxUses, true));
        UUID linkId = link.id();

        List<UUID> requestIds = new ArrayList<>(2);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            requestIds.add(requests.saveAndFlush(new GroupJoinRequest(
                    conversations.getReferenceById(groupId),
                    links.getReferenceById(linkId),
                    users.getReferenceById(firstJoiner.getId()))).getId());
            requestIds.add(requests.saveAndFlush(new GroupJoinRequest(
                    conversations.getReferenceById(groupId),
                    links.getReferenceById(linkId),
                    users.getReferenceById(secondJoiner.getId()))).getId());
        });
        return new RaceFixture(groupId, linkId, adminA.getId(), adminB.getId(),
                requestIds.get(0), requestIds.get(1));
    }

    private Connection holdConversationRow(UUID groupId) throws Exception {
        Connection connection = jdbc.getDataSource().getConnection();
        connection.setAutoCommit(false);
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM conversations WHERE id = ? FOR UPDATE")) {
            statement.setObject(1, groupId);
            statement.executeQuery().close();
        }
        return connection;
    }

    private ExecutorService racingApprovals() {
        return Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "approve-cap-race");
            thread.setDaemon(true);
            return thread;
        });
    }

    private CompletableFuture<Object> approveAsync(
            ExecutorService executor, RaceFixture fixture, int index) {
        UUID admin = index == 0 ? fixture.adminA() : fixture.adminB();
        UUID requestId = index == 0 ? fixture.firstRequestId() : fixture.secondRequestId();
        return CompletableFuture.supplyAsync(() -> {
            try {
                return (Object) invites.approveJoinRequest(admin, fixture.groupId(), requestId);
            } catch (ApiException failure) {
                return failure;
            }
        }, executor);
    }

    /** Waits until both racing approvals park on the held conversation row. */
    private void awaitBlockedApprovals(int expected) {
        TestWaits.until(expected + " racing approval(s) to block on the "
                        + "conversations row lock",
                () -> lockWaiters() >= expected);
    }

    private int lockWaiters() {
        Integer waiting = jdbc.queryForObject("""
                SELECT count(*) FROM pg_stat_activity
                WHERE datname = current_database()
                  AND pid <> pg_backend_pid()
                  AND wait_event_type = 'Lock'
                  AND query ILIKE '%conversations%'
                """, Integer.class);
        return waiting == null ? 0 : waiting;
    }

    private static JoinRequestResponse asApproved(Object first, Object second) {
        List<Object> results = List.of(first, second);
        List<JoinRequestResponse> approved = results.stream()
                .filter(JoinRequestResponse.class::isInstance)
                .map(JoinRequestResponse.class::cast)
                .toList();
        assertThat(approved).as("exactly one approval must win").hasSize(1);
        return approved.get(0);
    }

    private static ApiException asRefused(Object first, Object second, ApiErrorCode expectedCode) {
        List<ApiException> refused = List.of(first, second).stream()
                .filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .toList();
        assertThat(refused).as("the losing approval must be the typed refusal").hasSize(1);
        assertThat(refused.get(0).getCode()).isEqualTo(expectedCode);
        return refused.get(0);
    }

    private static UUID refusedRequestId(RaceFixture fixture, Object first, Object second) {
        boolean firstWon = first instanceof JoinRequestResponse;
        return firstWon ? fixture.secondRequestId() : fixture.firstRequestId();
    }

    private static String email() {
        return UUID.randomUUID() + "@example.test";
    }
}
