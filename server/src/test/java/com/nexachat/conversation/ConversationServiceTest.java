package com.nexachat.conversation;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.dto.ConversationMemberView;
import com.nexachat.conversation.dto.OpenDirectRequest;
import com.nexachat.user.PhoneService;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationServiceTest {

    @Mock
    private ConversationRepository conversationRepository;
    @Mock
    private ConversationMemberRepository memberRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private PhoneService phoneService;
    @Mock
    private DirectConversationCreator directConversationCreator;

    private ConversationService conversationService;

    private final UUID callerId = UUID.randomUUID();
    private final UUID otherId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        conversationService = new ConversationService(
                conversationRepository, memberRepository, userRepository,
                phoneService, directConversationCreator);
    }

    private User userWithId(UUID id, String phone, String displayName) {
        User user = User.create(phone, "hash", displayName);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private User caller() {
        return userWithId(callerId, "+84901112233", "Caller");
    }

    private User other() {
        return userWithId(otherId, "+84987776655", "Other");
    }

    private Conversation conversationWithKey(String key) {
        Conversation conversation = Conversation.direct(caller(), key);
        ReflectionTestUtils.setField(conversation, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(conversation, "createdAt", Instant.now());
        return conversation;
    }

    // ------------------------------------------------------------- directKey

    @Test
    void directKey_isOrderIndependent_andUsesStringNotUuidOrdering() {
        // Chosen so String.compareTo and UUID.compareTo DISAGREE: '7' < '8'
        // lexicographically, but 0x8000… is negative as a signed long while
        // 0x7fff… is positive — UUID.compareTo would order them backwards.
        UUID low = UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff");
        UUID high = UUID.fromString("80000000-0000-0000-0000-000000000000");

        String keyAB = ConversationService.directKey(low, high);
        String keyBA = ConversationService.directKey(high, low);

        assertThat(keyAB).isEqualTo(keyBA);
        assertThat(keyAB).isEqualTo(
                "7fffffff-ffff-ffff-ffff-ffffffffffff:80000000-0000-0000-0000-000000000000");
    }

    // ------------------------------------------------------------ openDirect

    @Test
    void openDirect_byUserId_createsConversationAndMembers() {
        when(userRepository.findById(otherId)).thenReturn(Optional.of(other()));
        when(userRepository.findById(callerId)).thenReturn(Optional.of(caller()));
        when(conversationRepository.findByDirectKey(anyString())).thenReturn(Optional.empty());
        Conversation stored = conversationWithKey("k");
        when(directConversationCreator.createDirect(any(), any(), anyString())).thenReturn(stored);
        when(memberRepository.findMemberViews(stored.getId())).thenReturn(List.of(
                new ConversationMemberView(callerId, "Caller", "+84901112233", false),
                new ConversationMemberView(otherId, "Other", "+84987776655", false)));

        ConversationService.OpenDirectResult result =
                conversationService.openDirect(callerId, new OpenDirectRequest(null, otherId));

        assertThat(result.created()).isTrue();
        assertThat(result.body().id()).isEqualTo(stored.getId());
        assertThat(result.body().type()).isEqualTo("DIRECT");
        assertThat(result.body().members()).hasSize(2);

        // The canonical LEAST:GREATEST key is what gets persisted.
        String expectedKey = ConversationService.directKey(callerId, otherId);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(directConversationCreator).createDirect(any(), any(), key.capture());
        assertThat(key.getValue()).isEqualTo(expectedKey);
    }

    @Test
    void openDirect_existingConversation_returnsItWithoutInsert() {
        when(userRepository.findById(otherId)).thenReturn(Optional.of(other()));
        when(userRepository.findById(callerId)).thenReturn(Optional.of(caller()));
        Conversation stored = conversationWithKey(ConversationService.directKey(callerId, otherId));
        when(conversationRepository.findByDirectKey(stored.getDirectKey()))
                .thenReturn(Optional.of(stored));
        when(memberRepository.findMemberViews(stored.getId())).thenReturn(List.of());

        ConversationService.OpenDirectResult result =
                conversationService.openDirect(callerId, new OpenDirectRequest(null, otherId));

        assertThat(result.created()).isFalse();
        assertThat(result.body().id()).isEqualTo(stored.getId());
        verify(directConversationCreator, never()).createDirect(any(), any(), anyString());
    }

    @Test
    void openDirect_insertRace_returnsWinnersRow() {
        when(userRepository.findById(otherId)).thenReturn(Optional.of(other()));
        when(userRepository.findById(callerId)).thenReturn(Optional.of(caller()));
        String key = ConversationService.directKey(callerId, otherId);
        Conversation winner = conversationWithKey(key);
        // First lookup misses, the post-race re-read finds the winner.
        when(conversationRepository.findByDirectKey(key))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(directConversationCreator.createDirect(any(), any(), eq(key)))
                .thenThrow(new DataIntegrityViolationException("conversations_direct_key_key"));
        when(memberRepository.findMemberViews(winner.getId())).thenReturn(List.of());

        ConversationService.OpenDirectResult result =
                conversationService.openDirect(callerId, new OpenDirectRequest(null, otherId));

        assertThat(result.created()).isFalse();
        assertThat(result.body().id()).isEqualTo(winner.getId());
    }

    @Test
    void openDirect_byPhone_normalizesAndResolves() {
        when(phoneService.toE164("0987776655")).thenReturn("+84987776655");
        when(userRepository.findByPhoneAndDeletedAtIsNull("+84987776655")).thenReturn(Optional.of(other()));
        when(userRepository.findById(callerId)).thenReturn(Optional.of(caller()));
        Conversation stored = conversationWithKey("k");
        when(conversationRepository.findByDirectKey(anyString())).thenReturn(Optional.of(stored));
        when(memberRepository.findMemberViews(stored.getId())).thenReturn(List.of());

        ConversationService.OpenDirectResult result =
                conversationService.openDirect(callerId, new OpenDirectRequest("0987776655", null));

        assertThat(result.body().id()).isEqualTo(stored.getId());
        verify(phoneService).toE164("0987776655");
        verify(userRepository).findByPhoneAndDeletedAtIsNull("+84987776655");
    }

    @Test
    void openDirect_unknownUserId_throwsUserNotFound404() {
        when(userRepository.findById(otherId)).thenReturn(Optional.empty());

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> conversationService.openDirect(callerId, new OpenDirectRequest(null, otherId)));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.USER_NOT_FOUND);
        assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        verify(directConversationCreator, never()).createDirect(any(), any(), anyString());
    }

    @Test
    void openDirect_unknownPhone_throwsUserNotFound404() {
        when(phoneService.toE164(anyString())).thenReturn("+84900000001");
        when(userRepository.findByPhoneAndDeletedAtIsNull("+84900000001")).thenReturn(Optional.empty());

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> conversationService.openDirect(callerId, new OpenDirectRequest("0900000001", null)));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.USER_NOT_FOUND);
        verify(directConversationCreator, never()).createDirect(any(), any(), anyString());
    }

    @Test
    void openDirect_self_throwsValidationFailed() {
        when(userRepository.findById(callerId)).thenReturn(Optional.of(caller()));

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> conversationService.openDirect(callerId, new OpenDirectRequest(null, callerId)));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
        assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        verify(conversationRepository, never()).findByDirectKey(anyString());
        verify(directConversationCreator, never()).createDirect(any(), any(), anyString());
    }

    @Test
    void openDirect_bothParams_throwsValidationFailed() {
        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> conversationService.openDirect(
                        callerId, new OpenDirectRequest("+84987776655", otherId)));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
        verify(userRepository, never()).findById(any());
    }

    @Test
    void openDirect_neitherParam_throwsValidationFailed() {
        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> conversationService.openDirect(callerId, new OpenDirectRequest(null, null)));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
        verify(userRepository, never()).findById(any());
    }
}
