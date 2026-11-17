package com.nexachat.user;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.contact.ContactRepository;
import com.nexachat.recovery.EmailOtpService;
import com.nexachat.user.dto.UserResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Profile read/update for the authenticated user (/users/me scope) plus the
 * rate-limited user-discovery search (/users/search).
 */
@Service
public class UserService {

    /** Hard cap on prefix-search results (plan §10.2 / FR-B). */
    static final int SEARCH_LIMIT = 20;

    private final UserRepository userRepository;
    private final PhoneService phoneService;
    private final ContactRepository contactRepository;
    private final UserBlockRepository blockRepository;
    private final java.util.Set<String> operatorPhones;
    private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository,
                       PhoneService phoneService,
                       ContactRepository contactRepository,
                       UserBlockRepository blockRepository,
                       com.nexachat.config.SecurityProperties securityProperties,
                       org.springframework.security.crypto.password.PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.phoneService = phoneService;
        this.contactRepository = contactRepository;
        this.blockRepository = blockRepository;
        this.passwordEncoder = passwordEncoder;
        this.operatorPhones = securityProperties.adminPhones().stream()
                .map(configured -> {
                    try {
                        return phoneService.toE164(configured);
                    } catch (IllegalArgumentException e) {
                        throw new IllegalStateException(
                                "nexachat.security.admin-phones must contain valid E.164 numbers", e);
                    }
                })
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    @Transactional(readOnly = true)
    public User getById(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.USER_NOT_FOUND, "User not found"));
        if (user.getDeletedAt() != null) {
            throw ApiException.of(ApiErrorCode.USER_NOT_FOUND, "User not found");
        }
        return user;
    }

    /**
     * Adds or replaces the account's optional phone number. The number is not
     * verified by SMS: it only lets people find the account by phone. Replacing
     * a phone that is already linked needs the current password, like the other
     * changes to how a person signs in.
     */
    @Transactional
    public User linkPhone(UUID userId, String rawPhone, String currentPassword) {
        String phone;
        try {
            phone = phoneService.toE164(rawPhone);
        } catch (IllegalArgumentException e) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "Phone number is not valid");
        }
        User user = ActiveUserSupport.requireActiveForUpdate(userRepository, userId);
        if (phone.equals(user.getPhone())) {
            return user;
        }
        requirePasswordToReplacePhone(user, currentPassword);
        // A linked number is not verified, so a number that grants operator access must never be claimable.
        if (operatorPhones.contains(phone)) {
            throw ApiException.of(ApiErrorCode.PHONE_EXISTS, "Phone number is already registered");
        }
        if (userRepository.existsByPhoneAndDeletedAtIsNull(phone)) {
            throw ApiException.of(ApiErrorCode.PHONE_EXISTS, "Phone number is already registered");
        }
        user.changePhone(phone);
        try {
            userRepository.saveAndFlush(user);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw ApiException.of(ApiErrorCode.PHONE_EXISTS, "Phone number is already registered");
        }
        return user;
    }

    private void requirePasswordToReplacePhone(User user, String currentPassword) {
        String hash = user.getPasswordHash();
        if (user.getPhone() == null || hash == null || hash.isBlank()) {
            return;
        }
        if (currentPassword == null || !passwordEncoder.matches(currentPassword, hash)) {
            throw ApiException.of(ApiErrorCode.WRONG_PASSWORD, "Current password is incorrect");
        }
    }

    /** Removes the phone number; a verified email must remain as the way to sign in. */
    @Transactional
    public User removePhone(UUID userId) {
        User user = ActiveUserSupport.requireActiveForUpdate(userRepository, userId);
        if (user.getPhone() == null) {
            return user;
        }
        if (!user.hasVerifiedEmail()) {
            throw ApiException.of(ApiErrorCode.LAST_SIGN_IN_METHOD,
                    "The last sign-in method cannot be removed");
        }
        user.removePhone();
        userRepository.save(user);
        return user;
    }

    /**
     * PATCH /users/me semantics: {@code null} displayName = no change;
     * a provided value is trimmed and must be non-blank (and ≤60 via DTO validation).
     */
    @Transactional
    public User updateDisplayName(UUID userId, String displayName) {
        User user = ActiveUserSupport.requireActiveForUpdate(userRepository, userId);
        if (displayName != null) {
            String trimmed = displayName.trim();
            if (trimmed.isEmpty()) {
                throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "displayName must not be blank");
            }
            user.changeDisplayName(trimmed);
            userRepository.save(user);
        }
        return user;
    }

    /**
     * GET /users/search: exactly one of {@code phone}, {@code email}, or {@code q}
     * must be supplied (any other combination → 400 VALIDATION_FAILED).
     *
     * <ul>
     *   <li>{@code phone}: normalized to E.164 then exact match — returns a
     *       list of 0..1 users; an unknown number is an EMPTY list, not a 404
     *       (search semantics), while an unparseable number is a 400.</li>
     *   <li>{@code email}: normalized like recovery email, exact match on verified
     *       active users — 0..1 rows, caller excluded; unknown, unverified, or
     *       deleted addresses yield an empty list.</li>
     *   <li>{@code q}: case-insensitive displayName prefix, capped at
     *       {@value #SEARCH_LIMIT} rows, caller excluded. A blank {@code q}
     *       counts as "not supplied".</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public List<UserResponse> search(UUID callerId, String phone, String email, String q) {
        boolean hasPhone = phone != null && !phone.isBlank();
        boolean hasEmail = email != null && !email.isBlank();
        boolean hasQ = q != null && !q.isBlank();
        int supplied = (hasPhone ? 1 : 0) + (hasEmail ? 1 : 0) + (hasQ ? 1 : 0);
        if (supplied != 1) {
            throw ApiException.of(
                    ApiErrorCode.VALIDATION_FAILED,
                    "Provide exactly one of 'phone', 'email', or 'q'");
        }

        if (hasPhone) {
            final String e164;
            try {
                e164 = phoneService.toE164(phone);
            } catch (IllegalArgumentException e) {
                throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "Phone number is not valid");
            }
            return userRepository.findByPhoneAndDeletedAtIsNull(e164)
                    .map(user -> List.of(scrubAvatar(UserResponse.from(user), callerId, user.getId())))
                    .orElseGet(List::of);
        }

        if (hasEmail) {
            String normalized = EmailOtpService.normalizeEmail(email);
            return userRepository.findActiveByVerifiedEmail(normalized)
                    .filter(user -> !user.getId().equals(callerId))
                    .map(user -> List.of(scrubAvatar(UserResponse.from(user), callerId, user.getId())))
                    .orElseGet(List::of);
        }

        String escaped = escapeLike(q.trim());
        List<User> matches = userRepository.searchByDisplayNamePrefix(
                escaped, callerId, PageRequest.of(0, SEARCH_LIMIT));
        if (matches.isEmpty()) {
            return List.of();
        }
        List<UUID> matchIds = matches.stream().map(User::getId).toList();
        Set<UUID> disclosePhoneFor = new HashSet<>(
                contactRepository.findContactUserIdsAmong(callerId, matchIds));
        Set<UUID> hiddenAvatars = blockRepository == null ? Set.of() : new HashSet<>(
                blockRepository.findRelatedIdsEitherDirection(callerId, matchIds));
        return matches.stream()
                .map(user -> {
                    UserResponse response = UserResponse.forSearchResult(
                            user, disclosePhoneFor.contains(user.getId()));
                    return hiddenAvatars.contains(user.getId())
                            ? withoutAvatar(response)
                            : response;
                })
                .toList();
    }

    /** V38: a block edge hides the avatar in both directions; everything else stays visible. */
    private UserResponse scrubAvatar(UserResponse response, UUID viewerId, UUID targetId) {
        if (blockRepository != null
                && blockRepository.existsBetweenEitherDirection(viewerId, targetId)) {
            return withoutAvatar(response);
        }
        return response;
    }

    private static UserResponse withoutAvatar(UserResponse response) {
        return new UserResponse(response.id(), response.phone(), response.displayName(),
                null, response.deleted(), response.passwordSet());
    }

    /** Escape LIKE metacharacters so user input stays a literal prefix. */
    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
