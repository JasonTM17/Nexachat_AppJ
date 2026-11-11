package com.nexachat.attachment;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class S3StorageServiceTest {

    private static final String BUCKET = "nexa-storage-test";
    private static final S3StorageProperties PROPERTIES = new S3StorageProperties(
            "http://localhost:9000", "auto", BUCKET, "test-access", "test-secret");

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

    private static S3Object object(String key, String lastModified) {
        return S3Object.builder().key(key).lastModified(Instant.parse(lastModified)).build();
    }
}
