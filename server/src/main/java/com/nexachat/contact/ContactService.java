package com.nexachat.contact;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.contact.dto.ContactResponse;
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
 * {@code 0987…} and {@code +84987…} resolve to the same user. The
 * {@code (owner, contact)} PK is the final arbiter of duplicates (check-then-
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
    public ContactResponse add(UUID ownerId, String rawPhone) {
        final String phone = normalize(rawPhone);
        User target = userRepository.findByPhoneAndDeletedAtIsNull(phone)
                .orElseThrow(() -> ApiException.of(
                        ApiErrorCode.USER_NOT_FOUND, "No user registered with that phone number"));

        if (target.getId().equals(ownerId)) {
            // Service-level guard ahead of the DB CHECK so the contract is 400, not 500.
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "Cannot add yourself as a contact");
        }

        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.USER_NOT_FOUND, "User not found"));
        Contact contact = Contact.add(owner, target);
        try {
            // saveAndFlush so the PK violation surfaces HERE — a deferred commit-
            // time flush would escape the catch (same race guard as register).
            contact = contactRepository.saveAndFlush(contact);
        } catch (DataIntegrityViolationException e) {
            throw ApiException.of(ApiErrorCode.ALREADY_CONTACT, "User is already in contacts");
        }
        log.info("Contact added owner={} contact={}", ownerId, target.getId());
        return new ContactResponse(
                target.getId(), target.getPhone(), target.getDisplayName(),
                contact.getAliasName(), target.getAvatarUrl(), contact.getCreatedAt());
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

    private String normalize(String rawPhone) {
        try {
            return phoneService.toE164(rawPhone);
        } catch (IllegalArgumentException e) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "Phone number is not valid");
        }
    }
}
