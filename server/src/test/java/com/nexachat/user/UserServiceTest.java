package com.nexachat.user;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.contact.ContactRepository;
import com.nexachat.user.dto.UserResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

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
class UserServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private PhoneService phoneService;
    @Mock
    private ContactRepository contactRepository;

    @InjectMocks
    private UserService userService;

    private static User userWithId(String displayName) {
        User user = User.create("+84912345678", "hash", displayName);
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }

    @Test
    void getById_missingUser_throwsUserNotFound404() {
        UUID id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.empty());

        ApiException ex = catchThrowableOfType(ApiException.class, () -> userService.getById(id));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.USER_NOT_FOUND);
        assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
    }

    @Test
    void updateDisplayName_trimsAndPersists() {
        User user = userWithId("Old Name");
        when(userRepository.findByIdForUpdate(user.getId())).thenReturn(Optional.of(user));

        User updated = userService.updateDisplayName(user.getId(), "  Lan Nguyen  ");

        assertThat(updated.getDisplayName()).isEqualTo("Lan Nguyen");
        verify(userRepository).save(user);
    }

    @Test
    void updateDisplayName_blank_throwsValidationFailed() {
        User user = userWithId("Old Name");
        when(userRepository.findByIdForUpdate(user.getId())).thenReturn(Optional.of(user));

        ApiException ex = catchThrowableOfType(ApiException.class, 
                () -> userService.updateDisplayName(user.getId(), "   "));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
        assertThat(user.getDisplayName()).isEqualTo("Old Name");
        verify(userRepository, never()).save(user);
    }

    @Test
    void updateDisplayName_null_isNoOp() {
        User user = userWithId("Old Name");
        when(userRepository.findByIdForUpdate(user.getId())).thenReturn(Optional.of(user));

        User updated = userService.updateDisplayName(user.getId(), null);

        assertThat(updated.getDisplayName()).isEqualTo("Old Name");
        verify(userRepository, never()).save(user);
    }

    // --------------------------------------------------------------- search

    @Test
    void search_phoneExact_normalizesAndReturnsSingleUser() {
        UUID callerId = UUID.randomUUID();
        User found = userWithId("Lan");
        when(phoneService.toE164("0987776655")).thenReturn("+84987776655");
        when(userRepository.findByPhoneAndDeletedAtIsNull("+84987776655")).thenReturn(Optional.of(found));

        List<UserResponse> results = userService.search(callerId, "0987776655", null, null);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).id()).isEqualTo(found.getId());
        assertThat(results.get(0).phone()).isEqualTo("+84912345678");
        verify(phoneService).toE164("0987776655");
    }

    @Test
    void search_phoneUnknown_returnsEmptyListNot404() {
        when(phoneService.toE164(anyString())).thenReturn("+84900000001");
        when(userRepository.findByPhoneAndDeletedAtIsNull("+84900000001")).thenReturn(Optional.empty());

        List<UserResponse> results = userService.search(UUID.randomUUID(), "0900000001", null, null);

        assertThat(results).isEmpty();
    }

    @Test
    void search_phoneInvalid_throwsValidationFailed() {
        when(phoneService.toE164(anyString())).thenThrow(new IllegalArgumentException("bad"));

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> userService.search(UUID.randomUUID(), "not-a-phone", null, null));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
        verify(userRepository, never()).findByPhoneAndDeletedAtIsNull(anyString());
    }

    @Test
    void search_q_prefixExcludesCallerAndCapsAt20() {
        UUID callerId = UUID.randomUUID();
        User first = userWithId("Lan A");
        User second = userWithId("Lan B");
        when(userRepository.searchByDisplayNamePrefix(eq("Lan"), eq(callerId), any(Pageable.class)))
                .thenReturn(List.of(first, second));
        when(contactRepository.findContactUserIdsAmong(eq(callerId), any()))
                .thenReturn(List.of());

        List<UserResponse> results = userService.search(callerId, null, null, " Lan ");

        assertThat(results).hasSize(2);
        assertThat(results).extracting(UserResponse::phone).containsOnlyNulls();
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(userRepository).searchByDisplayNamePrefix(eq("Lan"), eq(callerId), pageable.capture());
        // Exactly the 20-row contract cap.
        assertThat(pageable.getValue().getPageSize()).isEqualTo(20);
    }

    @Test
    void search_q_disclosesPhoneOnlyForContacts() {
        UUID callerId = UUID.randomUUID();
        User contact = userWithId("Lan Contact");
        User stranger = userWithId("Lan Stranger");
        when(userRepository.searchByDisplayNamePrefix(eq("Lan"), eq(callerId), any(Pageable.class)))
                .thenReturn(List.of(contact, stranger));
        when(contactRepository.findContactUserIdsAmong(eq(callerId), any()))
                .thenReturn(List.of(contact.getId()));

        List<UserResponse> results = userService.search(callerId, null, null, "Lan");

        assertThat(results).hasSize(2);
        assertThat(results.get(0).id()).isEqualTo(contact.getId());
        assertThat(results.get(0).phone()).isEqualTo(contact.getPhone());
        assertThat(results.get(1).id()).isEqualTo(stranger.getId());
        assertThat(results.get(1).phone()).isNull();
    }

    @Test
    void search_q_escapesLikeWildcards() {
        UUID callerId = UUID.randomUUID();
        when(userRepository.searchByDisplayNamePrefix(anyString(), eq(callerId), any(Pageable.class)))
                .thenReturn(List.of());

        userService.search(callerId, null, null, "10%_off");

        // % and _ reach the query as escaped literals, not LIKE wildcards.
        verify(userRepository).searchByDisplayNamePrefix(
                eq("10\\%\\_off"), eq(callerId), any(Pageable.class));
    }

    @Test
    void search_bothParams_throwsValidationFailed() {
        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> userService.search(UUID.randomUUID(), "+84912345678", null, "Lan"));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
        verify(userRepository, never()).findByPhoneAndDeletedAtIsNull(anyString());
    }

    @Test
    void search_neitherParam_throwsValidationFailed() {
        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> userService.search(UUID.randomUUID(), null, null, "  "));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
        verify(userRepository, never()).findByPhoneAndDeletedAtIsNull(anyString());
    }

    @Test
    void search_emailExact_trimsCaseAndReturnsSingleUser() {
        UUID callerId = UUID.randomUUID();
        User found = userWithId("Lan");
        found.changeEmail("verified@example.com");
        when(userRepository.findActiveByVerifiedEmail("verified@example.com")).thenReturn(Optional.of(found));

        List<UserResponse> results = userService.search(callerId, null, "  Verified@Example.com ", null);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).id()).isEqualTo(found.getId());
    }

    @Test
    void search_emailUnknown_returnsEmptyList() {
        when(userRepository.findActiveByVerifiedEmail("missing@example.com")).thenReturn(Optional.empty());

        List<UserResponse> results = userService.search(UUID.randomUUID(), null, "missing@example.com", null);

        assertThat(results).isEmpty();
    }

    @Test
    void search_emailSelf_returnsEmptyList() {
        UUID callerId = UUID.randomUUID();
        User self = userWithId("Me");
        ReflectionTestUtils.setField(self, "id", callerId);
        self.changeEmail("me@example.com");
        when(userRepository.findActiveByVerifiedEmail("me@example.com")).thenReturn(Optional.of(self));

        List<UserResponse> results = userService.search(callerId, null, "me@example.com", null);

        assertThat(results).isEmpty();
    }

    @Test
    void search_threeParams_throwsValidationFailed() {
        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> userService.search(UUID.randomUUID(), "+84912345678", "x@example.com", "Lan"));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
    }
}
