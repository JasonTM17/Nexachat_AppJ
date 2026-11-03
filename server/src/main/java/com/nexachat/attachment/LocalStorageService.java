package com.nexachat.attachment;

import com.nexachat.common.MagicBytes;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/**
 * Filesystem-backed {@link StorageService}: writes below
 * {@code ${nexachat.storage.root}} (default {@code ./data/uploads}).
 *
 * <p>Security properties:</p>
 * <ul>
 *   <li>MIME is sniffed from the bytes via {@link MagicBytes}; the client
 *       filename and declared Content-Type are never trusted.</li>
 *   <li>The extension is derived from the sniffed MIME only.</li>
 *   <li>The subdirectory must be one safe segment, so a generated key can
 *       never escape the root.</li>
 *   <li>Files larger than {@value #MAX_FILE_SIZE_BYTES} bytes are rejected
 *       before anything is written.</li>
 * </ul>
 */
@Service
@Primary
public class LocalStorageService implements StorageService {

    /** Hard ceiling for a single upload; also enforced by the multipart config. */
    static final long MAX_FILE_SIZE_BYTES = 25L * 1024 * 1024;

    private static final Map<String, String> MIME_TO_EXTENSION = Map.ofEntries(
            Map.entry("image/jpeg", ".jpg"),
            Map.entry("image/png", ".png"),
            Map.entry("image/webp", ".webp"),
            Map.entry("image/gif", ".gif"),
            Map.entry("video/mp4", ".mp4"),
            Map.entry("audio/mpeg", ".mp3"),
            Map.entry("audio/mp4", ".m4a"),
            Map.entry("audio/ogg", ".ogg"),
            Map.entry("application/pdf", ".pdf"),
            Map.entry("text/plain", ".txt"),
            Map.entry("application/zip", ".zip"),
            Map.entry("application/msword", ".doc"),
            Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.document", ".docx"),
            Map.entry("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ".xlsx"),
            Map.entry("application/vnd.openxmlformats-officedocument.presentationml.presentation", ".pptx")
    );

    private final Path root;

    public LocalStorageService(
            @Value("${nexachat.storage.root:./data/uploads}") String rootDir) {
        this.root = Path.of(rootDir).toAbsolutePath().normalize();
    }

    @PostConstruct
    void init() throws IOException {
        Files.createDirectories(root);
    }

    @Override
    public StoredFile store(String subdir, String originalFilename, String contentTypeDeclared, byte[] bytes) {
        if (bytes == null) {
            throw ApiException.of(ApiErrorCode.MIME_NOT_ALLOWED);
        }
        if (bytes.length > MAX_FILE_SIZE_BYTES) {
            throw ApiException.of(ApiErrorCode.FILE_TOO_LARGE);
        }
        String sniffed = MagicBytes.sniff(bytes);
        if (!MagicBytes.isAllowed(bytes)) {
            throw ApiException.of(ApiErrorCode.MIME_NOT_ALLOWED);
        }
        String storageKey = safeSubdir(subdir) + "/" + UUID.randomUUID() + extensionFor(sniffed);
        Path target = root.resolve(storageKey).normalize();
        if (!target.startsWith(root)) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "Invalid storage path");
        }
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
        } catch (IOException ex) {
            throw ApiException.of(ApiErrorCode.INTERNAL_ERROR, "Failed to store file");
        }
        return new StoredFile(storageKey, bytes.length, sniffed);
    }

    @Override
    public String resolveUrl(String storageKey) {
        return "/api/v1/files/" + storageKey;
    }

    private static String safeSubdir(String subdir) {
        if (subdir == null || subdir.isBlank()
                || subdir.contains("..")
                || subdir.contains("/")
                || subdir.contains("\\")
                || !subdir.matches("[A-Za-z0-9._-]+")) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "Invalid storage subdirectory");
        }
        return subdir;
    }

    private static String extensionFor(String mimeType) {
        return MIME_TO_EXTENSION.getOrDefault(mimeType, ".bin");
    }
}
