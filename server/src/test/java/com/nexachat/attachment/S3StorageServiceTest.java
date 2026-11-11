package com.nexachat.attachment;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class S3StorageServiceTest {

    private static final String BUCKET = "nexa-storage-test";
    private static final S3StorageProperties PROPERTIES = new S3StorageProperties(
            "http://localhost:9000", "auto", BUCKET, "test-access", "test-secret",
            null, null, null, null);

    @Test
    void avatarSweepListsObjectsAcrossListObjectsV2Pages() {
        S3Client client = mock(S3Client.class);
        when(client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(
                ListObjectsV2Response.builder()
                        .isTruncated(true)
                        .nextContinuationToken("page-2")
                        .contents(
                                object("avatars/new.png", "2026-01-03T00:00:00Z"),
                                object("avatars/nested/ignored.png", "2026-01-01T00:00:00Z"),
                                object("avatars/", "2026-01-01T00:00:00Z"))
                        .build(),
                ListObjectsV2Response.builder()
                        .isTruncated(false)
                        .contents(
                                object("avatars/middle.png", "2026-01-02T00:00:00Z"),
                                object("avatars/old.png", "2026-01-01T00:00:00Z"))
                        .build());

        List<StorageService.StoredFileInfo> listed = new S3StorageService(client, PROPERTIES).list("avatars");

        assertThat(listed).extracting(StorageService.StoredFileInfo::storageKey)
                .containsExactly("avatars/old.png", "avatars/middle.png", "avatars/new.png");
        ArgumentCaptor<ListObjectsV2Request> requests = ArgumentCaptor.forClass(ListObjectsV2Request.class);
        verify(client, times(2)).listObjectsV2(requests.capture());
        assertThat(requests.getAllValues())
                .allSatisfy(request -> {
                    assertThat(request.bucket()).isEqualTo(BUCKET);
                    assertThat(request.prefix()).isEqualTo("avatars/");
                    assertThat(request.delimiter()).isEqualTo("/");
                });
        assertThat(requests.getAllValues()).extracting(ListObjectsV2Request::continuationToken)
                .containsExactly(null, "page-2");
    }

    @Test
    void deleteIfExistsPurgesAnExistingObject() {
        S3Client client = mock(S3Client.class);
        when(client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(4L).build());
        when(client.deleteObject(any(DeleteObjectRequest.class)))
                .thenReturn(DeleteObjectResponse.builder().build());

        boolean deleted = new S3StorageService(client, PROPERTIES)
                .deleteIfExists("avatars/removed.png");

        assertThat(deleted).isTrue();
        ArgumentCaptor<DeleteObjectRequest> request = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(client).deleteObject(request.capture());
        assertThat(request.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(request.getValue().key()).isEqualTo("avatars/removed.png");
    }

    @Test
    void deleteIfExistsDoesNotIssueDeleteForAnAbsentObject() {
        S3Client client = mock(S3Client.class);
        when(client.headObject(any(HeadObjectRequest.class))).thenThrow(
                S3Exception.builder().statusCode(404).build());

        boolean deleted = new S3StorageService(client, PROPERTIES)
                .deleteIfExists("avatars/already-removed.png");

        assertThat(deleted).isFalse();
        verify(client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    // ------------------------------------------------- bounded range reads

    @Test
    void readPrefix_requestsExactlyTheSniffWindow() {
        S3Client client = mock(S3Client.class);
        AtomicBoolean closed = new AtomicBoolean();
        when(client.getObject(any(GetObjectRequest.class)))
                .thenReturn(response("bytes 0-3/12", 4, "0123", closed));

        StorageService.PrefixRead prefix = new S3StorageService(client, PROPERTIES)
                .readPrefix("attachments/x.png", 4);

        assertThat(new String(prefix.bytes(), StandardCharsets.UTF_8)).isEqualTo("0123");
        assertThat(prefix.sizeBytes()).isEqualTo(12);
        ArgumentCaptor<GetObjectRequest> request = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(client).getObject(request.capture());
        assertThat(request.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(request.getValue().key()).isEqualTo("attachments/x.png");
        assertThat(request.getValue().range()).isEqualTo("bytes=0-3");
        assertThat(closed).isTrue();
    }

    @Test
    void readPrefix_ignoresANonRangedResponseBodyPastTheWindow() {
        S3Client client = mock(S3Client.class);
        AtomicBoolean closed = new AtomicBoolean();
        when(client.getObject(any(GetObjectRequest.class)))
                .thenReturn(plainResponse(5, "01234", closed));
        when(client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(5L).build());

        StorageService.PrefixRead prefix = new S3StorageService(client, PROPERTIES)
                .readPrefix("attachments/x.png", 3);

        assertThat(new String(prefix.bytes(), StandardCharsets.UTF_8)).isEqualTo("012");
        assertThat(prefix.sizeBytes()).isEqualTo(5);
        assertThat(closed).isTrue();
    }

    @Test
    void readPrefix_emptyObjectIsReportedAsZeroBytes() {
        S3Client client = mock(S3Client.class);
        when(client.getObject(any(GetObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(416).build());
        when(client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(0L).build());

        StorageService.PrefixRead prefix = new S3StorageService(client, PROPERTIES)
                .readPrefix("attachments/empty.png", 64);

        assertThat(prefix.bytes()).isEmpty();
        assertThat(prefix.sizeBytes()).isZero();
    }

    @Test
    void readSuffix_requestsTheExactTailRange() {
        S3Client client = mock(S3Client.class);
        AtomicBoolean closed = new AtomicBoolean();
        when(client.getObject(any(GetObjectRequest.class)))
                .thenReturn(response("bytes 8-11/12", 4, "89ab", closed));

        byte[] suffix = new S3StorageService(client, PROPERTIES)
                .readSuffix("attachments/x.png", 12, 4);

        assertThat(new String(suffix, StandardCharsets.UTF_8)).isEqualTo("89ab");
        ArgumentCaptor<GetObjectRequest> request = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(client).getObject(request.capture());
        assertThat(request.getValue().range()).isEqualTo("bytes=8-11");
        assertThat(closed).isTrue();
    }

    @Test
    void readSuffix_shortProviderBodyFailsClosedAndReleasesTheStream() {
        S3Client client = mock(S3Client.class);
        AtomicBoolean closed = new AtomicBoolean();
        when(client.getObject(any(GetObjectRequest.class)))
                .thenReturn(response("bytes 8-11/12", 4, "89", closed));

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> new S3StorageService(client, PROPERTIES)
                        .readSuffix("attachments/x.png", 12, 4));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.INTERNAL_ERROR);
        assertThat(closed).isTrue();
    }

    @Test
    void readSuffix_rejectsAProviderThatIgnoredTheRange() {
        S3Client client = mock(S3Client.class);
        AtomicBoolean closed = new AtomicBoolean();
        when(client.getObject(any(GetObjectRequest.class)))
                .thenReturn(plainResponse(12, "0123456789ab", closed));

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> new S3StorageService(client, PROPERTIES)
                        .readSuffix("attachments/x.png", 12, 4));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.INTERNAL_ERROR);
        assertThat(closed).isTrue();
    }

    @Test
    void openRange_requestsTheExactSliceAndStopsAtItsLength() throws IOException {
        S3Client client = mock(S3Client.class);
        AtomicBoolean closed = new AtomicBoolean();
        when(client.getObject(any(GetObjectRequest.class)))
                .thenReturn(response("bytes 4-5/10", 2, "45EXTRA", closed));

        try (StorageService.StreamHandle handle = new S3StorageService(client, PROPERTIES)
                .openRange("attachments/x.png", 4, 2)) {
            assertThat(handle.sizeBytes()).isEqualTo(10);
            assertThat(handle.content().readAllBytes())
                    .containsExactly("45".getBytes(StandardCharsets.UTF_8));
        }

        ArgumentCaptor<GetObjectRequest> request = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(client).getObject(request.capture());
        assertThat(request.getValue().range()).isEqualTo("bytes=4-5");
        assertThat(closed).isTrue();
    }

    @Test
    void openRange_rejectsAnIgnoredSliceAtANonZeroOffset() {
        S3Client client = mock(S3Client.class);
        AtomicBoolean closed = new AtomicBoolean();
        when(client.getObject(any(GetObjectRequest.class)))
                .thenReturn(plainResponse(10, "0123456789", closed));
        when(client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(10L).build());

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> new S3StorageService(client, PROPERTIES)
                        .openRange("attachments/x.png", 4, 2));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.INTERNAL_ERROR);
        assertThat(closed).isTrue();
    }

    @Test
    void openRange_missingObjectMapsToFileNotFound() {
        S3Client client = mock(S3Client.class);
        when(client.getObject(any(GetObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(404).build());

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> new S3StorageService(client, PROPERTIES)
                        .openRange("attachments/missing.png", 0, 4));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.FILE_NOT_FOUND);
    }

    @Test
    void readPrefix_providerFailureMapsToInternalError() {
        S3Client client = mock(S3Client.class);
        when(client.getObject(any(GetObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(500).build());

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> new S3StorageService(client, PROPERTIES)
                        .readPrefix("attachments/x.png", 4));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.INTERNAL_ERROR);
    }

    @Test
    void readPrefix_rejectsAHostileKeyBeforeAnyCall() {
        S3Client client = mock(S3Client.class);

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> new S3StorageService(client, PROPERTIES).readPrefix("../x.png", 4));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.FILE_NOT_FOUND);
        verify(client, never()).getObject(any(GetObjectRequest.class));
    }

    private static ResponseInputStream<GetObjectResponse> response(
            String contentRange, long length, String body, AtomicBoolean closed) {
        GetObjectResponse response = GetObjectResponse.builder()
                .contentRange(contentRange)
                .contentLength(length)
                .build();
        return stream(response, body, closed);
    }

    private static ResponseInputStream<GetObjectResponse> plainResponse(
            long length, String body, AtomicBoolean closed) {
        GetObjectResponse response = GetObjectResponse.builder()
                .contentLength(length)
                .build();
        return stream(response, body, closed);
    }

    private static ResponseInputStream<GetObjectResponse> stream(
            GetObjectResponse response, String body, AtomicBoolean closed) {
        ByteArrayInputStream delegate = new ByteArrayInputStream(
                body.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public void close() throws IOException {
                closed.set(true);
                super.close();
            }
        };
        return new ResponseInputStream<>(response, AbortableInputStream.create(delegate));
    }

    private static S3Object object(String key, String lastModified) {
        return S3Object.builder().key(key).lastModified(Instant.parse(lastModified)).build();
    }
}
