package com.nexachat.contact;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.contact.dto.ContactResponse;
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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContactServiceTest {

    @Mock
    private ContactRepository contactRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private PhoneService phoneService;

    private ContactService contactService;

    private final UUID ownerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // lenient: the pass-through save covers every happy-path insert.
        lenient().when(contactRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        contactService = new ContactService(contactRepository, userRepository, phoneService);
    }

    private User userWithId(String phone, String displayName) {
        User user = User.create(phone, "hash", displayName);
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }

    private User owner() {
        User owner = userWithId("+84901112233", "Owner");
        ReflectionTestUtils.setField(owner, "id", ownerId);
        return owner;
    }

    // ------------------------------------------------------------------ add

    @Test
    void add_success_normalizesFindsAndPersists() {
        User target = userWithId("+84987776655", "Lan");
        when(phoneService.toE164("0987776655")).thenReturn("+84987776655");
        when(userRepository.findByPhone("+84987776655")).thenReturn(Optional.of(target));
        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner()));

        ContactResponse response = contactService.add(ownerId, "0987776655");

        assertThat(response.contactUserId()).isEqualTo(target.getId());
        assertThat(response.phone()).isEqualTo("+84987776655");
        assertThat(response.displayName()).isEqualTo("Lan");
        assertThat(response.aliasName()).isNull();

        // The E.164 result, not the raw input, is what hits the database.
        ArgumentCaptor<Contact> saved = ArgumentCaptor.forClass(Contact.class);
        verify(contactRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getOwner().getId()).isEqualTo(ownerId);
        assertThat(saved.getValue().getContactUser().getId()).isEqualTo(target.getId());
        verify(phoneService).toE164("0987776655");
    }

    @Test
    void add_unknownPhone_throwsUserNotFound404() {
        when(phoneService.toE164(anyString())).thenReturn("+84900000001");
        when(userRepository.findByPhone("+84900000001")).thenReturn(Optional.empty());

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> contactService.add(ownerId, "0900000001"));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.USER_NOT_FOUND);
        assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        verify(contactRepository, never()).saveAndFlush(any());
    }

    @Test
    void add_duplicatePk_throwsAlreadyContact409() {
        User target = userWithId("+84987776655", "Lan");
        when(phoneService.toE164(anyString())).thenReturn("+84987776655");
        when(userRepository.findByPhone("+84987776655")).thenReturn(Optional.of(target));
        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner()));
        when(contactRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("contacts_pkey"));

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> contactService.add(ownerId, "+84987776655"));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.ALREADY_CONTACT);
        assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT.value());
    }

    @Test
    void add_self_throwsValidationFailedBeforeInsert() {
        User self = owner();
        when(phoneService.toE164(anyString())).thenReturn("+84901112233");
        when(userRepository.findByPhone("+84901112233")).thenReturn(Optional.of(self));

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> contactService.add(ownerId, "+84901112233"));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
        assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        // Service guard fires first — the DB CHECK is only a backstop.
        verify(contactRepository, never()).saveAndFlush(any());
    }

    @Test
    void add_invalidPhone_throwsValidationFailed() {
        when(phoneService.toE164(anyString())).thenThrow(new IllegalArgumentException("not a phone"));

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> contactService.add(ownerId, "abc"));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
        verify(userRepository, never()).findByPhone(anyString());
        verify(contactRepository, never()).saveAndFlush(any());
    }

    // ----------------------------------------------------------------- list

    @Test
    void list_delegatesToProjectionQuery() {
        ContactResponse row = new ContactResponse(UUID.randomUUID(), "+8498", "Lan", "L", null);
        when(contactRepository.findAllByOwnerId(ownerId)).thenReturn(List.of(row));

        assertThat(contactService.list(ownerId)).containsExactly(row);
    }

    // ------------------------------------------------------------ updateAlias

    @Test
    void updateAlias_setValue_persistsAndReturnsView() {
        User target = userWithId("+84987776655", "Lan");
        Contact contact = Contact.add(owner(), target);
        ContactId id = new ContactId(ownerId, target.getId());
        when(contactRepository.findById(id)).thenReturn(Optional.of(contact));

        ContactResponse response = contactService.updateAlias(ownerId, target.getId(), "  Bạn Lan  ");

        assertThat(response.aliasName()).isEqualTo("Bạn Lan");
        assertThat(response.displayName()).isEqualTo("Lan");
        verify(contactRepository).save(contact);
    }

    @Test
    void updateAlias_null_clearsAlias() {
        User target = userWithId("+84987776655", "Lan");
        Contact contact = Contact.add(owner(), target);
        contact.changeAlias("old");
        when(contactRepository.findById(new ContactId(ownerId, target.getId())))
                .thenReturn(Optional.of(contact));

        ContactResponse response = contactService.updateAlias(ownerId, target.getId(), null);

        assertThat(response.aliasName()).isNull();
        verify(contactRepository).save(contact);
    }

    @Test
    void updateAlias_blank_throwsValidationFailed() {
        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> contactService.updateAlias(ownerId, UUID.randomUUID(), "   "));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
        verify(contactRepository, never()).findById(any());
    }

    @Test
    void updateAlias_missingContact_throwsUserNotFound404() {
        UUID targetId = UUID.randomUUID();
        when(contactRepository.findById(new ContactId(ownerId, targetId))).thenReturn(Optional.empty());

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> contactService.updateAlias(ownerId, targetId, "x"));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.USER_NOT_FOUND);
        assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
    }

    // --------------------------------------------------------------- delete

    @Test
    void delete_existing_removesRow() {
        Contact contact = Contact.add(owner(), userWithId("+84987776655", "Lan"));
        ContactId id = new ContactId(ownerId, contact.getContactUser().getId());
        when(contactRepository.findById(id)).thenReturn(Optional.of(contact));

        contactService.delete(ownerId, contact.getContactUser().getId());

        verify(contactRepository).delete(contact);
    }

    @Test
    void delete_missing_isSilentNoOp() {
        UUID targetId = UUID.randomUUID();
        when(contactRepository.findById(new ContactId(ownerId, targetId))).thenReturn(Optional.empty());

        // Idempotent contract: no exception, no delete.
        contactService.delete(ownerId, targetId);

        verify(contactRepository, never()).delete((Contact) any());
    }
}
