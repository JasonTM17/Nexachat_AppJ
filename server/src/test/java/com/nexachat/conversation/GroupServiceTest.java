package com.nexachat.conversation;

import com.nexachat.attachment.StorageService;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.dto.AddMemberRequest;
import com.nexachat.conversation.dto.ConversationMemberView;
import com.nexachat.conversation.dto.ConversationResponse;
import com.nexachat.conversation.dto.ConversationUpdatePayload;
import com.nexachat.conversation.dto.CreateGroupRequest;
import com.nexachat.conversation.dto.MemberResponse;
import com.nexachat.conversation.dto.PatchGroupRequest;
import com.nexachat.message.MessageAttachmentRepository;
import com.nexachat.message.MessageService;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GroupServiceTest {

    @Mock
    private ConversationRepository conversationRepository;
    @Mock
    private ConversationMemberRepository memberRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private MessageAttachmentRepository attachmentRepository;
    @Mock
    private MessageService messageService;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private SimpUserRegistry simpUserRegistry;
    @Mock
    private SimpleBrokerMessageHandler simpleBrokerMessageHandler;
    @Mock
    private com.nexachat.attachment.StorageService storageService;

    private GroupService groupService;

    private final UUID adminId = UUID.randomUUID();
    private final UUID bobId = UUID.randomUUID();
    private final UUID carolId = UUID.randomUUID();
    private final UUID conversationId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        groupService = new GroupService(
                conversationRepository, memberRepository, userRepository, attachmentRepository,
                messageService, eventPublisher, storageService,
                simpUserRegistry, simpleBrokerMessageHandler);
    }

    // ------------------------------------------------------------- fixtures

    private User userWithId(UUID id, String phone, String displayName) {
        User user = User.create(phone, "hash", displayName);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private User admin() {
        return userWithId(adminId, "+84901110001", "Admin Alice");
    }

    private User bob() {
        return userWithId(bobId, "+84901110002", "Bob");
    }

    private User carol() {
        return userWithId(carolId, "+84901110003", "Carol");
    }

    private List<UUID> sortedIds(UUID... ids) {
        return Arrays.stream(ids).distinct().sorted().toList();
    }

    private Conversation groupConversation() {
        Conversation conversation = Conversation.group(admin(), "Old Name");
        ReflectionTestUtils.setField(conversation, "id", conversationId);
        ReflectionTestUtils.setField(conversation, "createdAt", Instant.now());
        return conversation;
    }

    private Conversation directConversation() {
        Conversation conversation = Conversation.direct(admin(), "a:b");
        ReflectionTestUtils.setField(conversation, "id", conversationId);
        ReflectionTestUtils.setField(conversation, "createdAt", Instant.now());
        return conversation;
    }

    private ConversationMember member(Conversation conversation, UUID userId,
                                      String displayName, ConversationMember.Role role) {
        return ConversationMember.join(
                conversation, userWithId(userId, "+84901234567", displayName), role);
    }

    /** Stubs the three lookups every member op performs up front. */
    private void groupExistsAndCallerIs(ConversationMember.Role callerRole) {
        Conversation conversation = groupConversation();
        when(conversationRepository.findById(conversationId))
                .thenReturn(Optional.of(conversation));
        lenient().when(memberRepository.findByConversation_IdAndUser_Id(conversationId, adminId))
                .thenReturn(Optional.of(
                        member(conversation, adminId, "Admin Alice", callerRole)));
    }

    private ArgumentCaptor<ConversationUpdatedEvent> updateEvent() {
        ArgumentCaptor<ConversationUpdatedEvent> captor =
                ArgumentCaptor.forClass(ConversationUpdatedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        return captor;
    }

    // -------------------------------------------------------------- create

    @Test
    void createGroup_happy_creatorIsAdmin_membersAreMember_systemLineWritten() {
        when(userRepository.findActiveUsersByIdsForUpdate(sortedIds(adminId, bobId)))
                .thenReturn(List.of(admin(), bob()));
        when(conversationRepository.save(any(Conversation.class))).thenAnswer(inv -> {
            Conversation c = inv.getArgument(0);
            ReflectionTestUtils.setField(c, "id", conversationId);
            ReflectionTestUtils.setField(c, "createdAt", Instant.now());
            return c;
        });
        when(memberRepository.findMembers(conversationId)).thenReturn(List.of());
        when(memberRepository.findMemberViews(conversationId)).thenReturn(List.of(
                new ConversationMemberView(adminId, "Admin Alice", "+84901110001", false),
                new ConversationMemberView(bobId, "Bob", "+84901110002", false)));

        ConversationResponse response = groupService.createGroup(
                adminId, new CreateGroupRequest("  Team  ", List.of(bobId)));

        assertThat(response.id()).isEqualTo(conversationId);
        assertThat(response.type()).isEqualTo("GROUP");
        assertThat(response.members()).hasSize(2);

        ArgumentCaptor<Conversation> saved = ArgumentCaptor.forClass(Conversation.class);
        verify(conversationRepository).save(saved.capture());
        assertThat(saved.getValue().getType()).isEqualTo(Conversation.Type.GROUP);
        assertThat(saved.getValue().getTitle()).isEqualTo("Team"); // trimmed
        assertThat(saved.getValue().getDirectKey()).isNull();

        ArgumentCaptor<List<ConversationMember>> rows =
                ArgumentCaptor.forClass(List.class);
        verify(memberRepository).saveAll(rows.capture());
        assertThat(rows.getValue()).hasSize(2);
        assertThat(rows.getValue().get(0).getUser().getId()).isEqualTo(adminId);
        assertThat(rows.getValue().get(0).getRole()).isEqualTo(ConversationMember.Role.ADMIN);
        assertThat(rows.getValue().get(1).getUser().getId()).isEqualTo(bobId);
        assertThat(rows.getValue().get(1).getRole()).isEqualTo(ConversationMember.Role.MEMBER);

        verify(messageService).postSystemMessage(
                eq(adminId), eq(conversationId), contains("created the group"));
        ConversationUpdatedEvent event = updateEvent().getValue();
        assertThat(event.payload().change()).isEqualTo("CREATED");
        assertThat(event.payload().title()).isEqualTo("Team");
        assertThat(event.extraAudienceUserIds()).isEmpty();
        verify(eventPublisher).publishEvent(new ConversationMembershipChangedEvent(conversationId, List.of()));
    }

    @Test
    void createGroup_dedupesMemberIds_andSkipsCreator() {
        when(userRepository.findActiveUsersByIdsForUpdate(sortedIds(adminId, bobId)))
                .thenReturn(List.of(admin(), bob()));
        when(conversationRepository.save(any(Conversation.class))).thenAnswer(inv -> {
            Conversation c = inv.getArgument(0);
            ReflectionTestUtils.setField(c, "id", conversationId);
            ReflectionTestUtils.setField(c, "createdAt", Instant.now());
            return c;
        });
        when(memberRepository.findMembers(conversationId)).thenReturn(List.of());
        when(memberRepository.findMemberViews(conversationId)).thenReturn(List.of());

        // creator listed + a duplicate → only bob ends up as a MEMBER row
        groupService.createGroup(adminId,
                new CreateGroupRequest("Team", List.of(bobId, bobId, adminId)));

        ArgumentCaptor<List<ConversationMember>> rows =
                ArgumentCaptor.forClass(List.class);
        verify(memberRepository).saveAll(rows.capture());
        assertThat(rows.getValue()).hasSize(2); // creator ADMIN + bob MEMBER
    }

    @Test
    void createGroup_onlyCreatorListed_throwsValidation() {
        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> groupService.createGroup(
                        adminId, new CreateGroupRequest("Team", List.of(adminId))));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
        verify(conversationRepository, never()).save(any());
    }

    @Test
    void createGroup_emptyMembers_throwsValidation() {
        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> groupService.createGroup(
                        adminId, new CreateGroupRequest("Team", List.of())));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
    }

    @Test
    void createGroup_blankTitle_throwsValidation() {
        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> groupService.createGroup(
                        adminId, new CreateGroupRequest("   ", List.of(bobId))));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
        verify(userRepository, never()).findById(any());
    }

    @Test
    void createGroup_titleOver80_throwsValidation() {
        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> groupService.createGroup(adminId,
                        new CreateGroupRequest("x".repeat(81), List.of(bobId))));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
    }

    @Test
    void createGroup_unknownMemberId_throwsUserNotFound() {
        when(userRepository.findActiveUsersByIdsForUpdate(sortedIds(adminId, bobId)))
                .thenReturn(List.of(admin())); // missing bob

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> groupService.createGroup(
                        adminId, new CreateGroupRequest("Team", List.of(bobId))));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.USER_NOT_FOUND);
        assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        verify(conversationRepository, never()).save(any());
    }

    // ----------------------------------------------------------- addMembers

    @Test
    void addMembers_happy_savesOnlyNewAndPostsSystemPerAdd() {
        groupExistsAndCallerIs(ConversationMember.Role.ADMIN);
        when(memberRepository.findMemberUserIds(conversationId)).thenReturn(List.of(adminId));
        when(userRepository.findActiveUsersByIdsForUpdate(sortedIds(adminId, bobId, carolId)))
                .thenReturn(List.of(admin(), bob(), carol()));
        when(memberRepository.findMembers(conversationId)).thenReturn(List.of());

        groupService.addMembers(adminId, conversationId,
                new AddMemberRequest(List.of(bobId, carolId)));

        verify(memberRepository, times(2)).save(any(ConversationMember.class));
        verify(messageService).postSystemMessage(
                eq(adminId), eq(conversationId), eq("Admin Alice added Bob"));
        verify(messageService).postSystemMessage(
                eq(adminId), eq(conversationId), eq("Admin Alice added Carol"));
        assertThat(updateEvent().getValue().payload().change()).isEqualTo("MEMBERS_ADDED");
    }

    @Test
    void addMembers_existingMemberSkipped_idempotent() {
        groupExistsAndCallerIs(ConversationMember.Role.ADMIN);
        when(memberRepository.findMemberUserIds(conversationId))
                .thenReturn(List.of(adminId, bobId)); // bob already in
        when(userRepository.findActiveUsersByIdsForUpdate(sortedIds(adminId, bobId, carolId)))
                .thenReturn(List.of(admin(), bob(), carol()));
        when(memberRepository.findMembers(conversationId)).thenReturn(List.of());

        groupService.addMembers(adminId, conversationId,
                new AddMemberRequest(List.of(bobId, carolId)));

        verify(memberRepository, times(1)).save(any(ConversationMember.class));
        verify(messageService, times(1)).postSystemMessage(any(), any(), anyString());
    }

    @Test
    void addMembers_allAlreadyMembers_noopNoEvents() {
        groupExistsAndCallerIs(ConversationMember.Role.ADMIN);
        when(memberRepository.findMemberUserIds(conversationId))
                .thenReturn(List.of(adminId, bobId));
        when(userRepository.findActiveUsersByIdsForUpdate(sortedIds(adminId, bobId)))
                .thenReturn(List.of(admin(), bob()));

        groupService.addMembers(adminId, conversationId, new AddMemberRequest(List.of(bobId)));

        verify(memberRepository, never()).save(any(ConversationMember.class));
        verify(messageService, never()).postSystemMessage(any(), any(), anyString());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void addMembers_nonAdmin_throwsNotAdmin() {
        groupExistsAndCallerIs(ConversationMember.Role.MEMBER); // member, not admin

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> groupService.addMembers(adminId, conversationId,
                        new AddMemberRequest(List.of(bobId))));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.NOT_ADMIN);
        assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        verify(memberRepository, never()).save(any(ConversationMember.class));
    }

    @Test
    void addMembers_callerNotMember_throwsNotMember() {
        when(conversationRepository.findById(conversationId))
                .thenReturn(Optional.of(groupConversation()));
        when(memberRepository.findByConversation_IdAndUser_Id(conversationId, adminId))
                .thenReturn(Optional.empty());

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> groupService.addMembers(adminId, conversationId,
                        new AddMemberRequest(List.of(bobId))));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.NOT_MEMBER);
    }

    @Test
    void addMembers_unknownUser_throwsUserNotFound() {
        groupExistsAndCallerIs(ConversationMember.Role.ADMIN);
        when(userRepository.findActiveUsersByIdsForUpdate(sortedIds(adminId, bobId)))
                .thenReturn(List.of(admin())); // missing bob

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> groupService.addMembers(adminId, conversationId,
                        new AddMemberRequest(List.of(bobId))));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.USER_NOT_FOUND);
    }

    @Test
    void addMembers_emptyList_throwsValidation() {
        groupExistsAndCallerIs(ConversationMember.Role.ADMIN);

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> groupService.addMembers(adminId, conversationId,
                        new AddMemberRequest(List.of())));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
    }

    @Test
    void addMembers_directConversation_throwsValidation() {
        when(conversationRepository.findById(conversationId))
                .thenReturn(Optional.of(directConversation()));

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> groupService.addMembers(adminId, conversationId,
                        new AddMemberRequest(List.of(bobId))));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
        verify(memberRepository, never()).save(any(ConversationMember.class));
    }

    @Test
    void addMembers_missingConversation_throwsNotFound() {
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.empty());

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> groupService.addMembers(adminId, conversationId,
                        new AddMemberRequest(List.of(bobId))));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.CONVERSATION_NOT_FOUND);
    }

    // --------------------------------------------------------- removeMember

    @Test
    void removeMember_adminRemovesMember_deletesAndBroadcasts() {
        Conversation conversation = groupConversation();
        when(conversationRepository.findById(conversationId))
                .thenReturn(Optional.of(conversation));
        ConversationMember adminMember =
                member(conversation, adminId, "Admin Alice", ConversationMember.Role.ADMIN);
        ConversationMember bobMember =
                member(conversation, bobId, "Bob", ConversationMember.Role.MEMBER);
        when(memberRepository.findByConversation_IdAndUser_Id(conversationId, adminId))
                .thenReturn(Optional.of(adminMember));
        when(memberRepository.findByConversation_IdAndUser_Id(conversationId, bobId))
                .thenReturn(Optional.of(bobMember));
        when(memberRepository.findMembers(conversationId))
                .thenReturn(List.of(new MemberResponse(
                        adminId, "Admin Alice", "+84901110001", null,
                        ConversationMember.Role.ADMIN, Instant.now())));

        groupService.removeMember(adminId, conversationId, bobId);

        verify(memberRepository).delete(bobMember);
        verify(messageService).postSystemMessage(
                eq(adminId), eq(conversationId), eq("Admin Alice removed Bob"), eq(bobId));
        ConversationUpdatedEvent event = updateEvent().getValue();
        assertThat(event.payload().change()).isEqualTo("MEMBER_REMOVED");
        verify(eventPublisher).publishEvent(
                new ConversationMembershipChangedEvent(conversationId, List.of(bobId)));
        // The kicked user is no longer a member at fan-out time — they are
        // pushed explicitly so their inbox drops the conversation.
        assertThat(event.extraAudienceUserIds()).containsExactly(bobId);
    }

    @Test
    void removeMember_nonAdminCaller_throwsNotAdmin() {
        Conversation conversation = groupConversation();
        when(conversationRepository.findById(conversationId))
                .thenReturn(Optional.of(conversation));
        when(memberRepository.findByConversation_IdAndUser_Id(conversationId, bobId))
                .thenReturn(Optional.of(
                        member(conversation, bobId, "Bob", ConversationMember.Role.MEMBER)));

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> groupService.removeMember(bobId, conversationId, carolId));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.NOT_ADMIN);
        verify(memberRepository, never()).delete(any(ConversationMember.class));
    }

    @Test
    void removeMember_targetNotMember_throwsNotMember() {
        groupExistsAndCallerIs(ConversationMember.Role.ADMIN);
        when(memberRepository.findByConversation_IdAndUser_Id(conversationId, carolId))
                .thenReturn(Optional.empty());

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> groupService.removeMember(adminId, conversationId, carolId));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.NOT_MEMBER);
        verify(memberRepository, never()).delete(any(ConversationMember.class));
    }

    @Test
    void removeMember_soleAdminSelfDelete_withMembersBlocked() {
        Conversation conversation = groupConversation();
        when(conversationRepository.findById(conversationId))
                .thenReturn(Optional.of(conversation));
        when(memberRepository.findByConversation_IdAndUser_Id(conversationId, adminId))
                .thenReturn(Optional.of(
                        member(conversation, adminId, "Admin Alice", ConversationMember.Role.ADMIN)));
        when(memberRepository.countByConversation_IdAndRole(
                conversationId, ConversationMember.Role.ADMIN)).thenReturn(1L);
        when(memberRepository.countByConversation_Id(conversationId)).thenReturn(2L);

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> groupService.removeMember(adminId, conversationId, adminId));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
        verify(memberRepository, never()).delete(any(ConversationMember.class));
    }

    @Test
    void removeMember_secondAdminCanBeRemoved() {
        Conversation conversation = groupConversation();
        when(conversationRepository.findById(conversationId))
                .thenReturn(Optional.of(conversation));
        when(memberRepository.findByConversation_IdAndUser_Id(conversationId, adminId))
                .thenReturn(Optional.of(
                        member(conversation, adminId, "Admin Alice", ConversationMember.Role.ADMIN)));
        ConversationMember bobAdmin =
                member(conversation, bobId, "Bob", ConversationMember.Role.ADMIN);
        when(memberRepository.findByConversation_IdAndUser_Id(conversationId, bobId))
                .thenReturn(Optional.of(bobAdmin));
        when(memberRepository.countByConversation_IdAndRole(
                conversationId, ConversationMember.Role.ADMIN)).thenReturn(2L);
        when(memberRepository.findMembers(conversationId)).thenReturn(List.of());

        groupService.removeMember(adminId, conversationId, bobId);

        verify(memberRepository).delete(bobAdmin);
    }

    // ---------------------------------------------------------------- leave

    @Test
    void leave_memberLeaves_postsLeftAndBroadcasts() {
        Conversation conversation = groupConversation();
        when(conversationRepository.findById(conversationId))
                .thenReturn(Optional.of(conversation));
        ConversationMember bobMember =
                member(conversation, bobId, "Bob", ConversationMember.Role.MEMBER);
        when(memberRepository.findByConversation_IdAndUser_Id(conversationId, bobId))
                .thenReturn(Optional.of(bobMember));
        when(memberRepository.findMembers(conversationId)).thenReturn(List.of());

        groupService.leave(bobId, conversationId);

        verify(memberRepository).delete(bobMember);
        verify(messageService).postSystemMessage(
                eq(bobId), eq(conversationId), eq("Bob left the group"), isNull());
        ConversationUpdatedEvent event = updateEvent().getValue();
        assertThat(event.payload().change()).isEqualTo("MEMBER_LEFT");
        assertThat(event.extraAudienceUserIds()).containsExactly(bobId);
    }

    @Test
    void leave_soleAdminWithMembersBlocked() {
        Conversation conversation = groupConversation();
        when(conversationRepository.findById(conversationId))
                .thenReturn(Optional.of(conversation));
        when(memberRepository.findByConversation_IdAndUser_Id(conversationId, adminId))
                .thenReturn(Optional.of(
                        member(conversation, adminId, "Admin Alice", ConversationMember.Role.ADMIN)));
        when(memberRepository.countByConversation_IdAndRole(
                conversationId, ConversationMember.Role.ADMIN)).thenReturn(1L);
        when(memberRepository.countByConversation_Id(conversationId)).thenReturn(3L);

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> groupService.leave(adminId, conversationId));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
        assertThat(ex.getMessage()).contains("admin");
        verify(memberRepository, never()).delete(any(ConversationMember.class));
    }

    @Test
    void leaveForAccountDeletion_promotesSuccessorBeforeSoleAdminLeaves() {
        Conversation conversation = groupConversation();
        when(conversationRepository.findByIdForUpdate(conversationId))
                .thenReturn(Optional.of(conversation));
        when(conversationRepository.findById(conversationId))
                .thenReturn(Optional.of(conversation));
        ConversationMember adminMember =
                member(conversation, adminId, "Admin Alice", ConversationMember.Role.ADMIN);
        ConversationMember bobMember =
                member(conversation, bobId, "Bob", ConversationMember.Role.MEMBER);
        when(memberRepository.findByConversation_IdAndUser_Id(conversationId, adminId))
                .thenReturn(Optional.of(adminMember), Optional.of(adminMember));
        when(memberRepository.countByConversation_IdAndRole(
                conversationId, ConversationMember.Role.ADMIN)).thenReturn(1L);
        when(memberRepository.countByConversation_Id(conversationId)).thenReturn(2L);
        when(memberRepository.findSuccessorUserIdForAccountDeletion(conversationId, adminId))
                .thenReturn(Optional.of(bobId));
        when(memberRepository.findByConversation_IdAndUser_Id(conversationId, bobId))
                .thenReturn(Optional.of(bobMember));
        when(memberRepository.findMembers(conversationId)).thenReturn(List.of());

        groupService.leaveForAccountDeletion(adminId, conversationId);

        assertThat(bobMember.getRole()).isEqualTo(ConversationMember.Role.ADMIN);
        verify(memberRepository).save(bobMember);
        verify(messageService).postSystemMessage(
                eq(adminId), eq(conversationId), eq("Admin Alice made Bob a group admin"));
        verify(memberRepository).delete(adminMember);
    }

    @Test
    void leave_lastMemberLeaves_emptyGroupAllowed() {
        Conversation conversation = groupConversation();
        when(conversationRepository.findById(conversationId))
                .thenReturn(Optional.of(conversation));
        ConversationMember adminMember =
                member(conversation, adminId, "Admin Alice", ConversationMember.Role.ADMIN);
        when(memberRepository.findByConversation_IdAndUser_Id(conversationId, adminId))
                .thenReturn(Optional.of(adminMember));
        when(memberRepository.countByConversation_IdAndRole(
                conversationId, ConversationMember.Role.ADMIN)).thenReturn(1L);
        when(memberRepository.countByConversation_Id(conversationId)).thenReturn(1L); // alone
        when(memberRepository.findMembers(conversationId)).thenReturn(List.of());

        groupService.leave(adminId, conversationId);

        verify(memberRepository).delete(adminMember); // allowed — empty group stays
    }

    @Test
    void leave_notMember_throwsNotMember() {
        when(conversationRepository.findById(conversationId))
                .thenReturn(Optional.of(groupConversation()));
        when(memberRepository.findByConversation_IdAndUser_Id(conversationId, bobId))
                .thenReturn(Optional.empty());

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> groupService.leave(bobId, conversationId));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.NOT_MEMBER);
    }

    // ------------------------------------------------------------ update

    @Test
    void updateGroup_titleChanged_postsRenamedSystem() {
        groupExistsAndCallerIs(ConversationMember.Role.ADMIN);
        when(memberRepository.findMembers(conversationId)).thenReturn(List.of());

        ConversationUpdatePayload payload = groupService.updateGroup(
                adminId, conversationId, new PatchGroupRequest("  New Name  ", null));

        assertThat(payload.title()).isEqualTo("New Name");
        assertThat(payload.change()).isEqualTo("UPDATED");
        verify(messageService).postSystemMessage(
                eq(adminId), eq(conversationId), contains("renamed the group to"));
    }

    @Test
    void updateGroup_sameTitle_noSystemLine() {
        groupExistsAndCallerIs(ConversationMember.Role.ADMIN);
        when(memberRepository.findMembers(conversationId)).thenReturn(List.of());

        ConversationUpdatePayload payload = groupService.updateGroup(
                adminId, conversationId, new PatchGroupRequest("Old Name", null));

        assertThat(payload.title()).isEqualTo("Old Name");
        verify(messageService, never()).postSystemMessage(any(), any(), anyString());
        updateEvent(); // CONVERSATION_UPDATED still broadcast
    }

    @Test
    void updateGroup_avatarOnly_noSystemLine() {
        groupExistsAndCallerIs(ConversationMember.Role.ADMIN);
        when(memberRepository.findMembers(conversationId)).thenReturn(List.of());

        ConversationUpdatePayload payload = groupService.updateGroup(
                adminId, conversationId, new PatchGroupRequest(null, " /api/v1/files/avatars/av.png "));

        assertThat(payload.avatarUrl()).isEqualTo("/api/v1/files/avatars/av.png");
        verify(messageService, never()).postSystemMessage(any(), any(), anyString());
    }

    @Test
    void updateGroup_rejectsMissingLocalAttachmentAvatar() {
        groupExistsAndCallerIs(ConversationMember.Role.ADMIN);
        String storageKey = "attachments/00000000-0000-0000-0000-000000000001.png";
        when(attachmentRepository.lockStorageKeyForReference(storageKey)).thenReturn(Optional.empty());

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> groupService.updateGroup(adminId, conversationId,
                        new PatchGroupRequest(null, "/api/v1/files/" + storageKey)));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.ATTACHMENT_NOT_FOUND);
        verify(attachmentRepository).lockStorageKeyForReference(storageKey);
    }

    @Test
    void updateGroup_locksAbsoluteAttachmentAvatarUrl() {
        groupExistsAndCallerIs(ConversationMember.Role.ADMIN);
        when(memberRepository.findMembers(conversationId)).thenReturn(List.of());
        String storageKey = "attachments/00000000-0000-0000-0000-000000000002.png";
        when(attachmentRepository.lockStorageKeyForReference(storageKey)).thenReturn(Optional.of(storageKey));
        when(storageService.exists(storageKey)).thenReturn(true);
        String avatarUrl = "https://nexachat.example/api/v1/files/" + storageKey + "?v=1";

        ConversationUpdatePayload payload = groupService.updateGroup(adminId, conversationId,
                new PatchGroupRequest(null, avatarUrl));

        assertThat(payload.avatarUrl()).isEqualTo("/api/v1/files/" + storageKey + "?v=1");
        verify(attachmentRepository).lockStorageKeyForReference(storageKey);
    }

    @Test
    void updateGroup_rejectsLocalAttachmentAvatarWhenFileIsMissing() {
        groupExistsAndCallerIs(ConversationMember.Role.ADMIN);
        String storageKey = "attachments/00000000-0000-0000-0000-000000000003.png";
        when(attachmentRepository.lockStorageKeyForReference(storageKey)).thenReturn(Optional.of(storageKey));
        when(storageService.exists(storageKey)).thenReturn(false);

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> groupService.updateGroup(adminId, conversationId,
                        new PatchGroupRequest(null, "/api/v1/files/" + storageKey)));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.ATTACHMENT_NOT_FOUND);
        verify(storageService).exists(storageKey);
    }

    @Test
    void updateGroup_canonicalizesPercentEncodedAttachmentAvatarPath() {
        groupExistsAndCallerIs(ConversationMember.Role.ADMIN);
        when(memberRepository.findMembers(conversationId)).thenReturn(List.of());
        String storageKey = "attachments/00000000-0000-0000-0000-00000000000a.png";
        when(attachmentRepository.lockStorageKeyForReference(storageKey)).thenReturn(Optional.of(storageKey));
        when(storageService.exists(storageKey)).thenReturn(true);
        String encodedUrl = "/api/v1/files/attachments/00000000-0000-0000-0000-00000000000%61.png?v=1";

        ConversationUpdatePayload payload = groupService.updateGroup(adminId, conversationId,
                new PatchGroupRequest(null, encodedUrl));

        assertThat(payload.avatarUrl()).isEqualTo(
                "/api/v1/files/" + storageKey + "?v=1");
        verify(attachmentRepository).lockStorageKeyForReference(storageKey);
    }

    @Test
    void updateGroup_rejectsOffOriginAvatar() {
        groupExistsAndCallerIs(ConversationMember.Role.ADMIN);

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> groupService.updateGroup(adminId, conversationId,
                        new PatchGroupRequest(null, "https://evil.example/track.png")));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void updateGroup_emptyAvatarString_clearsAvatar() {
        groupExistsAndCallerIs(ConversationMember.Role.ADMIN);
        when(memberRepository.findMembers(conversationId)).thenReturn(List.of());

        ConversationUpdatePayload payload = groupService.updateGroup(
                adminId, conversationId, new PatchGroupRequest(null, "   "));

        assertThat(payload.avatarUrl()).isNull();
    }

    @Test
    void updateGroup_nothingProvided_throwsValidation() {
        groupExistsAndCallerIs(ConversationMember.Role.ADMIN);

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> groupService.updateGroup(
                        adminId, conversationId, new PatchGroupRequest(null, null)));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
    }

    @Test
    void updateGroup_blankTitle_throwsValidation() {
        groupExistsAndCallerIs(ConversationMember.Role.ADMIN);

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> groupService.updateGroup(
                        adminId, conversationId, new PatchGroupRequest("   ", null)));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
    }

    @Test
    void updateGroup_nonAdmin_throwsNotAdmin() {
        groupExistsAndCallerIs(ConversationMember.Role.MEMBER);

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> groupService.updateGroup(
                        adminId, conversationId, new PatchGroupRequest("New", null)));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.NOT_ADMIN);
    }

    @Test
    void updateGroup_notMember_throwsNotMember() {
        // Membership is checked before the admin role: an outsider patching a
        // group must see NOT_MEMBER, never NOT_ADMIN (same ordering as the
        // GroupFlowIT PATCH probe).
        when(conversationRepository.findById(conversationId))
                .thenReturn(Optional.of(groupConversation()));
        when(memberRepository.findByConversation_IdAndUser_Id(conversationId, adminId))
                .thenReturn(Optional.empty());

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> groupService.updateGroup(
                        adminId, conversationId, new PatchGroupRequest("Hijack", null)));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.NOT_MEMBER);
        assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        verify(messageService, never()).postSystemMessage(any(), any(), anyString());
        verify(eventPublisher, never()).publishEvent(any());
    }

    // ------------------------------------------------------------ avatar --

    /** Minimal valid PNG: signature + IHDR skeleton — enough for MagicBytes. */
    private static final byte[] PNG_BYTES = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0, 0, 0, 0x0D, 0x49, 0x48, 0x44, 0x52,
    };

    private MockMultipartFile pngFile() {
        return new MockMultipartFile("file", "a.png", "image/png", PNG_BYTES);
    }

    @Test
    void setGroupAvatar_happyPath_storesAndBroadcasts() throws Exception {
        groupExistsAndCallerIs(ConversationMember.Role.ADMIN);
        when(memberRepository.findMembers(conversationId)).thenReturn(List.of());
        when(storageService.store(eq("avatars"), any(), any(), any()))
                .thenReturn(new StorageService.StoredFile("avatars/k.png", 16, "image/png"));
        when(storageService.resolveUrl("avatars/k.png"))
                .thenReturn("/api/v1/files/avatars/k.png");

        var resp = groupService.setGroupAvatar(adminId, conversationId, pngFile());

        assertThat(resp.avatarUrl()).isEqualTo("/api/v1/files/avatars/k.png");
        ConversationUpdatedEvent ev = updateEvent().getValue();
        assertThat(ev.payload().avatarUrl()).isEqualTo("/api/v1/files/avatars/k.png");
        verify(messageService, never()).postSystemMessage(any(), any(), anyString());
    }

    @Test
    void setGroupAvatar_memberRole_forbidden() {
        groupExistsAndCallerIs(ConversationMember.Role.MEMBER);

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> groupService.setGroupAvatar(adminId, conversationId, pngFile()));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.NOT_ADMIN);
        verify(storageService, never()).store(any(), any(), any(), any());
    }

    @Test
    void setGroupAvatar_nonImage_rejected() {
        groupExistsAndCallerIs(ConversationMember.Role.ADMIN);
        MockMultipartFile text = new MockMultipartFile(
                "file", "evil.png", "image/png", "definitely not a png".getBytes());

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> groupService.setGroupAvatar(adminId, conversationId, text));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.MIME_NOT_ALLOWED);
        verify(storageService, never()).store(any(), any(), any(), any());
    }

    @Test
    void setGroupAvatar_oversize_rejectedBeforeBuffering() throws Exception {
        groupExistsAndCallerIs(ConversationMember.Role.ADMIN);
        MultipartFile oversized = mock(MultipartFile.class);
        when(oversized.isEmpty()).thenReturn(false);
        when(oversized.getSize()).thenReturn(StorageService.MAX_FILE_SIZE_BYTES + 1);

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> groupService.setGroupAvatar(adminId, conversationId, oversized));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.FILE_TOO_LARGE);
        verify(oversized, never()).getBytes();
        verify(storageService, never()).store(any(), any(), any(), any());
    }
}
