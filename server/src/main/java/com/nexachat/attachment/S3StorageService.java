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

import java.io.IOException;
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

    private static boolean isNotFound(S3Exception failure) {
        if (failure.statusCode() == 404) {
            return true;
        }
        return failure.awsErrorDetails() != null
                && ("NoSuchKey".equals(failure.awsErrorDetails().errorCode())
                || "NotFound".equals(failure.awsErrorDetails().errorCode()));
    }
}
