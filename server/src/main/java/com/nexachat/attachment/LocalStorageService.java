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
import java.nio.file.NoSuchFileException;
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
    static final long MAX_FILE_SIZE_BYTES = StorageService.MAX_FILE_SIZE_BYTES;

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
            Map.entry("text/csv", ".csv"),
            Map.entry("text/markdown", ".md"),
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
        if (sniffed == null) {
            // Text formats carry no magic bytes — verify the content instead:
            // the declared type must be textual AND the payload must decode as
            // strict UTF-8 (a renamed binary fails the decode and still 415s).
            sniffed = sniffTextContent(contentTypeDeclared, bytes);
        }
        if (sniffed == null || !MagicBytes.ALLOWED_MIMES.contains(sniffed)) {
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
    public byte[] read(String storageKey) {
        Path target = resolveExactPath(storageKey);
        try {
            return Files.readAllBytes(target);
        } catch (NoSuchFileException ex) {
            throw ApiException.of(ApiErrorCode.FILE_NOT_FOUND);
        } catch (IOException ex) {
            throw ApiException.of(ApiErrorCode.INTERNAL_ERROR, "Failed to read stored file");
        }
    }

    @Override
    public StreamHandle open(String storageKey) {
        Path target = resolveExactPath(storageKey);
        final long size;
        try {
            size = Files.size(target);
        } catch (NoSuchFileException ex) {
            throw ApiException.of(ApiErrorCode.FILE_NOT_FOUND);
        } catch (IOException ex) {
            throw ApiException.of(ApiErrorCode.INTERNAL_ERROR, "Failed to read stored file");
        }
        try {
            return new StreamHandle(Files.newInputStream(target), size);
        } catch (NoSuchFileException ex) {
            throw ApiException.of(ApiErrorCode.FILE_NOT_FOUND);
        } catch (IOException ex) {
            throw ApiException.of(ApiErrorCode.INTERNAL_ERROR, "Failed to read stored file");
        }
    }

    @Override
    public boolean exists(String storageKey) {
        Path target = resolveDeletionPath(storageKey);
        return target != null && Files.isRegularFile(target, java.nio.file.LinkOption.NOFOLLOW_LINKS);
    }

    @Override
    public boolean deleteIfExists(String storageKey) {
        Path target = resolveDeletionPath(storageKey);
        if (target == null) {
            return false;
        }
        try {
            return Files.deleteIfExists(target);
        } catch (NoSuchFileException ex) {
            return false;
        } catch (IOException ex) {
            throw ApiException.of(ApiErrorCode.INTERNAL_ERROR, "Failed to delete stored file");
        }
    }

    /**
     * Resolves a deletion target without following a path outside the storage
     * root. The nearest existing ancestor is canonicalized even when the file
     * is already absent, so a symlinked directory cannot bypass the guard.
     */
    private Path resolveDeletionPath(String storageKey) {
        if (storageKey == null || storageKey.isBlank()
                || storageKey.contains("..") || storageKey.contains("\\")) {
            throw ApiException.of(ApiErrorCode.FILE_NOT_FOUND);
        }
        Path target = root.resolve(storageKey).normalize();
        if (!target.startsWith(root) || target.equals(root)) {
            throw ApiException.of(ApiErrorCode.FILE_NOT_FOUND);
        }
        if (!Files.exists(root)) {
            return null;
        }
        try {
            Path realRoot = root.toRealPath();
            Path existing = target;
            while (existing != null
                    && !Files.exists(existing, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                existing = existing.getParent();
            }
            if (existing == null || !existing.toRealPath().startsWith(realRoot)) {
                throw ApiException.of(ApiErrorCode.FILE_NOT_FOUND);
            }
            if (Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isRegularFile(target)) {
                    throw ApiException.of(ApiErrorCode.FILE_NOT_FOUND);
                }
                Path realTarget = target.toRealPath();
                if (!realTarget.startsWith(realRoot)) {
                    throw ApiException.of(ApiErrorCode.FILE_NOT_FOUND);
                }
                String actualKey = realRoot.relativize(realTarget).toString().replace('\\', '/');
                if (!actualKey.equals(storageKey)) {
                    throw ApiException.of(ApiErrorCode.FILE_NOT_FOUND);
                }
            }
            return target;
        } catch (NoSuchFileException ex) {
            return null;
        } catch (IOException ex) {
            throw ApiException.of(ApiErrorCode.INTERNAL_ERROR, "Failed to resolve stored file");
        }
    }

    private Path resolveExactPath(String storageKey) {
        if (storageKey == null || storageKey.isBlank()
                || storageKey.contains("..") || storageKey.contains("\\")) {
            throw ApiException.of(ApiErrorCode.FILE_NOT_FOUND);
        }
        Path target = root.resolve(storageKey).normalize();
        if (!target.startsWith(root) || !Files.isRegularFile(target)) {
            throw ApiException.of(ApiErrorCode.FILE_NOT_FOUND);
        }
        try {
            Path realRoot = root.toRealPath();
            Path realTarget = target.toRealPath();
            if (!realTarget.startsWith(realRoot)) {
                throw ApiException.of(ApiErrorCode.FILE_NOT_FOUND);
            }
            String actualKey = realRoot.relativize(realTarget).toString().replace('\\', '/');
            if (!actualKey.equals(storageKey)) {
                throw ApiException.of(ApiErrorCode.FILE_NOT_FOUND);
            }
            return target;
        } catch (NoSuchFileException ex) {
            throw ApiException.of(ApiErrorCode.FILE_NOT_FOUND);
        } catch (IOException ex) {
            throw ApiException.of(ApiErrorCode.INTERNAL_ERROR, "Failed to read stored file");
        }
    }

    @Override
    public String resolveUrl(String storageKey) {
        return "/api/v1/files/" + storageKey;
    }

    /**
     * Declared-mime acceptance for un-sniffable text types. The bytes must
     * decode under a strict UTF-8 decoder; anything binary (e.g. an .exe
     * renamed .txt) throws {@link java.nio.charset.CharacterCodingException}
     * and is rejected. Returns the declared mime or {@code null}.
     */
    private static String sniffTextContent(String contentTypeDeclared, byte[] bytes) {
        if (contentTypeDeclared == null
                || !TEXT_FALLBACK_MIMES.contains(contentTypeDeclared)) {
            return null;
        }
        try {
            java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes));
            return contentTypeDeclared;
        } catch (java.nio.charset.CharacterCodingException ex) {
            return null;
        }
    }

    private static final java.util.Set<String> TEXT_FALLBACK_MIMES =
            java.util.Set.of("text/plain", "text/markdown", "text/csv");

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
