package com.nexachat.attachment;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;

/**
 * Stores uploaded bytes under a server-generated key and resolves an API URL
 * path for them.
 *
 * <p>Implementations must derive the storage key from server-side sniffed
 * content, never from the client-supplied filename, so the URL space stays
 * predictable and traversal-safe.</p>
 */
public interface StorageService {

    /** Maximum allowed upload size, enforced before upload bytes are buffered by service code. */
    long MAX_FILE_SIZE_BYTES = 25L * 1024 * 1024;

    /**
     * Persists {@code bytes} under {@code subdir}.
     *
     * @param subdir              logical folder, e.g. {@code avatars}
     * @param originalFilename    client filename; informational only, never trusted
     * @param contentTypeDeclared client-declared MIME type; informational only, never trusted
     * @param bytes               raw file content
     * @return descriptor of the stored file
     * @throws com.nexachat.common.error.ApiException {@code MIME_NOT_ALLOWED} or
     *                                                {@code FILE_TOO_LARGE}
     */
    StoredFile store(String subdir, String originalFilename, String contentTypeDeclared, byte[] bytes);

    /**
     * Reads back the bytes stored under {@code storageKey}.
     *
     * <p>Implementations must apply the same traversal guard as {@link #store}:
     * the key is validated before any read.</p>
     *
     * @param storageKey key returned by {@link #store}
     * @return raw file content
     * @throws com.nexachat.common.error.ApiException {@code FILE_NOT_FOUND}
     *                                                when the key resolves to
     *                                                no stored file
     */
    byte[] read(String storageKey);

    /**
     * Opens a stream over the stored file for range-aware serving. The caller
     * owns the handle and must close it; closing the stream releases the
     * underlying resource.
     *
     * <p>Implementations must apply the same traversal guard as {@link #read}.
     *
     * @param storageKey key returned by {@link #store}
     * @return the open stream plus its exact byte count
     * @throws com.nexachat.common.error.ApiException {@code FILE_NOT_FOUND}
     *                                                when the key resolves to
     *                                                no stored file
     */
    StreamHandle open(String storageKey);

    /**
     * Reads at most {@code maxBytes} leading bytes of the stored object and
     * reports its exact total size.
     *
     * <p>Remote backends must translate this into one bounded range request
     * instead of a full-object read, so a large file is never traversed to
     * serve a short response or a {@code 416}. The returned array is closed
     * by the implementation before it returns.</p>
     *
     * <p>Implementations must apply the same traversal guard as {@link #read}
     * and accept a zero-byte object (empty prefix, size zero).</p>
     *
     * @param storageKey key returned by {@link #store}
     * @param maxBytes   maximum number of leading bytes to return; must be positive
     * @return the prefix bytes actually available (never more than {@code maxBytes})
     *         and the exact object size
     * @throws com.nexachat.common.error.ApiException {@code FILE_NOT_FOUND}
     *                                                when the key resolves to
     *                                                no stored file
     */
    PrefixRead readPrefix(String storageKey, int maxBytes);

    /**
     * Reads at most {@code maxBytes} trailing bytes of a stored object whose
     * exact size the caller already observed.
     *
     * <p>Remote backends must translate this into one bounded range request
     * positioned near the end of the object; the skipped bytes must not cross
     * the network.</p>
     *
     * <p>Implementations must apply the same traversal guard as {@link #read}
     * and must return an empty array for an empty object.</p>
     *
     * @param storageKey key returned by {@link #store}
     * @param sizeBytes  exact object size previously reported by this service
     * @param maxBytes   maximum number of trailing bytes to return; must be positive
     * @return the trailing bytes actually available (never more than {@code maxBytes})
     * @throws com.nexachat.common.error.ApiException {@code FILE_NOT_FOUND}
     *                                                when the key resolves to
     *                                                no stored file
     */
    byte[] readSuffix(String storageKey, long sizeBytes, int maxBytes);

    /**
     * Opens a stream over exactly {@code length} bytes starting at
     * {@code start}.
     *
     * <p>Remote backends must issue one bounded range request for the slice,
     * so serving the last kilobyte of a large object does not transfer the
     * preceding bytes. The caller owns the handle and must close it; closing
     * the stream releases the underlying resource, including when only part
     * of the slice was consumed.</p>
     *
     * <p>Implementations must apply the same traversal guard as {@link #read}
     * and must reject a slice outside the object with {@code INTERNAL_ERROR}
     * (the caller derives the slice from a reported size, so an out-of-range
     * request means the object changed unexpectedly).</p>
     *
     * @param storageKey key returned by {@link #store}
     * @param start      zero-based offset of the first byte
     * @param length     exact number of bytes to expose; must be positive
     * @return the open stream limited to exactly {@code length} bytes plus the
     *         exact total object size
     * @throws com.nexachat.common.error.ApiException {@code FILE_NOT_FOUND}
     *                                                when the key resolves to
     *                                                no stored file
     */
    StreamHandle openRange(String storageKey, long start, long length);

    /**
     * Checks whether a safe stored object exists in the configured backend.
     *
     * <p>Implementations must apply the same key-safety guards as {@link #read};
     * malformed keys are rejected as missing files.</p>
     *
     * @param storageKey key returned by {@link #store}
     * @return {@code true} only when the key resolves to a stored object
     */
    boolean exists(String storageKey);

    /**
     * Deletes a stored file if it still exists.
     *
     * <p>Implementations must apply the same key-safety guard as reads.
     * The operation must be idempotent so a database retry can finish after a
     * file was removed but its attachment row was not committed.</p>
     *
     * @param storageKey server-generated key returned by {@link #store}
     * @return {@code true} when a file was removed, or {@code false} when it
     *         was already absent
     */
    boolean deleteIfExists(String storageKey);

    /**
     * Lists the stored files directly inside {@code subdir}.
     *
     * <p>Only direct child objects are returned: nested folders and
     * directory-marker objects are skipped. Implementations apply the same
     * {@code subdir} guard as
     * {@link #store}. The result is ordered oldest first (last-modified time,
     * then key) so a bounded batch makes deterministic progress.</p>
     *
     * @param subdir logical folder, e.g. {@code avatars}
     * @return stored files, empty when the folder does not exist
     */
    List<StoredFileInfo> list(String subdir);

    /**
     * An open stored file: the stream positioned at offset zero plus its size.
     *
     * <p>For {@link #openRange} the stream is limited to exactly the requested
     * slice while {@code sizeBytes} remains the total object size, so callers
     * can build a {@code Content-Range} without another lookup.</p>
     */
    record StreamHandle(InputStream content, long sizeBytes) implements AutoCloseable {

        @Override
        public void close() throws IOException {
            content.close();
        }
    }

    /**
     * A bounded leading read: the bytes available at the start of the object
     * and its exact total size.
     *
     * @param bytes     leading bytes, at most the requested maximum
     * @param sizeBytes exact object size in bytes
     */
    record PrefixRead(byte[] bytes, long sizeBytes) {

        public PrefixRead {
            bytes = bytes == null ? new byte[0] : bytes;
        }
    }

    /**
     * Resolves the public URL path for a key returned by {@link #store}.
     *
     * @param storageKey storage key
     * @return URL path that {@code FileController} serves
     */
    String resolveUrl(String storageKey);

    /**
     * Result of a successful store: key, exact byte count, and sniffed MIME type.
     *
     * @param storageKey key relative to the configured storage backend
     * @param sizeBytes  number of bytes written
     * @param mimeType   MIME type sniffed from the content
     */
    record StoredFile(String storageKey, long sizeBytes, String mimeType) {
    }

    /**
     * A stored file observed during an inventory walk.
     *
     * @param storageKey key relative to the configured storage backend
     * @param lastModified last write time; write-once files are never modified
     *                     after {@link #store}, so this is their creation time
     */
    record StoredFileInfo(String storageKey, Instant lastModified) {
    }
}
