package com.nexachat.conversation;

import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.dto.AddMemberRequest;
import com.nexachat.conversation.dto.PatchGroupRequest;
import com.nexachat.invite.InviteService;
import com.nexachat.invite.dto.CreateInviteLinkRequest;
import com.nexachat.user.User;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GroupAuthorityRaceIT extends ConcurrentDatabaseITSupport {
    @Autowired private InviteService invites;

    enum Mutation { ADD, REMOVE, RENAME, CREATE_LINK, APPROVE }

    @ParameterizedTest
    @EnumSource(Mutation.class)
    void demotedAdministratorCannotCommitAWaitingMutation(Mutation mutation) throws Exception {
        revokedAdministratorCannotCommit(mutation, false);
    }

    @ParameterizedTest
    @EnumSource(Mutation.class)
    void removedAdministratorCannotCommitAWaitingMutation(Mutation mutation) throws Exception {
        revokedAdministratorCannotCommit(mutation, true);
    }

    private void revokedAdministratorCannotCommit(Mutation mutation, boolean removed) throws Exception {
        User owner = user("Owner");
        User admin = user("Acting admin");
        User target = user("Existing member");
        User newcomer = user("New member");
        UUID conversation = group(owner, admin, target);
        jdbc.update("UPDATE conversation_members SET role='ADMIN' WHERE conversation_id=? AND user_id=?",
                conversation, admin.getId());
        UUID request = null;
        if (mutation == Mutation.APPROVE) {
            var link = invites.createOrRotateInviteLink(owner.getId(), conversation,
                    new CreateInviteLinkRequest(null, 3, true));
            request = invites.join(newcomer.getId(), link.token(), clientIp).requestId();
        }
        UUID requestId = request;
        long messagesBefore = count("SELECT count(*) FROM messages WHERE conversation_id=?", conversation);
        long linksBefore = count("SELECT count(*) FROM group_invite_links WHERE conversation_id=?", conversation);

        // Even remove-member (which already checks after the lock) is forced to overlap revocation.
        try (Workers workers = new Workers(); Gate gate = gate("conversations", conversation)) {
            var waiting = workers.submit(() -> {
                try {
                    switch (mutation) {
                        case ADD -> groups.addMembers(admin.getId(), conversation,
                                new AddMemberRequest(List.of(newcomer.getId())));
                        case REMOVE -> groups.removeMember(admin.getId(), conversation, target.getId());
                        case RENAME -> groups.updateGroup(admin.getId(), conversation,
                                new PatchGroupRequest("Unauthorized rename", null));
                        case CREATE_LINK -> invites.createOrRotateInviteLink(admin.getId(), conversation,
                                new CreateInviteLinkRequest(null, null, false));
                        case APPROVE -> invites.approveJoinRequest(admin.getId(), conversation, requestId);
                    }
                    return "COMMITTED";
                } catch (ApiException denial) {
                    return denial.getCode().name();
                }
            });
            awaitBlocked(gate.pid, 1);
            if (removed) {
                gate.update("DELETE FROM conversation_members WHERE conversation_id=? AND user_id=?",
                        conversation, admin.getId());
            } else {
                gate.update("UPDATE conversation_members SET role='MEMBER' WHERE conversation_id=? AND user_id=?",
                        conversation, admin.getId());
            }
            gate.commit();
            assertThat(result(waiting)).isEqualTo(removed ? "NOT_MEMBER" : "NOT_ADMIN");
        }
        assertThat(member(conversation, target.getId())).isTrue();
        assertThat(member(conversation, newcomer.getId())).isFalse();
        assertThat(jdbc.queryForObject("SELECT title FROM conversations WHERE id=?", String.class, conversation))
                .isEqualTo("Original group");
        assertThat(count("SELECT count(*) FROM messages WHERE conversation_id=?", conversation)).isEqualTo(messagesBefore);
        assertThat(count("SELECT count(*) FROM group_invite_links WHERE conversation_id=?", conversation)).isEqualTo(linksBefore);
        if (request != null) {
            assertThat(jdbc.queryForObject("SELECT status FROM group_join_requests WHERE id=?", String.class, request))
                    .isEqualTo("PENDING");
        }
    }
}
