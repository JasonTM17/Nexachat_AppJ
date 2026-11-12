package com.nexachat.attachment;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.message.MessageAttachmentRepository;
import com.nexachat.message.dto.AttachmentResponse;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Semaphore accounting for the attachment upload path: the limit is enforced,
 * and every exit path (success, validation failure, storage failure, client
 * abort) returns the slot.
 */
@ExtendWith(MockitoExtension.class)
class AttachmentUploadAdmissionTest {

    private static final byte[] PNG_BYTES = hex("89504E470D0A1A0A0000000D49484452");

    @Mock
    private MessageAttachmentRepository attachmentRepository;
    @Mock
    private ConversationMemberRepository memberRepository;
    @Mock
    private StorageService storageService;
    @Mock
    private UserRepository userRepository;

    private final UUID ownerId = UUID.randomUUID();

    private UploadAdmission admission;
    private AttachmentService attachmentService;

    @BeforeEach
    void setUp() {
        admission = new UploadAdmission(2, 200);
        attachmentService = new AttachmentService(
                attachmentRepository, memberRepository, storageService, userRepository, admission,
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                        mock(javax.sql.DataSource.class, org.mockito.Mockito.RETURNS_DEEP_STUBS)));
        lenient().when(storageService.resolveUrl(anyString()))
                .thenAnswer(i -> "/api/v1/files/" + i.getArgument(0));
        lenient().when(userRepository.findByIdForUpdate(ownerId))
                .thenReturn(Optional.of(user(ownerId)));
        lenient().when(userRepository.findById(ownerId)).thenReturn(Optional.of(user(ownerId)));
    }

    private static User user(UUID id) {
        User user = User.create("+84905550001", "hash", "Uploader");
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private static byte[] hex(String hex) {
        int length = hex.length();
        byte[] out = new byte[length / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static MockMultipartFile pngFile() {
        return new MockMultipartFile("file", "photo.png", "image/png", PNG_BYTES);
    }

    private static StorageService.StoredFile storedFile() {
        return new StorageService.StoredFile("attachments/x.png", PNG_BYTES.length, "image/png");
    }

    @Test
    void concurrentUploads_beyondLimit_rejectTheExtraCaller() throws Exception {
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        when(storageService.store(anyString(), anyString(), anyString(), any(byte[].class)))
                .thenAnswer(invocation -> {
                    entered.countDown();
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test release latch timed out");
                    }
                    return storedFile();
                });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<AttachmentResponse> first = executor.submit(
                    () -> attachmentService.upload(ownerId, pngFile(), null, null, null, null));
            Future<AttachmentResponse> second = executor.submit(
                    () -> attachmentService.upload(ownerId, pngFile(), null, null, null, null));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

            ApiException rejection = catchThrowableOfType(ApiException.class,
                    () -> attachmentService.upload(ownerId, pngFile(), null, null, null, null));

            assertThat(rejection.getCode()).isEqualTo(ApiErrorCode.RATE_LIMITED);
            assertThat(rejection.getHttpStatus()).isEqualTo(429);
            assertThat(rejection.getDetails()).containsEntry("retryAfterSeconds", 1L);

            release.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS).url()).isNotBlank();
            assertThat(second.get(10, TimeUnit.SECONDS).url()).isNotBlank();
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void success_releasesTheSlot() {
        when(storageService.store(anyString(), anyString(), anyString(), any(byte[].class)))
                .thenReturn(storedFile());

        attachmentService.upload(ownerId, pngFile(), null, null, null, null);

        assertSlotIsFree();
    }

    @Test
    void storageFailure_releasesTheSlot() {
        when(storageService.store(anyString(), anyString(), anyString(), any(byte[].class)))
                .thenThrow(ApiException.of(ApiErrorCode.INTERNAL_ERROR, "Failed to store file"));

        ApiException failure = catchThrowableOfType(ApiException.class,
                () -> attachmentService.upload(ownerId, pngFile(), null, null, null, null));

        assertThat(failure.getCode()).isEqualTo(ApiErrorCode.INTERNAL_ERROR);
        assertSlotIsFree();
    }

    @Test
    void blurHashValidationFailure_releasesTheSlot() {
        MockMultipartFile text = new MockMultipartFile(
                "file", "note.txt", "text/plain", "not an image".getBytes());

        ApiException failure = catchThrowableOfType(ApiException.class,
                () -> attachmentService.upload(
                        ownerId, text, null, null, null, "LEHV6nWB2yk8pyo0adR*.7kCMdnj"));

        assertThat(failure.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
        assertSlotIsFree();
    }

    @Test
    void clientAbortWhileReadingBytes_releasesTheSlot() throws Exception {
        MultipartFile aborted = mock(MultipartFile.class);
        when(aborted.isEmpty()).thenReturn(false);
        when(aborted.getSize()).thenReturn(1024L);
        when(aborted.getBytes()).thenThrow(new IOException("connection reset"));

        ApiException failure = catchThrowableOfType(ApiException.class,
                () -> attachmentService.upload(ownerId, aborted, null, null, null, null));

        assertThat(failure.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
        assertSlotIsFree();
    }

    @Test
    void thousandSequentialUploads_doNotLeakPermits() {
        UploadAdmission singleSlot = new UploadAdmission(1, 0);
        AttachmentService service = new AttachmentService(
                attachmentRepository, memberRepository, storageService, userRepository, singleSlot,
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                        mock(javax.sql.DataSource.class, org.mockito.Mockito.RETURNS_DEEP_STUBS)));
        when(storageService.store(anyString(), anyString(), anyString(), any(byte[].class)))
                .thenReturn(storedFile());

        for (int i = 0; i < 1000; i++) {
            service.upload(ownerId, pngFile(), null, null, null, null);
        }

        assertThat(singleSlot.availablePermits()).isEqualTo(1);
    }

    /**
     * A leaked permit would make this acquire time out after 200 ms and fail;
     * a released one returns immediately.
     */
    private void assertSlotIsFree() {
        try (UploadAdmission.Permit permit = admission.acquire()) {
            assertThat(admission.availablePermits()).isEqualTo(1);
        }
    }
}
