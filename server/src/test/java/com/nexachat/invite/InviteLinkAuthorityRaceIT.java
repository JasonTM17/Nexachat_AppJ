package com.nexachat.invite;

import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.ConcurrentDatabaseITSupport;
import com.nexachat.invite.dto.CreateInviteLinkRequest;
import com.nexachat.invite.dto.PatchInviteLinkRequest;
import jakarta.persistence.EntityManager;
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

class InviteLinkAuthorityRaceIT extends ConcurrentDatabaseITSupport {
    @Autowired private InviteService invites;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactions;
    enum Mutation { PATCH, REVOKE }

    @ParameterizedTest
    @CsvSource({"PATCH,false", "PATCH,true", "REVOKE,false", "REVOKE,true"})
    void authorityRevokedWhileWaitingForTheUserLockDeniesTheLinkMutation(Mutation mutation, boolean removed) throws Exception {
        var owner = user("Owner");
        var admin = user("Admin");
        UUID conversation = group(owner, admin);
        jdbc.update("UPDATE conversation_members SET role='ADMIN' WHERE conversation_id=? AND user_id=?", conversation, admin.getId());
        var link = invites.createOrRotateInviteLink(owner.getId(), conversation, new CreateInviteLinkRequest(null, 2, true));
        try (Workers workers = new Workers(); Gate gate = gate("users", admin.getId())) {
            var waiting = workers.submit(() -> {
                try {
                    mutate(mutation, admin.getId(), conversation);
                    return "COMMITTED";
                } catch (ApiException denial) {
                    return denial.getCode().name();
                }
            });
            awaitBlocked(gate.pid, 1);
            gate.update("UPDATE conversations SET title=title WHERE id=?", conversation);
            gate.update(removed ? "DELETE FROM conversation_members WHERE conversation_id=? AND user_id=?"
                    : "UPDATE conversation_members SET role='MEMBER' WHERE conversation_id=? AND user_id=?", conversation, admin.getId());
            gate.commit();
            assertThat(result(waiting)).isEqualTo(removed ? "NOT_MEMBER" : "NOT_ADMIN");
        }
        assertThat(count("SELECT count(*) FROM group_invite_links WHERE id=? AND revoked_at IS NULL AND requires_approval", link.id())).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(Mutation.class)
    void membershipRemovalWaitsForAnAlreadyAuthorizedLinkMutation(Mutation mutation) throws Exception {
        var owner = user("Owner");
        var admin = user("Admin");
        UUID conversation = group(owner, admin);
        jdbc.update("UPDATE conversation_members SET role='ADMIN' WHERE conversation_id=? AND user_id=?", conversation, admin.getId());
        var link = invites.createOrRotateInviteLink(owner.getId(), conversation, new CreateInviteLinkRequest(null, 2, true));
        CountDownLatch mutated = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        AtomicInteger mutationPid = new AtomicInteger();
        try (Workers workers = new Workers()) {
            var mutationResult = workers.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                mutate(mutation, admin.getId(), conversation);
                entityManager.flush();
                mutationPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                mutated.countDown();
                await(commit);
                return true;
            }));
            try {
                await(mutated);
                var removal = workers.submit(() -> {
                    groups.removeMember(owner.getId(), conversation, admin.getId());
                    return true;
                });
                awaitBlocked(mutationPid.get(), 1);
                commit.countDown();
                assertThat(result(mutationResult)).isTrue();
                assertThat(result(removal)).isTrue();
            } finally {
                commit.countDown();
            }
        }
        assertThat(member(conversation, admin.getId())).isFalse();
        assertThat(count(mutation == Mutation.REVOKE
                ? "SELECT count(*) FROM group_invite_links WHERE id=? AND revoked_at IS NOT NULL"
                : "SELECT count(*) FROM group_invite_links WHERE id=? AND requires_approval=false", link.id())).isEqualTo(1);
        assertThat(count("SELECT use_count FROM group_invite_links WHERE id=?", link.id())).isZero();
    }

    private void mutate(Mutation mutation, UUID caller, UUID conversation) {
        if (mutation == Mutation.PATCH) invites.patchInviteLink(caller, conversation, new PatchInviteLinkRequest(false));
        else invites.revokeInviteLink(caller, conversation);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(15, TimeUnit.SECONDS)).as("planned authority transaction boundary reached").isTrue();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }
}
