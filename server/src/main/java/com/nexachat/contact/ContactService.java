package com.nexachat.contact;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.contact.dto.ContactResponse;
import com.nexachat.recovery.EmailOtpService;
import com.nexachat.user.PhoneService;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Contacts CRUD (plan §10.2).
 *
 * <p>Add-by-phone always normalizes first — the stored identity is E.164, so
 * {@code 0987…} and {@code +84987…} resolve to the same user. Add-by-email uses
 * the same normalization and validation as recovery email verification and only
 * matches active users with a verified address ({@code users_verified_email_active_key}).
 * The {@code (owner, contact)} PK is the final arbiter of duplicates (check-then-
 * insert can race), mapped to {@code ALREADY_CONTACT} via
 * {@code saveAndFlush} + {@link DataIntegrityViolationException}, exactly like
 * {@code AuthService.register}'s phone-UNIQUE handling.
 *
 * <p>DELETE is deliberately idempotent and silent: removing a non-contact
 * returns 204 either way — no information about the address book leaks.
 */
@Service
public class ContactService {

    /** Hard cap on GET /contacts rows returned per caller (defense in depth). */
    static final int LIST_LIMIT = 500;

    private static final String LOOKUP_NOT_FOUND = "No user registered with that phone number";

    private static final Logger log = LoggerFactory.getLogger(ContactService.class);

    private final ContactRepository contactRepository;
    private final UserRepository userRepository;
    private final PhoneService phoneService;

    public ContactService(ContactRepository contactRepository,
                          UserRepository userRepository,
                          PhoneService phoneService) {
        this.contactRepository = contactRepository;
        this.userRepository = userRepository;
        this.phoneService = phoneService;
    }

    @Transactional
    public ContactResponse addByPhone(UUID ownerId, String rawPhone) {
        final String phone = normalizePhone(rawPhone);
        User target = userRepository.findByPhoneAndDeletedAtIsNull(phone)
                .orElseThrow(() -> lookupNotFound());
        return persistNewContact(ownerId, target);
    }

    @Transactional
    public ContactResponse addByEmail(UUID ownerId, String rawEmail) {
        final String email = EmailOtpService.normalizeEmail(rawEmail);
        User target = userRepository.findActiveByVerifiedEmail(email)
                .orElseThrow(() -> lookupNotFound());
        return persistNewContact(ownerId, target);
    }

    @Transactional(readOnly = true)
    public List<ContactResponse> list(UUID ownerId) {
        return contactRepository.findAllByOwnerId(ownerId, PageRequest.of(0, LIST_LIMIT));
    }

    /**
     * Sets or clears the alias. {@code null} clears; a present-but-blank value
     * is rejected (same convention as PATCH /users/me).
     */
    @Transactional
    public ContactResponse updateAlias(UUID ownerId, UUID contactUserId, String aliasName) {
        String normalized = null;
        if (aliasName != null) {
            normalized = aliasName.trim();
            if (normalized.isEmpty()) {
                throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "aliasName must not be blank");
            }
        }
        Contact contact = contactRepository.findById(new ContactId(ownerId, contactUserId))
                .orElseThrow(() -> ApiException.of(ApiErrorCode.USER_NOT_FOUND, "Contact not found"));
        contact.changeAlias(normalized);
        contactRepository.save(contact);
        User target = contact.getContactUser();
        return new ContactResponse(
                target.getId(), target.getPhone(), target.getDisplayName(),
                contact.getAliasName(), target.getAvatarUrl(), contact.getCreatedAt());
    }

    /** Idempotent by contract: deleting a non-contact is still 204. */
    @Transactional
    public void delete(UUID ownerId, UUID contactUserId) {
        contactRepository.findById(new ContactId(ownerId, contactUserId))
                .ifPresent(contactRepository::delete);
    }

    private ContactResponse persistNewContact(UUID ownerId, User target) {
        if (target.getId().equals(ownerId)) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "Cannot add yourself as a contact");
        }

        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.USER_NOT_FOUND, "User not found"));
        Contact contact = Contact.add(owner, target);
        try {
            contact = contactRepository.saveAndFlush(contact);
        } catch (DataIntegrityViolationException e) {
            throw ApiException.of(ApiErrorCode.ALREADY_CONTACT, "User is already in contacts");
        }
        log.info("Contact added owner={} contact={}", ownerId, target.getId());
        return new ContactResponse(
                target.getId(), target.getPhone(), target.getDisplayName(),
                contact.getAliasName(), target.getAvatarUrl(), contact.getCreatedAt());
    }

    private static ApiException lookupNotFound() {
        return ApiException.of(ApiErrorCode.USER_NOT_FOUND, LOOKUP_NOT_FOUND);
    }

    private String normalizePhone(String rawPhone) {
        try {
            return phoneService.toE164(rawPhone);
        } catch (IllegalArgumentException e) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "Phone number is not valid");
        }
    }
}
