package com.nexachat.attachment;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
@ConditionalOnProperty(prefix = "nexachat.storage", name = "provider", havingValue = "local", matchIfMissing = true)
public class LocalStorageService implements StorageService {

    /** Hard ceiling for a single upload; also enforced by the multipart config. */
    static final long MAX_FILE_SIZE_BYTES = StorageService.MAX_FILE_SIZE_BYTES;

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
        String sniffed = StorageContentPolicy.sniffMime(contentTypeDeclared, bytes);
        if (sniffed == null) {
            throw ApiException.of(ApiErrorCode.MIME_NOT_ALLOWED);
        }
        String safeDirectory = StorageContentPolicy.safeSubdir(subdir);
        String storageKey = safeDirectory + "/" + UUID.randomUUID()
                + StorageContentPolicy.extensionFor(sniffed);
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

    @Override
    public List<StoredFileInfo> list(String subdir) {
        String folder = StorageContentPolicy.safeSubdir(subdir);
        Path directory = root.resolve(folder).normalize();
        if (!directory.startsWith(root) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        List<StoredFileInfo> files = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                try {
                    Instant lastModified = Files.getLastModifiedTime(entry, LinkOption.NOFOLLOW_LINKS)
                            .toInstant();
                    files.add(new StoredFileInfo(
                            folder + "/" + entry.getFileName(), lastModified));
                } catch (NoSuchFileException vanished) {
                    // A concurrent delete won the race; the next walk will not see it either.
                }
            }
        } catch (NoSuchFileException vanished) {
            return List.of();
        } catch (IOException ex) {
            throw ApiException.of(ApiErrorCode.INTERNAL_ERROR, "Failed to list stored files");
        }
        files.sort(Comparator.comparing(StoredFileInfo::lastModified)
                .thenComparing(StoredFileInfo::storageKey));
        return List.copyOf(files);
    }

    /**
     * Resolves a deletion target without following a path outside the storage
     * root. The nearest existing ancestor is canonicalized even when the file
     * is already absent, so a symlinked directory cannot bypass the guard.
     */
    private Path resolveDeletionPath(String storageKey) {
        if (!StorageContentPolicy.isSafeStorageKey(storageKey)) {
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
        if (!StorageContentPolicy.isSafeStorageKey(storageKey)) {
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
}
