package com.nexachat.attachment;

import com.nexachat.common.MagicBytes;
import com.nexachat.common.MultipartRequests;
import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * Self-service avatar upload: POST /api/v1/users/me/avatar.
 *
 * <p>The caller is the authenticated {@link JwtPrincipal}; only image content
 * passes the sniff check. The stored file is served from the public files
 * endpoint and its URL is persisted on the user row.</p>
 *
 * <p>Like every upload path, the whole-file buffer is admitted by
 * {@link UploadAdmission}; a saturated gate answers {@code 429 RATE_LIMITED}
 * with a {@code Retry-After} header.</p>
 */
@RestController
@RequestMapping("/api/v1/users")
public class AvatarController {

    private final StorageService storageService;
    private final UserRepository userRepository;
    private final AvatarReferenceCoordinator referenceCoordinator;
    private final UploadAdmission uploadAdmission;

    public AvatarController(
            StorageService storageService,
            UserRepository userRepository,
            AvatarReferenceCoordinator referenceCoordinator,
            UploadAdmission uploadAdmission) {
        this.storageService = storageService;
        this.userRepository = userRepository;
        this.referenceCoordinator = referenceCoordinator;
        this.uploadAdmission = uploadAdmission;
    }

    @PostMapping("/me/avatar")
    @Transactional
    public AvatarResponse uploadAvatar(
            @AuthenticationPrincipal JwtPrincipal principal,
            @RequestParam("file") List<MultipartFile> files) throws IOException {
        if (principal == null) {
            throw ApiException.of(ApiErrorCode.UNAUTHORIZED, "Authentication required");
        }
        MultipartFile file = MultipartRequests.requireSingleFile(files);
        if (file.isEmpty()) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "Avatar file is required");
        }
        if (file.getSize() > StorageService.MAX_FILE_SIZE_BYTES) {
            throw ApiException.of(ApiErrorCode.FILE_TOO_LARGE);
        }
        // The whole-file copy and the store that consumes it share one
        // admission slot so a burst of avatars cannot exhaust the heap.
        try (UploadAdmission.Permit permit = uploadAdmission.acquire()) {
            byte[] bytes = file.getBytes();
            String sniffed = MagicBytes.sniff(bytes);
            if (sniffed == null || !sniffed.startsWith("image/")) {
                throw ApiException.of(ApiErrorCode.MIME_NOT_ALLOWED);
            }
            // The avatar coordination advisory is the outermost lock: a group
            // rename/avatar change takes it before its user and conversation
            // rows, so the upload must not hold the user row while it waits for
            // the same advisory (user -> advisory would invert that order).
            referenceCoordinator.lockForTransaction();
            User user = userRepository.findByIdForUpdate(principal.userId())
                    .orElseThrow(() -> ApiException.of(ApiErrorCode.USER_NOT_FOUND, "User not found"));
            if (user.getDeletedAt() != null) {
                throw ApiException.of(ApiErrorCode.USER_NOT_FOUND, "User not found");
            }
            String oldAvatarKey = ownedAvatarStorageKey(user.getAvatarUrl());
            StorageService.StoredFile stored = storageService.store(
                    "avatars", file.getOriginalFilename(), file.getContentType(), bytes);
            StorageTransactionCleanup.deleteOnRollback(storageService, stored.storageKey());
            user.changeAvatarUrl(storageService.resolveUrl(stored.storageKey()));
            userRepository.save(user);
            if (!stored.storageKey().equals(oldAvatarKey)) {
                referenceCoordinator.deleteIfUnreferencedAfterCommit(oldAvatarKey);
            }
            return new AvatarResponse(user.getAvatarUrl());
        }
    }

    private static String ownedAvatarStorageKey(String avatarUrl) {
        if (avatarUrl == null || !avatarUrl.startsWith(AvatarUrlPolicy.FILES_PATH)) {
            return null;
        }
        String storageKey = avatarUrl.substring(AvatarUrlPolicy.FILES_PATH.length());
        return AvatarUrlPolicy.AVATAR_STORAGE_KEY.matcher(storageKey).matches() ? storageKey : null;
    }

    /**
     * Response body: the public URL of the stored avatar.
     *
     * @param avatarUrl URL path served by {@link FileController}
     */
    public record AvatarResponse(String avatarUrl) {
    }
}
