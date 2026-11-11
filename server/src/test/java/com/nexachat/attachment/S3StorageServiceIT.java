package com.nexachat.attachment;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class S3StorageServiceIT {

    private static final String BUCKET = "nexa-storage-it";
    private static final String ACCESS_KEY = "test-access-key";
    private static final String SECRET_KEY = "test-secret-key";
    private static final byte[] PNG_BYTES = new byte[] {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52
    };

    @Container
    private static final GenericContainer<?> S3MOCK = new GenericContainer<>(
            DockerImageName.parse("adobe/s3mock:5.2.3"))
            .withExposedPorts(9090)
            .waitingFor(Wait.forHttp("/favicon.ico")
                    .forPort(9090)
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(2)));

    private static S3Client client;
    private static S3StorageService storage;

    @BeforeAll
    static void createBucket() {
        URI endpoint = URI.create("http://" + S3MOCK.getHost() + ":" + S3MOCK.getMappedPort(9090));
        S3StorageProperties properties = new S3StorageProperties(
                endpoint.toString(), "us-east-1", BUCKET, ACCESS_KEY, SECRET_KEY);
        client = new S3StorageConfiguration().s3StorageClient(properties);
        client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        storage = new S3StorageService(client, properties);
    }

    @AfterAll
    static void closeClient() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    void storesListsStreamsAndPurgesObjectsThroughS3CompatibleApi() throws Exception {
        StorageService.StoredFile stored = storage.store("avatars", "ignored.png", "image/png", PNG_BYTES);

        assertThat(storage.resolveUrl(stored.storageKey()))
                .startsWith("/api/v1/files/")
                .doesNotContain(BUCKET)
                .doesNotContain(S3MOCK.getHost());
        assertThat(storage.exists(stored.storageKey())).isTrue();
        assertThat(storage.read(stored.storageKey())).containsExactly(PNG_BYTES);
        try (StorageService.StreamHandle handle = storage.open(stored.storageKey())) {
            assertThat(handle.sizeBytes()).isEqualTo(PNG_BYTES.length);
            assertThat(handle.content().readAllBytes()).containsExactly(PNG_BYTES);
        }
        assertThat(storage.list("avatars"))
                .extracting(StorageService.StoredFileInfo::storageKey)
                .containsExactly(stored.storageKey());

        assertThat(storage.deleteIfExists(stored.storageKey())).isTrue();
        assertThat(storage.deleteIfExists(stored.storageKey())).isFalse();
        assertThat(storage.exists(stored.storageKey())).isFalse();
    }
}
