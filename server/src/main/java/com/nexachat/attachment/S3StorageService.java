package com.nexachat.attachment;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Private S3-compatible storage; all reads continue through the API controller. */
@Service
@ConditionalOnProperty(prefix = "nexachat.storage", name = "provider", havingValue = "s3")
public class S3StorageService implements StorageService {

    private final S3Client s3Client;
    private final S3StorageProperties properties;

    public S3StorageService(S3Client s3Client, S3StorageProperties properties) {
        this.s3Client = s3Client;
        this.properties = properties;
    }

    @Override
    public StoredFile store(String subdir, String originalFilename, String contentTypeDeclared, byte[] bytes) {
        if (bytes == null) {
            throw ApiException.of(ApiErrorCode.MIME_NOT_ALLOWED);
        }
        if (bytes.length > MAX_FILE_SIZE_BYTES) {
            throw ApiException.of(ApiErrorCode.FILE_TOO_LARGE);
        }
        String mimeType = StorageContentPolicy.sniffMime(contentTypeDeclared, bytes);
        if (mimeType == null) {
            throw ApiException.of(ApiErrorCode.MIME_NOT_ALLOWED);
        }
        String safeDirectory = StorageContentPolicy.safeSubdir(subdir);
        String key = safeDirectory + "/" + java.util.UUID.randomUUID()
                + StorageContentPolicy.extensionFor(mimeType);
        try {
            s3Client.putObject(PutObjectRequest.builder()
                            .bucket(properties.bucket())
                            .key(key)
                            .contentType(mimeType)
                            .build(),
                    RequestBody.fromBytes(bytes));
        } catch (SdkException failure) {
            throw ApiException.of(ApiErrorCode.INTERNAL_ERROR, "Failed to store file");
        }
        return new StoredFile(key, bytes.length, mimeType);
    }

    @Override
    public byte[] read(String storageKey) {
        try (StreamHandle handle = open(storageKey)) {
            return handle.content().readAllBytes();
        } catch (IOException failure) {
            throw ApiException.of(ApiErrorCode.INTERNAL_ERROR, "Failed to read stored file");
        }
    }

    @Override
    public StreamHandle open(String storageKey) {
        String key = requireSafeKey(storageKey);
        try {
            ResponseInputStream<GetObjectResponse> object = s3Client.getObject(GetObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(key)
                    .build());
            Long contentLength = object.response().contentLength();
            return new StreamHandle(object, contentLength == null ? 0 : contentLength);
        } catch (S3Exception failure) {
            if (isNotFound(failure)) {
                throw ApiException.of(ApiErrorCode.FILE_NOT_FOUND);
            }
            throw ApiException.of(ApiErrorCode.INTERNAL_ERROR, "Failed to read stored file");
        } catch (SdkException failure) {
            throw ApiException.of(ApiErrorCode.INTERNAL_ERROR, "Failed to read stored file");
        }
    }

    @Override
    public PrefixRead readPrefix(String storageKey, int maxBytes) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        String key = requireSafeKey(storageKey);
        try (ResponseInputStream<GetObjectResponse> object =
                     getRange(key, "bytes=0-" + (maxBytes - 1L))) {
            long size = totalSize(key, object.response());
            byte[] prefix = object.readNBytes(maxBytes);
            return new PrefixRead(prefix, size);
        } catch (S3Exception failure) {
            if (failure.statusCode() == 416) {
                // An empty object cannot satisfy byte 0; only size zero is legitimate.
                long size = headSize(key);
                if (size == 0) {
                    return new PrefixRead(new byte[0], 0);
                }
            }
            throw readFailure(failure);
        } catch (SdkException failure) {
            throw ApiException.of(ApiErrorCode.INTERNAL_ERROR, "Failed to read stored file");
        } catch (IOException failure) {
            throw ApiException.of(ApiErrorCode.INTERNAL_ERROR, "Failed to read stored file");
        }
    }

    @Override
    public byte[] readSuffix(String storageKey, long sizeBytes, int maxBytes) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        String key = requireSafeKey(storageKey);
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must not be negative");
        }
        if (sizeBytes == 0) {
            return new byte[0];
        }
        int suffixLength = (int) Math.min(sizeBytes, maxBytes);
        long start = sizeBytes - suffixLength;
        long end = sizeBytes - 1;
        try (ResponseInputStream<GetObjectResponse> object =
                     getRange(key, "bytes=" + start + "-" + end)) {
            long[] contentRange = requireContentRange(object.response(), start, end);
            if (contentRange[2] != sizeBytes) {
                throw ApiException.of(ApiErrorCode.INTERNAL_ERROR, "Stored file changed while serving");
            }
            byte[] suffix = object.readNBytes(suffixLength);
            if (suffix.length != suffixLength) {
                throw ApiException.of(ApiErrorCode.INTERNAL_ERROR, "Failed to read stored file");
            }
            return suffix;
        } catch (S3Exception failure) {
            throw readFailure(failure);
        } catch (SdkException failure) {
            throw ApiException.of(ApiErrorCode.INTERNAL_ERROR, "Failed to read stored file");
        } catch (IOException failure) {
            throw ApiException.of(ApiErrorCode.INTERNAL_ERROR, "Failed to read stored file");
        }
    }

    @Override
    public StreamHandle openRange(String storageKey, long start, long length) {
        if (start < 0 || length <= 0 || start > Long.MAX_VALUE - length) {
            throw new IllegalArgumentException("Invalid byte range");
        }
        String key = requireSafeKey(storageKey);
        long end = start + length - 1;
        ResponseInputStream<GetObjectResponse> object;
        try {
            object = getRange(key, "bytes=" + start + "-" + end);
        } catch (S3Exception failure) {
            throw readFailure(failure);
        } catch (SdkException failure) {
            throw ApiException.of(ApiErrorCode.INTERNAL_ERROR, "Failed to read stored file");
        }
        try {
            long total = totalSize(key, object.response());
            if (total < start + length) {
                throw ApiException.of(ApiErrorCode.INTERNAL_ERROR, "Stored file changed while serving");
            }
            long[] contentRange = parseContentRange(object.response().contentRange());
            if (contentRange != null) {
                if (contentRange[0] != start || contentRange[1] != end) {
                    throw ApiException.of(ApiErrorCode.INTERNAL_ERROR, "Stored file changed while serving");
                }
            } else if (start > 0) {
                // Without a Content-Range the provider did not honour the slice.
                throw ApiException.of(ApiErrorCode.INTERNAL_ERROR, "Object store ignored the range request");
            }
            return new StreamHandle(new ExactLengthInputStream(object, length), total);
        } catch (RuntimeException failure) {
            closeQuietly(object);
            throw failure;
        }
    }

    @Override
    public boolean exists(String storageKey) {
        String key = requireSafeKey(storageKey);
        try {
            s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(key)
                    .build());
            return true;
        } catch (S3Exception failure) {
            if (isNotFound(failure)) {
                return false;
            }
            throw ApiException.of(ApiErrorCode.INTERNAL_ERROR, "Failed to check stored file");
        } catch (SdkException failure) {
            throw ApiException.of(ApiErrorCode.INTERNAL_ERROR, "Failed to check stored file");
        }
    }

    @Override
    public boolean deleteIfExists(String storageKey) {
        String key = requireSafeKey(storageKey);
        try {
            s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(key)
                    .build());
        } catch (S3Exception failure) {
            if (isNotFound(failure)) {
                return false;
            }
            throw ApiException.of(ApiErrorCode.INTERNAL_ERROR, "Failed to delete stored file");
        } catch (SdkException failure) {
            throw ApiException.of(ApiErrorCode.INTERNAL_ERROR, "Failed to delete stored file");
        }
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(key)
                    .build());
            return true;
        } catch (SdkException failure) {
            throw ApiException.of(ApiErrorCode.INTERNAL_ERROR, "Failed to delete stored file");
        }
    }

    @Override
    public List<StoredFileInfo> list(String subdir) {
        String prefix = StorageContentPolicy.safeSubdir(subdir) + "/";
        List<StoredFileInfo> files = new ArrayList<>();
        String continuationToken = null;
        try {
            while (true) {
                var requestBuilder = ListObjectsV2Request.builder()
                        .bucket(properties.bucket())
                        .prefix(prefix)
                        .delimiter("/");
                if (continuationToken != null) {
                    requestBuilder.continuationToken(continuationToken);
                }
                ListObjectsV2Response response = s3Client.listObjectsV2(requestBuilder.build());
                for (S3Object object : response.contents()) {
                    String key = object.key();
                    if (key == null || !key.startsWith(prefix)) {
                        continue;
                    }
                    String childName = key.substring(prefix.length());
                    if (childName.isEmpty() || childName.contains("/") || object.lastModified() == null) {
                        continue;
                    }
                    Instant lastModified = object.lastModified();
                    files.add(new StoredFileInfo(key, lastModified));
                }
                if (!Boolean.TRUE.equals(response.isTruncated())) {
                    break;
                }
                String nextToken = response.nextContinuationToken();
                if (nextToken == null || nextToken.isBlank() || nextToken.equals(continuationToken)) {
                    throw ApiException.of(ApiErrorCode.INTERNAL_ERROR, "Failed to list stored files");
                }
                continuationToken = nextToken;
            }
        } catch (SdkException failure) {
            throw ApiException.of(ApiErrorCode.INTERNAL_ERROR, "Failed to list stored files");
        }
        files.sort(Comparator.comparing(StoredFileInfo::lastModified)
                .thenComparing(StoredFileInfo::storageKey));
        return List.copyOf(files);
    }

    @Override
    public String resolveUrl(String storageKey) {
        return "/api/v1/files/" + storageKey;
    }

    private static String requireSafeKey(String storageKey) {
        if (!StorageContentPolicy.isCanonicalStorageKey(storageKey)) {
            throw ApiException.of(ApiErrorCode.FILE_NOT_FOUND);
        }
        return storageKey;
    }

    private ResponseInputStream<GetObjectResponse> getRange(String key, String range) {
        return s3Client.getObject(GetObjectRequest.builder()
                .bucket(properties.bucket())
                .key(key)
                .range(range)
                .build());
    }

    private long headSize(String key) {
        try {
            var head = s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(key)
                    .build());
            return head.contentLength() == null ? 0 : head.contentLength();
        } catch (S3Exception failure) {
            throw readFailure(failure);
        } catch (SdkException failure) {
            throw ApiException.of(ApiErrorCode.INTERNAL_ERROR, "Failed to read stored file");
        }
    }

    /**
     * Resolves the total object size of a range response. A conforming object
     * store reports it in {@code Content-Range}; a provider that ignored the
     * range answers {@code 200} with the full size as the body length.
     */
    private long totalSize(String key, GetObjectResponse response) {
        long[] contentRange = parseContentRange(response.contentRange());
        if (contentRange != null) {
            return contentRange[2];
        }
        Integer status = response.sdkHttpResponse() == null
                ? null : response.sdkHttpResponse().statusCode();
        if (status != null && status == 200 && response.contentLength() != null) {
            return response.contentLength();
        }
        return headSize(key);
    }

    /**
     * Requires a conforming {@code Content-Range} for a committed slice.
     * Silently accepting a full-object response would put the wrong bytes in
     * the requested offset.
     */
    private static long[] requireContentRange(GetObjectResponse response, long start, long end) {
        long[] contentRange = parseContentRange(response.contentRange());
        if (contentRange == null || contentRange[0] != start || contentRange[1] != end) {
            throw ApiException.of(ApiErrorCode.INTERNAL_ERROR, "Object store ignored the range request");
        }
        return contentRange;
    }

    private static long[] parseContentRange(String contentRange) {
        if (contentRange == null || !contentRange.startsWith("bytes ")) {
            return null;
        }
        int dash = contentRange.indexOf('-');
        int slash = contentRange.indexOf('/');
        if (dash < 0 || slash < 0 || slash < dash) {
            return null;
        }
        try {
            long start = Long.parseLong(contentRange.substring("bytes ".length(), dash).trim());
            long end = Long.parseLong(contentRange.substring(dash + 1, slash).trim());
            long total = Long.parseLong(contentRange.substring(slash + 1).trim());
            return new long[] {start, end, total};
        } catch (NumberFormatException failure) {
            return null;
        }
    }

    private static ApiException readFailure(S3Exception failure) {
        if (isNotFound(failure)) {
            return ApiException.of(ApiErrorCode.FILE_NOT_FOUND);
        }
        return ApiException.of(ApiErrorCode.INTERNAL_ERROR, "Failed to read stored file");
    }

    private static void closeQuietly(InputStream content) {
        try {
            content.close();
        } catch (IOException ignored) {
            // Best-effort abort of a range response on an error path.
        }
    }

    private static boolean isNotFound(S3Exception failure) {
        if (failure.statusCode() == 404) {
            return true;
        }
        return failure.awsErrorDetails() != null
                && ("NoSuchKey".equals(failure.awsErrorDetails().errorCode())
                || "NotFound".equals(failure.awsErrorDetails().errorCode()));
    }

    /** Caps a range response at the exact requested length. */
    private static final class ExactLengthInputStream extends FilterInputStream {

        private long remaining;

        ExactLengthInputStream(InputStream in, long length) {
            super(in);
            this.remaining = length;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int value = super.read();
            if (value >= 0) {
                remaining--;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int read = super.read(buffer, offset, (int) Math.min(length, remaining));
            if (read > 0) {
                remaining -= read;
            }
            return read;
        }

        @Override
        public long skip(long n) throws IOException {
            long skipped = super.skip(Math.min(n, remaining));
            remaining -= skipped;
            return skipped;
        }

        @Override
        public int available() throws IOException {
            return (int) Math.min(super.available(), remaining);
        }
    }
}
