package com.nexachat.attachment;

import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AvatarControllerTest {

    private static final byte[] PNG_BYTES = new byte[] {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0, 0, 0, 0x0D, 0x49, 0x48, 0x44, 0x52,
    };

    @Mock
    private StorageService storageService;
    @Mock
    private UserRepository userRepository;
    @Mock
    private AvatarReferenceCoordinator referenceCoordinator;

    private AvatarController controller;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        controller = new AvatarController(storageService, userRepository, referenceCoordinator,
                new UploadAdmission(UploadAdmission.DEFAULT_MAX_CONCURRENT,
                        UploadAdmission.DEFAULT_ACQUIRE_TIMEOUT_MILLIS), transactionManager());
        org.mockito.Mockito.lenient().when(userRepository.findById(userId)).thenReturn(Optional.of(user()));
        org.mockito.Mockito.lenient().when(storageService.exists(anyString())).thenReturn(true);
    }

    private MultipartFile pngFile() {
        return new org.springframework.mock.web.MockMultipartFile(
                "file", "me.png", "image/png", PNG_BYTES);
    }

    private org.springframework.transaction.PlatformTransactionManager transactionManager() {
        return new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                mock(javax.sql.DataSource.class, org.mockito.Mockito.RETURNS_DEEP_STUBS));
    }

    private User user() {
        User user = User.create("+84905550002", "hash", "Avatar Owner");
        ReflectionTestUtils.setField(user, "id", userId);
        return user;
    }

    @Test
    void uploadAvatar_oversizeRejectedBeforeBuffering() throws Exception {
        MultipartFile oversized = mock(MultipartFile.class);
        when(oversized.isEmpty()).thenReturn(false);
        when(oversized.getSize()).thenReturn(StorageService.MAX_FILE_SIZE_BYTES + 1);

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> controller.uploadAvatar(new JwtPrincipal(userId), List.of(oversized)));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.FILE_TOO_LARGE);
        verify(oversized, never()).getBytes();
        verify(storageService, never()).store(any(), any(), any(), any());
        verify(userRepository, never()).findById(userId);
    }

    @Test
    void uploadAvatar_beyondUploadAdmission_rejectsExtraCaller() throws Exception {
        AvatarController limited = new AvatarController(
                storageService, userRepository, referenceCoordinator, new UploadAdmission(1, 200), transactionManager());
        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(user()));
        when(storageService.resolveUrl(anyString()))
                .thenAnswer(i -> "/api/v1/files/" + i.getArgument(0));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(storageService.store(eq("avatars"), any(), any(), any(byte[].class)))
                .thenAnswer(invocation -> {
                    entered.countDown();
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test release latch timed out");
                    }
                    return new StorageService.StoredFile("avatars/k.png", PNG_BYTES.length, "image/png");
                });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<AvatarController.AvatarResponse> first = executor.submit(
                    () -> limited.uploadAvatar(new JwtPrincipal(userId), List.of(pngFile())));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

            ApiException rejection = catchThrowableOfType(ApiException.class,
                    () -> limited.uploadAvatar(new JwtPrincipal(userId), List.of(pngFile())));

            assertThat(rejection.getCode()).isEqualTo(ApiErrorCode.RATE_LIMITED);
            assertThat(rejection.getHttpStatus()).isEqualTo(429);

            release.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS).avatarUrl())
                    .isEqualTo("/api/v1/files/avatars/k.png");
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void uploadAvatar_clientAbort_releasesTheSlot() throws Exception {
        UploadAdmission admission = new UploadAdmission(1, 200);
        AvatarController limited = new AvatarController(
                storageService, userRepository, referenceCoordinator, admission, transactionManager());
        MultipartFile aborted = mock(MultipartFile.class);
        when(aborted.isEmpty()).thenReturn(false);
        when(aborted.getSize()).thenReturn(1024L);
        when(aborted.getBytes()).thenThrow(new IOException("connection reset"));

        IOException failure = catchThrowableOfType(IOException.class,
                () -> limited.uploadAvatar(new JwtPrincipal(userId), List.of(aborted)));

        assertThat(failure).hasMessageContaining("connection reset");
        // A leaked permit would make this acquire time out (200 ms) and throw.
        admission.acquire().close();
    }

    @Test
    void uploadAvatar_storageFailure_releasesTheSlot() {
        UploadAdmission admission = new UploadAdmission(1, 200);
        AvatarController limited = new AvatarController(
                storageService, userRepository, referenceCoordinator, admission, transactionManager());
        when(storageService.store(eq("avatars"), any(), any(), any(byte[].class)))
                .thenThrow(ApiException.of(ApiErrorCode.INTERNAL_ERROR, "Failed to store file"));

        ApiException failure = catchThrowableOfType(ApiException.class,
                () -> limited.uploadAvatar(new JwtPrincipal(userId), List.of(pngFile())));

        assertThat(failure.getCode()).isEqualTo(ApiErrorCode.INTERNAL_ERROR);
        // A leaked permit would make this acquire time out (200 ms) and throw.
        admission.acquire().close();
    }
}
