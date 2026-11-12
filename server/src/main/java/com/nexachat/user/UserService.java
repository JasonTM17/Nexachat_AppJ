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

    public UserService(UserRepository userRepository,
                       PhoneService phoneService,
                       ContactRepository contactRepository) {
        this.userRepository = userRepository;
        this.phoneService = phoneService;
        this.contactRepository = contactRepository;
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
                    .map(user -> List.of(UserResponse.from(user)))
                    .orElseGet(List::of);
        }

        if (hasEmail) {
            String normalized = EmailOtpService.normalizeEmail(email);
            return userRepository.findActiveByVerifiedEmail(normalized)
                    .filter(user -> !user.getId().equals(callerId))
                    .map(user -> List.of(UserResponse.from(user)))
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
        return matches.stream()
                .map(user -> UserResponse.forSearchResult(user, disclosePhoneFor.contains(user.getId())))
                .toList();
    }

    /** Escape LIKE metacharacters so user input stays a literal prefix. */
    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
