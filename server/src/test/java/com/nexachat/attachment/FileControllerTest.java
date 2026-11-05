package com.nexachat.attachment;

import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Controller-level coverage of {@link FileController}: the raw
 * {@code /api/v1/files/**} door must reject hostile keys in
 * {@code extractKey} BEFORE any authZ lookup or disk read, and the
 * attachment member-check must run before bytes leave storage.
 * Service-side rules are covered in {@link AttachmentServiceTest} /
 * {@link LocalStorageServiceTest}; the end-to-end HTTP path is covered in
 * {@link AttachmentFlowIT}.
 */
@ExtendWith(MockitoExtension.class)
class FileControllerTest {

    private static final byte[] PNG_BYTES = hex("89504E470D0A1A0A0000000D49484452");

    @Mock
    private StorageService storageService;
    @Mock
    private AttachmentService attachmentService;

    private FileController fileController;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        fileController = new FileController(storageService, attachmentService);
    }

    private static MockHttpServletRequest mappedTo(String pathWithinMapping) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(
                HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE, pathWithinMapping);
        return request;
    }

    // ------------------------------------------------------------ authN

    @Test
    void serve_unauthenticatedPrincipal_throwsUnauthorized() {
        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> fileController.serve(mappedTo("/api/v1/files/avatars/x.png"), null));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.UNAUTHORIZED);
        assertThat(ex.getHttpStatus()).isEqualTo(401);
        // Identity is checked before the key is even extracted.
        verify(attachmentService, never()).assertStorageKeyReadable(anyString(), any());
        verify(storageService, never()).read(anyString());
    }

    // --------------------------------------------------------- traversal

    @Test
    void serve_dotDotKey_throwsFileNotFoundBeforeStorage() {
        for (String hostile : new String[] {
                "/api/v1/files/../secret.png",
                "/api/v1/files/attachments/../../escape.png"}) {
            ApiException ex = catchThrowableOfType(ApiException.class, () ->
                    fileController.serve(mappedTo(hostile), new JwtPrincipal(userId)));
            assertThat(ex.getCode()).isEqualTo(ApiErrorCode.FILE_NOT_FOUND);
        }
        // Rejected in extractKey — authZ and storage are never reached.
        verify(attachmentService, never()).assertStorageKeyReadable(anyString(), any());
        verify(storageService, never()).read(anyString());
    }

    @Test
    void serve_backslashKey_throwsFileNotFoundBeforeStorage() {
        ApiException ex = catchThrowableOfType(ApiException.class, () ->
                fileController.serve(
                        mappedTo("/api/v1/files/dir\\..\\secret.png"), new JwtPrincipal(userId)));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.FILE_NOT_FOUND);
        verify(storageService, never()).read(anyString());
    }

    @Test
    void serve_blankOrWrongPrefixKey_throwsFileNotFound() {
        JwtPrincipal principal = new JwtPrincipal(userId);
        for (String bad : new String[] {"/api/v1/files/", "/api/v1/other/x.png"}) {
            ApiException ex = catchThrowableOfType(ApiException.class,
                    () -> fileController.serve(mappedTo(bad), principal));
            assertThat(ex.getCode()).isEqualTo(ApiErrorCode.FILE_NOT_FOUND);
        }
        verify(storageService, never()).read(anyString());
    }

    // ------------------------------------------------------ authZ ordering

    @Test
    void serve_attachmentKey_memberCheckPropagatesAndSkipsRead() {
        MockHttpServletRequest request = mappedTo("/api/v1/files/attachments/x.png");
        org.mockito.Mockito.doThrow(ApiException.of(ApiErrorCode.NOT_MEMBER))
                .when(attachmentService)
                .assertStorageKeyReadable("attachments/x.png", userId);

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> fileController.serve(request, new JwtPrincipal(userId)));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.NOT_MEMBER);
        // A denied attachment key never reaches the disk read.
        verify(storageService, never()).read(anyString());
    }

    // ------------------------------------------------------------ happy path

    @Test
    void serve_validKey_memberCheckThenRead_returnsSniffedType() {
        MockHttpServletRequest request = mappedTo("/api/v1/files/avatars/x.png");
        when(storageService.read("avatars/x.png")).thenReturn(PNG_BYTES);

        ResponseEntity<byte[]> response =
                fileController.serve(request, new JwtPrincipal(userId));

        verify(attachmentService).assertStorageKeyReadable("avatars/x.png", userId);
        verify(storageService).read("avatars/x.png");
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("image/png");
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getBody()).containsExactly(PNG_BYTES);
    }

    @Test
    void serve_noMappingAttribute_fallsBackToRequestUri() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/files/avatars/x.png");
        when(storageService.read("avatars/x.png")).thenReturn(PNG_BYTES);

        ResponseEntity<byte[]> response =
                fileController.serve(request, new JwtPrincipal(userId));

        verify(storageService).read("avatars/x.png");
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    private static byte[] hex(String hex) {
        int length = hex.length();
        byte[] out = new byte[length / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
