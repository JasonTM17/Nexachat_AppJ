package com.nexachat.user;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.user.dto.UserResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
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

    public UserService(UserRepository userRepository, PhoneService phoneService) {
        this.userRepository = userRepository;
        this.phoneService = phoneService;
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
        User user = getById(userId);
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
     * GET /users/search: exactly one of {@code phone} or {@code q} must be
     * supplied (both or neither → 400 VALIDATION_FAILED).
     *
     * <ul>
     *   <li>{@code phone}: normalized to E.164 then exact match — returns a
     *       list of 0..1 users; an unknown number is an EMPTY list, not a 404
     *       (search semantics), while an unparseable number is a 400.</li>
     *   <li>{@code q}: case-insensitive displayName prefix, capped at
     *       {@value #SEARCH_LIMIT} rows, caller excluded. A blank {@code q}
     *       counts as "not supplied".</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public List<UserResponse> search(UUID callerId, String phone, String q) {
        boolean hasPhone = phone != null && !phone.isBlank();
        boolean hasQ = q != null && !q.isBlank();
        if (hasPhone == hasQ) {
            throw ApiException.of(
                    ApiErrorCode.VALIDATION_FAILED, "Provide exactly one of 'phone' or 'q'");
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

        String escaped = escapeLike(q.trim());
        return userRepository
                .searchByDisplayNamePrefix(escaped, callerId, PageRequest.of(0, SEARCH_LIMIT))
                .stream()
                .map(UserResponse::from)
                .toList();
    }

    /** Escape LIKE metacharacters so user input stays a literal prefix. */
    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
