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
 */
@RestController
@RequestMapping("/api/v1/users")
public class AvatarController {

    private final StorageService storageService;
    private final UserRepository userRepository;

    public AvatarController(StorageService storageService, UserRepository userRepository) {
        this.storageService = storageService;
        this.userRepository = userRepository;
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
        byte[] bytes = file.getBytes();
        String sniffed = MagicBytes.sniff(bytes);
        if (sniffed == null || !sniffed.startsWith("image/")) {
            throw ApiException.of(ApiErrorCode.MIME_NOT_ALLOWED);
        }
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
            StorageTransactionCleanup.deleteAfterCommit(storageService, oldAvatarKey);
        }
        return new AvatarResponse(user.getAvatarUrl());
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
