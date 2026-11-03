package com.nexachat.attachment;

/**
 * Stores uploaded bytes under a server-generated key and resolves a public
 * URL path for them.
 *
 * <p>Implementations must derive the storage key from server-side sniffed
 * content, never from the client-supplied filename, so the URL space stays
 * predictable and traversal-safe.</p>
 */
public interface StorageService {

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
     * the key is normalized and required to stay inside the storage root before
     * any read.</p>
     *
     * @param storageKey key returned by {@link #store}
     * @return raw file content
     * @throws com.nexachat.common.error.ApiException {@code FILE_NOT_FOUND}
     *                                                when the key resolves to
     *                                                no stored file
     */
    byte[] read(String storageKey);

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
     * @param storageKey key relative to the storage root
     * @param sizeBytes  number of bytes written
     * @param mimeType   MIME type sniffed from the content
     */
    record StoredFile(String storageKey, long sizeBytes, String mimeType) {
    }
}
