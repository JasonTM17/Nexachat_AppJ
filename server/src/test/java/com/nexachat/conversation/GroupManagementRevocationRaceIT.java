package com.nexachat.conversation;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.dto.PatchGroupRequest;
import com.nexachat.invite.InviteService;
import com.nexachat.invite.dto.CreateInviteLinkRequest;
import com.nexachat.invite.dto.PatchInviteLinkRequest;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

@Testcontainers
@SpringBootTest(properties = "spring.datasource.hikari.maximum-pool-size=4")
@ActiveProfiles("it")
class GroupManagementRevocationRaceIT {
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
    @MockitoSpyBean ConversationMemberRepository members;
    @Autowired GroupService groups;
    @Autowired InviteService invites;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;

    @ParameterizedTest
    @CsvSource({"avatar,false", "title,false", "create,false", "patch,false", "revoke,false",
            "avatar,true", "title,true", "create,true", "patch,true", "revoke,true"})
    void authorityRevokedBeforeManagementLockIsRejected(String operation, boolean demote) throws Exception {
        User caller = users.saveAndFlush(User.createGoogleOnly("Caller", email(), Instant.now()));
        User owner = users.saveAndFlush(User.createGoogleOnly("Owner", email(), Instant.now()));
        Conversation group = Conversation.group(owner, "Original title");
        group.changeAvatarUrl("/api/v1/files/avatars/original.png");
        group = conversations.saveAndFlush(group);
        UUID groupId = group.getId();
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status -> {
            Conversation managed = conversations.findById(groupId).orElseThrow();
            members.saveAndFlush(ConversationMember.join(managed, users.getReferenceById(caller.getId()), ConversationMember.Role.ADMIN));
            members.saveAndFlush(ConversationMember.join(managed, users.getReferenceById(owner.getId()), ConversationMember.Role.ADMIN));
        });
        invites.createOrRotateInviteLink(owner.getId(), groupId, new CreateInviteLinkRequest(null, 10, false));

        CountDownLatch read = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean once = new AtomicBoolean();
        var delegate = org.mockito.Mockito.mockingDetails(members).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            Object result = delegate.answer(invocation);
            if (Thread.currentThread().getName().equals("management-race") && once.compareAndSet(false, true)) {
                read.countDown();
                assertThat(release.await(15, TimeUnit.SECONDS)).isTrue();
            }
            return result;
        }).when(members).findByConversation_IdAndUser_Id(eq(groupId), eq(caller.getId()));

        ExecutorService executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "management-race"));
        try {
            Future<Throwable> pending = executor.submit(() -> {
                try {
                    switch (operation) {
                        case "avatar" -> groups.updateGroup(caller.getId(), groupId, new PatchGroupRequest(null, ""));
                        case "title" -> groups.updateGroup(caller.getId(), groupId, new PatchGroupRequest("Changed", null));
                        case "create" -> invites.createOrRotateInviteLink(caller.getId(), groupId, null);
                        case "patch" -> invites.patchInviteLink(caller.getId(), groupId, new PatchInviteLinkRequest(true));
                        case "revoke" -> invites.revokeInviteLink(caller.getId(), groupId);
                        default -> throw new AssertionError(operation);
                    }
                    return null;
                } catch (Throwable failure) {
                    return failure;
                }
            });
            assertThat(read.await(15, TimeUnit.SECONDS))
                    .as("initial membership query reached; task result: %s", pending.isDone() ? pending.get() : "running").isTrue();
            if (demote) {
                jdbc.update("UPDATE conversation_members SET role='MEMBER' WHERE conversation_id=? AND user_id=?",
                        groupId, caller.getId());
            } else {
                groups.removeMember(owner.getId(), groupId, caller.getId());
            }
            long messages = jdbc.queryForObject("SELECT count(*) FROM messages WHERE conversation_id=?", Long.class, groupId);
            release.countDown();
            Throwable failure = pending.get(20, TimeUnit.SECONDS);
            assertThat(failure).isInstanceOf(ApiException.class);
            assertThat(((ApiException) failure).getCode()).isEqualTo(demote ? ApiErrorCode.NOT_ADMIN : ApiErrorCode.NOT_MEMBER);
            Conversation persisted = conversations.findById(groupId).orElseThrow();
            assertThat(persisted.getTitle()).isEqualTo("Original title");
            assertThat(persisted.getAvatarUrl()).isEqualTo("/api/v1/files/avatars/original.png");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM group_invite_links WHERE conversation_id=?", Long.class, groupId))
                    .isEqualTo(1L);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM group_invite_links WHERE conversation_id=? AND revoked_at IS NULL AND requires_approval=false",
                    Long.class, groupId)).isEqualTo(1L);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM messages WHERE conversation_id=?", Long.class, groupId))
                    .isEqualTo(messages);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static String email() {
        return UUID.randomUUID() + "@example.test";
    }
}
