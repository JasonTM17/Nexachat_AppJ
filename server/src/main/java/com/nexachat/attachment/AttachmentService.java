package com.nexachat.attachment;

import com.nexachat.common.MagicBytes;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.message.Message;
import com.nexachat.message.MessageAttachment;
import com.nexachat.message.MessageAttachmentRepository;
import com.nexachat.message.dto.AttachmentResponse;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Attachment use cases (phase-09 multimedia, plan §10.2 + UC-09):
 * upload-first, bind-at-send.
 *
 * <p><b>Upload</b> ({@code POST /api/v1/attachments}): stores the bytes via
 * {@link StorageService} — which re-sniffs the real content, enforces the
 * magic-byte whitelist and the 25MB ceiling — then inserts an UNBOUND
 * {@code message_attachments} row owned by the caller
 * ({@code message_id = NULL} per V2). The id is what the client later passes
 * as {@code attachmentIds} on send.
 *
 * <p><b>Download authZ</b> — the fix for the documented FileController gap
 * (plan §13 "Unauthorized file access"): a stored attachment is readable iff
 * the requester is its owner OR is a member of the bound message's
 * conversation; an unbound upload is owner-only. The same rule backs both the
 * id-addressed {@code GET /api/v1/attachments/{id}/content} endpoint and the
 * raw {@code /api/v1/files/**} serving path ({@link #assertStorageKeyReadable}),
 * so neither door bypasses the other.
 */
@Service
public class AttachmentService {

    /**
     * Upload ceiling — mirrors {@code spring.servlet.multipart.max-file-size}
     * (25MB) and {@link LocalStorageService#MAX_FILE_SIZE_BYTES}. Enforced here
     * too so the rejection is a clean 413 even when the multipart layer did
     * not trip first (e.g. tests, alternate storage backends).
     */
    static final long MAX_FILE_SIZE_BYTES = StorageService.MAX_FILE_SIZE_BYTES;

    /** Logical storage subdirectory for message attachments. */
    static final String STORAGE_SUBDIR = "attachments";

    private static final int MAX_ORIGINAL_NAME_LENGTH = 255;

    /** BlurHash placeholders are 6..64 characters from the BlurHash base83 alphabet. */
    static final int BLUR_HASH_MIN_LENGTH = 6;
    static final int BLUR_HASH_MAX_LENGTH = 64;
    private static final Pattern BLUR_HASH_ALPHABET =
            Pattern.compile("^[0-9A-Za-z#$%*+,\\-.:;=?@\\[\\]^_{|}~]+$");

    private final MessageAttachmentRepository attachmentRepository;
    private final ConversationMemberRepository memberRepository;
    private final StorageService storageService;
    private final UserRepository userRepository;

    public AttachmentService(MessageAttachmentRepository attachmentRepository,
                             ConversationMemberRepository memberRepository,
                             StorageService storageService,
                             UserRepository userRepository) {
        this.attachmentRepository = attachmentRepository;
        this.memberRepository = memberRepository;
        this.storageService = storageService;
        this.userRepository = userRepository;
    }

    /**
     * A stored attachment's metadata plus an open stream over its bytes. The
     * caller owns the handle; closing it releases the underlying file.
     */
    public record AttachmentContent(MessageAttachment attachment, StorageService.StreamHandle content)
            implements AutoCloseable {

        @Override
        public void close() throws java.io.IOException {
            content.close();
        }
    }

    /**
     * Stores {@code file} as an unbound attachment owned by {@code ownerId}.
     *
     * <p>The client filename is never trusted: {@code storage_key} is a
     * server-minted {@code <subdir>/<uuid>.<sniffed-ext>} and only a sanitized
     * copy of the name is kept as display metadata. The MIME persisted is the
     * sniffed one, not the client-declared Content-Type.
     *
     * @return the canonical attachment DTO (id + url + metadata) — 201 body
     * @throws ApiException {@code VALIDATION_FAILED} (missing/empty file or bad
     *                      dimensions), {@code FILE_TOO_LARGE} (413),
     *                      {@code MIME_NOT_ALLOWED} (415 — e.g. an .exe
     *                      renamed .png), {@code USER_NOT_FOUND}
     */
    @Transactional
    public AttachmentResponse upload(UUID ownerId, MultipartFile file,
                                     Integer width, Integer height, Integer duration,
                                     String blurHash) {
        if (file == null || file.isEmpty()) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "Attachment file is required");
        }
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw ApiException.of(ApiErrorCode.FILE_TOO_LARGE);
        }
        validateDimension("width", width);
        validateDimension("height", height);
        validateDimension("duration", duration);
        String validatedBlurHash = validateBlurHash(blurHash);

        final byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException ex) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "Could not read uploaded file");
        }
        if (bytes.length > MAX_FILE_SIZE_BYTES) {
            throw ApiException.of(ApiErrorCode.FILE_TOO_LARGE);
        }
        // A blur hash describes an image placeholder; reject it before the bytes
        // are written so a bad claim never leaves a stored file behind.
        if (validatedBlurHash != null) {
            String sniffed = MagicBytes.sniff(bytes);
            if (sniffed == null || !sniffed.startsWith("image/")) {
                throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                        "Only image attachments may carry a blurHash");
            }
        }

        // Serialize with account deletion before writing the external file.
        User owner = userRepository.findByIdForUpdate(ownerId)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.USER_NOT_FOUND, "User not found"));
        if (owner.getDeletedAt() != null) {
            throw ApiException.of(ApiErrorCode.USER_NOT_FOUND, "User not found");
        }

        // store() re-sniffs the bytes and applies the magic-byte whitelist —
        // a disguised executable dies there with MIME_NOT_ALLOWED.
        StorageService.StoredFile stored = storageService.store(
                STORAGE_SUBDIR, file.getOriginalFilename(), file.getContentType(), bytes);
        boolean transactionActive = TransactionSynchronizationManager.isSynchronizationActive();
        StorageTransactionCleanup.deleteOnRollback(storageService, stored.storageKey());
        try {
            MessageAttachment attachment = MessageAttachment.upload(
                    owner, stored.storageKey(), sanitizeOriginalName(file.getOriginalFilename()),
                    stored.mimeType(), stored.sizeBytes(), width, height, duration, validatedBlurHash);
            attachmentRepository.save(attachment);
            return toResponse(attachment);
        } catch (RuntimeException | Error ex) {
            if (!transactionActive) {
                storageService.deleteIfExists(stored.storageKey());
            }
            throw ex;
        }
    }

    /**
     * AuthZ-guarded content load for {@code GET /api/v1/attachments/{id}/content}.
     *
     * @throws ApiException {@code ATTACHMENT_NOT_FOUND} (404 — unknown id),
     *                      {@code NOT_MEMBER} (403 — requester is neither the
     *                      owner nor a member of the bound conversation)
     */
    @Transactional(readOnly = true)
    public AttachmentContent loadContent(UUID attachmentId, UUID requesterId) {
        MessageAttachment attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> ApiException.of(
                        ApiErrorCode.ATTACHMENT_NOT_FOUND, "Attachment not found"));
        assertReadable(attachment, requesterId);
        return new AttachmentContent(attachment, storageService.open(attachment.getStorageKey()));
    }

    /**
     * Applies the attachment authZ rule to the raw {@code /api/v1/files/**}
     * path (FileController): an exact storage key that maps to a
     * {@code message_attachments} row must satisfy the same owner/member rule
     * as {@link #loadContent}. If the exact lookup misses, the storage layer
     * still rejects case-variant paths before opening bytes. Keys with no
     * attachment row — avatars and other profile-public files — pass unchecked:
     * they have no membership concept.
     *
     * @throws ApiException {@code NOT_MEMBER} when the key is an attachment the
     *                      requester may not read
     */
    @Transactional(readOnly = true)
    public void assertStorageKeyReadable(String storageKey, UUID requesterId) {
        attachmentRepository.findByStorageKey(storageKey).ifPresent(attachment -> {
            if (!attachment.getStorageKey().equals(storageKey)) {
                throw ApiException.of(ApiErrorCode.ATTACHMENT_NOT_FOUND, "Attachment not found");
            }
            assertReadable(attachment, requesterId);
        });
    }

    /**
     * The single authZ rule (plan §13): owner always may; otherwise the
     * requester must be a member of the bound message's conversation. Unbound
     * uploads are owner-only.
     */
    private void assertReadable(MessageAttachment attachment, UUID requesterId) {
        Message bound = attachment.getMessage();
        if (bound != null && bound.getDeletedAt() != null) {
            throw ApiException.of(ApiErrorCode.ATTACHMENT_NOT_FOUND, "Attachment not found");
        }
        if (attachment.getOwner().getId().equals(requesterId)) {
            return;
        }
        if (bound != null
                && memberRepository.existsByConversation_IdAndUser_Id(
                        bound.getConversation().getId(), requesterId)) {
            return;
        }
        throw ApiException.of(ApiErrorCode.NOT_MEMBER, "Not allowed to read this attachment");
    }

    private AttachmentResponse toResponse(MessageAttachment attachment) {
        return new AttachmentResponse(
                attachment.getId(),
                storageService.resolveUrl(attachment.getStorageKey()),
                attachment.getOriginalName(),
                attachment.getMimeType(),
                attachment.getSizeBytes(),
                attachment.getWidth(),
                attachment.getHeight(),
                attachment.getDurationMs(),
                attachment.getBlurHash());
    }

    /**
     * Validates an optional client-supplied BlurHash: 6..64 characters from the
     * BlurHash base83 alphabet. A null/blank value is accepted as "absent".
     *
     * @return the validated hash, or {@code null} when absent
     * @throws ApiException {@code VALIDATION_FAILED} (400) on any invalid value
     */
    static String validateBlurHash(String blurHash) {
        if (blurHash == null || blurHash.isBlank()) {
            return null;
        }
        if (blurHash.length() < BLUR_HASH_MIN_LENGTH
                || blurHash.length() > BLUR_HASH_MAX_LENGTH
                || !BLUR_HASH_ALPHABET.matcher(blurHash).matches()) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "blurHash must be 6 to 64 BlurHash base83 characters");
        }
        return blurHash;
    }

    private static void validateDimension(String field, Integer value) {
        if (value != null && value < 0) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, field + " must not be negative");
        }
    }

    /**
     * Turns a client filename into safe display metadata: drops any directory
     * portion (both separators), removes control and quoting characters, caps
     * at the column length, and falls back to {@code "file"} when nothing
     * usable remains. The result is safe to persist and to echo inside a
     * Content-Disposition header.
     */
    static String sanitizeOriginalName(String originalFilename) {
        if (originalFilename == null) {
            return "file";
        }
        String name = originalFilename;
        int lastSlash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (lastSlash >= 0) {
            name = name.substring(lastSlash + 1);
        }
        StringBuilder cleaned = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c >= 0x20 && c != 0x7F && c != '"' && c != ';') {
                cleaned.append(c);
            }
        }
        name = cleaned.toString().trim();
        if (name.isEmpty()) {
            return "file";
        }
        return name.length() > MAX_ORIGINAL_NAME_LENGTH
                ? name.substring(0, MAX_ORIGINAL_NAME_LENGTH)
                : name;
    }
}
