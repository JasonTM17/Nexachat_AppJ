package com.nexachat.attachment;

import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AvatarControllerTest {

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
        controller = new AvatarController(storageService, userRepository, referenceCoordinator);
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
}
