package com.nexachat.invite;

import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.ConcurrentDatabaseITSupport;
import com.nexachat.invite.dto.CreateInviteLinkRequest;
import com.nexachat.user.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class InviteTransitionRaceIT extends ConcurrentDatabaseITSupport {
    @Autowired private InviteService invites;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private EntityManager entityManager;

    enum Invalidation { REVOKE, EXPIRE, ROTATE, PATCH }
    private record Fixture(User owner, User applicant, UUID conversation, UUID link, String token, UUID request) { }

    private Fixture pending(int maxUses) {
        User owner = user("Owner");
        User applicant = user("Applicant");
        UUID conversation = group(owner, user("Member"));
        var link = invites.createOrRotateInviteLink(owner.getId(), conversation,
                new CreateInviteLinkRequest(null, maxUses, true));
        var request = invites.join(applicant.getId(), link.token(), clientIp);
        return new Fixture(owner, applicant, conversation, link.id(), link.token(), request.requestId());
    }

    @ParameterizedTest
    @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void competingDecisionsSerializeAndNeverOverwriteATerminalState(boolean approveFirst, boolean approveSecond)
            throws Exception {
        Fixture fixture = pending(3);
        CountDownLatch firstReady = new CountDownLatch(1);
        CountDownLatch commitFirst = new CountDownLatch(1);
        AtomicInteger firstPid = new AtomicInteger();
        try (Workers workers = new Workers()) {
            var first = workers.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                String decision = decide(fixture, approveFirst);
                entityManager.flush();
                firstPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                firstReady.countDown();
                await(commitFirst);
                return decision;
            }));
            try {
                await(firstReady);
                var second = workers.submit(() -> decide(fixture, approveSecond));
                awaitBlocked(firstPid.get(), 1);
                commitFirst.countDown();
                assertThat(result(first)).isEqualTo(approveFirst ? "APPROVED" : "REJECTED");
                assertThat(result(second)).isEqualTo(approveFirst == approveSecond
                        ? (approveFirst ? "APPROVED" : "REJECTED") : "VALIDATION_FAILED");
            } finally {
                commitFirst.countDown();
            }
        }
        assertThat(jdbc.queryForObject("SELECT status FROM group_join_requests WHERE id=?", String.class, fixture.request))
                .isEqualTo(approveFirst ? "APPROVED" : "REJECTED");
        assertThat(member(fixture.conversation, fixture.applicant.getId())).isEqualTo(approveFirst);
        assertThat(uses(fixture)).isEqualTo(approveFirst ? 1 : 0);
    }

    @ParameterizedTest
    @EnumSource(value = Invalidation.class, names = {"REVOKE", "EXPIRE", "ROTATE"})
    void linkInvalidationCommittedBeforeApprovalPreventsAdmission(Invalidation invalidation) throws Exception {
        Fixture fixture = pending(2);
        try (Workers workers = new Workers(); Gate gate = gate("conversations", fixture.conversation)) {
            var approval = workers.submit(() -> decide(fixture, true));
            awaitBlocked(gate.pid, 1);
            if (invalidation == Invalidation.EXPIRE) {
                gate.update("UPDATE group_invite_links SET expires_at=clock_timestamp() WHERE id=?", fixture.link);
            } else {
                // Rotation invalidates the predecessor link exactly as revoke does.
                gate.update("UPDATE group_invite_links SET revoked_at=clock_timestamp(), revoked_by=? WHERE id=?",
                        fixture.owner.getId(), fixture.link);
                if (invalidation == Invalidation.ROTATE) {
                    gate.update("""
                            INSERT INTO group_invite_links(id,conversation_id,created_by,token_hash,requires_approval)
                            VALUES (?,?,?,?,true)
                            """, UUID.randomUUID(), fixture.conversation, fixture.owner.getId(), "a".repeat(64));
                }
            }
            gate.commit();
            assertThat(result(approval)).isEqualTo("NOT_FOUND");
        }
        assertThat(member(fixture.conversation, fixture.applicant.getId())).isFalse();
        assertThat(uses(fixture)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM group_join_requests WHERE id=?", String.class, fixture.request))
                .isEqualTo("PENDING");
    }

    @ParameterizedTest
    @EnumSource(Invalidation.class)
    void approvalCommittedBeforeInvalidationPreservesTheConsumedUse(Invalidation invalidation) throws Exception {
        Fixture fixture = pending(2);
        CountDownLatch approved = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        AtomicInteger firstPid = new AtomicInteger();
        try (Workers workers = new Workers()) {
            var approval = workers.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                String decision = decide(fixture, true);
                entityManager.flush();
                firstPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                approved.countDown();
                await(commit);
                return decision;
            }));
            try {
                await(approved);
                var invalidationResult = workers.submit(() -> {
                    switch (invalidation) {
                        case REVOKE -> invites.revokeInviteLink(fixture.owner.getId(), fixture.conversation);
                        case ROTATE -> invites.createOrRotateInviteLink(fixture.owner.getId(), fixture.conversation,
                                new CreateInviteLinkRequest(null, 2, true));
                        case PATCH -> invites.patchInviteLink(fixture.owner.getId(), fixture.conversation,
                                new com.nexachat.invite.dto.PatchInviteLinkRequest(false));
                        case EXPIRE -> jdbc.update("UPDATE group_invite_links SET expires_at=clock_timestamp()-interval '1 day' WHERE id=?",
                                fixture.link);
                    }
                    return true;
                });
                awaitBlocked(firstPid.get(), 1);
                commit.countDown();
                assertThat(result(approval)).isEqualTo("APPROVED");
                assertThat(result(invalidationResult)).isTrue();
            } finally {
                commit.countDown();
            }
        }
        assertThat(member(fixture.conversation, fixture.applicant.getId())).isTrue();
        assertThat(uses(fixture)).isEqualTo(1);
    }

    @Test
    void exhaustedOneUseLinkStillAcknowledgesTheSameDirectJoin() {
        User owner = user("Owner");
        User applicant = user("Applicant");
        UUID conversation = group(owner, user("Member"));
        var link = invites.createOrRotateInviteLink(owner.getId(), conversation,
                new CreateInviteLinkRequest(null, 1, false));
        assertThat(invites.join(applicant.getId(), link.token(), clientIp).status()).isEqualTo("JOINED");
        assertThat(invites.join(applicant.getId(), link.token(), clientIp).status()).isEqualTo("ALREADY_MEMBER");
        assertThat(count("SELECT use_count FROM group_invite_links WHERE id=?", link.id())).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(value = Invalidation.class, names = {"REVOKE", "EXPIRE", "ROTATE"})
    void linkInvalidationCommittedWhileDirectJoinWaitsPreventsAdmission(Invalidation invalidation) throws Exception {
        User owner = user("Owner");
        User applicant = user("Applicant");
        UUID conversation = group(owner, user("Member"));
        var link = invites.createOrRotateInviteLink(owner.getId(), conversation,
                new CreateInviteLinkRequest(null, 1, false));
        try (Workers workers = new Workers(); Gate gate = gate("users", applicant.getId())) {
            var join = workers.submit(() -> {
                try {
                    return invites.join(applicant.getId(), link.token(), clientIp).status();
                } catch (ApiException denial) {
                    return denial.getCode().name();
                }
            });
            awaitBlocked(gate.pid, 1);
            switch (invalidation) {
                case REVOKE -> invites.revokeInviteLink(owner.getId(), conversation);
                case ROTATE -> invites.createOrRotateInviteLink(owner.getId(), conversation,
                        new CreateInviteLinkRequest(null, 1, false));
                case EXPIRE -> jdbc.update("UPDATE group_invite_links SET expires_at=clock_timestamp() WHERE id=?", link.id());
                default -> throw new AssertionError("Unsupported invalidation");
            }
            gate.commit();
            assertThat(result(join)).isEqualTo("NOT_FOUND");
        }
        assertThat(member(conversation, applicant.getId())).isFalse();
        assertThat(count("SELECT use_count FROM group_invite_links WHERE id=?", link.id())).isZero();
    }

    @Test
    void oneUseApprovalRetryDoesNotConsumeAgain() {
        Fixture fixture = pending(1);
        assertThat(decide(fixture, true)).isEqualTo("APPROVED");
        assertThat(decide(fixture, true)).isEqualTo("APPROVED");
        assertThat(uses(fixture)).isEqualTo(1);
    }

    @Test
    void duplicatePendingJoinsShareOneRequestWithoutConsumingTheLink() throws Exception {
        User owner = user("Owner");
        User applicant = user("Applicant");
        UUID conversation = group(owner, user("Member"));
        var link = invites.createOrRotateInviteLink(owner.getId(), conversation,
                new CreateInviteLinkRequest(null, 1, true));
        try (Workers workers = new Workers(); Gate gate = gate("users", applicant.getId())) {
            var first = workers.submit(() -> invites.join(applicant.getId(), link.token(), clientIp));
            var second = workers.submit(() -> invites.join(applicant.getId(), link.token(), clientIp));
            awaitBlocked(gate.pid, 2);
            gate.commit();
            assertThat(result(first).requestId()).isEqualTo(result(second).requestId());
        }
        assertThat(count("SELECT count(*) FROM group_join_requests WHERE conversation_id=?", conversation)).isEqualTo(1);
        assertThat(count("SELECT use_count FROM group_invite_links WHERE id=?", link.id())).isZero();
    }

    @Test
    void differentPendingRequestsCompeteForTheLastUse() throws Exception {
        Fixture fixture = pending(1);
        User other = user("Other applicant");
        UUID otherRequest = invites.join(other.getId(), fixture.token, clientIp).requestId();
        Fixture otherFixture = new Fixture(fixture.owner, other, fixture.conversation, fixture.link, fixture.token, otherRequest);
        try (Workers workers = new Workers(); Gate gate = gate("conversations", fixture.conversation)) {
            var first = workers.submit(() -> decide(fixture, true));
            var second = workers.submit(() -> decide(otherFixture, true));
            // Observe both requests: the second may be transitively queued on the owner's user lock.
            awaitBlocked(gate.pid, 2);
            gate.commit();
            assertThat(java.util.List.of(result(first), result(second))).containsExactlyInAnyOrder("APPROVED", "NOT_FOUND");
        }
        assertThat(uses(fixture)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM conversation_members WHERE conversation_id=? AND user_id IN (?,?)",
                fixture.conversation, fixture.applicant.getId(), other.getId())).isEqualTo(1);
    }

    private String decide(Fixture fixture, boolean approve) {
        try {
            return (approve ? invites.approveJoinRequest(fixture.owner.getId(), fixture.conversation, fixture.request)
                    : invites.rejectJoinRequest(fixture.owner.getId(), fixture.conversation, fixture.request)).status();
        } catch (ApiException denial) {
            return denial.getCode().name();
        }
    }

    private long uses(Fixture fixture) {
        return count("SELECT use_count FROM group_invite_links WHERE id=?", fixture.link);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(15, TimeUnit.SECONDS)).as("planned transaction boundary reached").isTrue();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }
}
