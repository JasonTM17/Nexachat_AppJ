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
import org.springframework.http.converter.ResourceHttpMessageConverter;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
 *
 * <p>The download path is also asserted to be range-aware and streaming:
 * the response body is written through the real
 * {@link ResourceHttpMessageConverter} into a capture response, and the
 * resulting bytes and headers are checked for 200/206/416 semantics.
 * Service-side rules are covered in {@link AttachmentServiceTest} /
 * {@link LocalStorageServiceTest}; the end-to-end HTTP path is covered in
 * {@link AttachmentFlowIT}.</p>
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

    private static MockHttpServletRequest mappedTo(String pathWithinMapping, String range) {
        MockHttpServletRequest request = mappedTo(pathWithinMapping);
        if (range != null) {
            request.addHeader("Range", range);
        }
        return request;
    }

    private static StorageService.StreamHandle handle(byte[] content) {
        return new StorageService.StreamHandle(
                new ByteArrayInputStream(content), content.length);
    }

    /** Writes the response body through the real converter and returns the capture. */
    private static MockHttpServletResponse drain(ResponseEntity<?> response) throws IOException {
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();
        servletResponse.setStatus(response.getStatusCodeValue());
        response.getHeaders().forEach((name, values) ->
                values.forEach(value -> servletResponse.addHeader(name, value)));
        new ResourceHttpMessageConverter().write(
                (org.springframework.core.io.Resource) response.getBody(),
                response.getHeaders().getContentType(),
                new ServletServerHttpResponse(servletResponse));
        return servletResponse;
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
        verify(storageService, never()).open(anyString());
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
        verify(storageService, never()).open(anyString());
    }

    @Test
    void serve_backslashKey_throwsFileNotFoundBeforeStorage() {
        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> fileController.serve(
                        mappedTo("/api/v1/files/dir\\..\\secret.png"), new JwtPrincipal(userId)));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.FILE_NOT_FOUND);
        verify(storageService, never()).open(anyString());
    }

    @Test
    void serve_blankOrWrongPrefixKey_throwsFileNotFound() {
        JwtPrincipal principal = new JwtPrincipal(userId);
        for (String bad : new String[] {"/api/v1/files/", "/api/v1/other/x.png"}) {
            ApiException ex = catchThrowableOfType(ApiException.class,
                    () -> fileController.serve(mappedTo(bad), principal));
            assertThat(ex.getCode()).isEqualTo(ApiErrorCode.FILE_NOT_FOUND);
        }
        verify(storageService, never()).open(anyString());
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
        verify(storageService, never()).open(anyString());
    }

    // ------------------------------------------------------------ happy path

    @Test
    void serve_validKey_memberCheckThenRead_returnsSniffedType() throws Exception {
        MockHttpServletRequest request = mappedTo("/api/v1/files/avatars/x.png");
        when(storageService.open("avatars/x.png")).thenReturn(handle(PNG_BYTES));

        ResponseEntity<?> response = fileController.serve(request, new JwtPrincipal(userId));

        verify(attachmentService).assertStorageKeyReadable("avatars/x.png", userId);
        verify(storageService).open("avatars/x.png");
        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("image/png");
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeaders().getFirst("Content-Disposition")).isEqualTo("inline");
        assertThat(response.getHeaders().getFirst("Accept-Ranges")).isEqualTo("bytes");
        assertThat(response.getHeaders().getContentLength()).isEqualTo(PNG_BYTES.length);
        assertThat(drain(response).getContentAsByteArray()).containsExactly(PNG_BYTES);
    }

    @Test
    void serve_noMappingAttribute_fallsBackToRequestUri() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/files/avatars/x.png");
        when(storageService.open("avatars/x.png")).thenReturn(handle(PNG_BYTES));

        ResponseEntity<?> response = fileController.serve(request, new JwtPrincipal(userId));

        verify(storageService).open("avatars/x.png");
        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        assertThat(drain(response).getContentAsByteArray()).containsExactly(PNG_BYTES);
    }

    @Test
    void serve_unknownKey_throwsFileNotFound() {
        when(storageService.open("avatars/missing.png"))
                .thenThrow(ApiException.of(ApiErrorCode.FILE_NOT_FOUND));

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> fileController.serve(
                        mappedTo("/api/v1/files/avatars/missing.png"), new JwtPrincipal(userId)));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.FILE_NOT_FOUND);
    }

    // -------------------------------------------------------------- ranges

    @Test
    void serve_firstRange_returns206WithContentRangeAndSlice() throws Exception {
        byte[] body = "0123456789".getBytes();
        when(storageService.open("attachments/x.txt")).thenReturn(handle(body));

        ResponseEntity<?> response = fileController.serve(
                mappedTo("/api/v1/files/attachments/x.txt", "bytes=2-5"),
                new JwtPrincipal(userId));

        assertThat(response.getStatusCodeValue()).isEqualTo(206);
        MockHttpServletResponse servletResponse = drain(response);
        assertThat(servletResponse.getHeader("Content-Range")).isEqualTo("bytes 2-5/10");
        assertThat(servletResponse.getContentLength()).isEqualTo(4);
        assertThat(servletResponse.getContentAsByteArray()).containsExactly("2345".getBytes());
        assertThat(servletResponse.getHeader("Accept-Ranges")).isEqualTo("bytes");
    }

    @Test
    void serve_openEndedRange_returns206ToEof() throws Exception {
        byte[] body = "0123456789".getBytes();
        when(storageService.open("attachments/x.txt")).thenReturn(handle(body));

        ResponseEntity<?> response = fileController.serve(
                mappedTo("/api/v1/files/attachments/x.txt", "bytes=4-"),
                new JwtPrincipal(userId));

        assertThat(response.getStatusCodeValue()).isEqualTo(206);
        MockHttpServletResponse servletResponse = drain(response);
        assertThat(servletResponse.getHeader("Content-Range")).isEqualTo("bytes 4-9/10");
        assertThat(servletResponse.getContentLength()).isEqualTo(6);
        assertThat(servletResponse.getContentAsByteArray()).containsExactly("456789".getBytes());
    }

    @Test
    void serve_suffixRange_returnsLastNBytes() throws Exception {
        byte[] body = "0123456789".getBytes();
        when(storageService.open("attachments/x.txt")).thenReturn(handle(body));

        ResponseEntity<?> response = fileController.serve(
                mappedTo("/api/v1/files/attachments/x.txt", "bytes=-3"),
                new JwtPrincipal(userId));

        assertThat(response.getStatusCodeValue()).isEqualTo(206);
        MockHttpServletResponse servletResponse = drain(response);
        assertThat(servletResponse.getHeader("Content-Range")).isEqualTo("bytes 7-9/10");
        assertThat(servletResponse.getContentLength()).isEqualTo(3);
        assertThat(servletResponse.getContentAsByteArray()).containsExactly("789".getBytes());
    }

    @Test
    void serve_suffixRangeLargerThanFile_returnsWholeFileAs206() throws Exception {
        byte[] body = "0123456789".getBytes();
        when(storageService.open("attachments/x.txt")).thenReturn(handle(body));

        ResponseEntity<?> response = fileController.serve(
                mappedTo("/api/v1/files/attachments/x.txt", "bytes=-99"),
                new JwtPrincipal(userId));

        assertThat(response.getStatusCodeValue()).isEqualTo(206);
        MockHttpServletResponse servletResponse = drain(response);
        assertThat(servletResponse.getHeader("Content-Range")).isEqualTo("bytes 0-9/10");
        assertThat(servletResponse.getContentLength()).isEqualTo(10);
        assertThat(servletResponse.getContentAsByteArray()).containsExactly(body);
    }

    @Test
    void serve_endBeyondSize_isClamped() throws Exception {
        byte[] body = "0123456789".getBytes();
        when(storageService.open("attachments/x.txt")).thenReturn(handle(body));

        ResponseEntity<?> response = fileController.serve(
                mappedTo("/api/v1/files/attachments/x.txt", "bytes=8-999"),
                new JwtPrincipal(userId));

        assertThat(response.getStatusCodeValue()).isEqualTo(206);
        MockHttpServletResponse servletResponse = drain(response);
        assertThat(servletResponse.getHeader("Content-Range")).isEqualTo("bytes 8-9/10");
        assertThat(servletResponse.getContentLength()).isEqualTo(2);
        assertThat(servletResponse.getContentAsByteArray()).containsExactly("89".getBytes());
    }

    @Test
    void serve_multipleRanges_honoursOnlyTheFirst() throws Exception {
        byte[] body = "0123456789".getBytes();
        when(storageService.open("attachments/x.txt")).thenReturn(handle(body));

        ResponseEntity<?> response = fileController.serve(
                mappedTo("/api/v1/files/attachments/x.txt", "bytes=1-2,6-7"),
                new JwtPrincipal(userId));

        assertThat(response.getStatusCodeValue()).isEqualTo(206);
        MockHttpServletResponse servletResponse = drain(response);
        assertThat(servletResponse.getHeader("Content-Range")).isEqualTo("bytes 1-2/10");
        assertThat(servletResponse.getContentAsByteArray()).containsExactly("12".getBytes());
    }

    @Test
    void serve_unsatisfiableRanges_return416() throws Exception {
        byte[] body = "0123456789".getBytes();
        when(storageService.open("attachments/x.txt")).thenReturn(handle(body));

        for (String bad : new String[] {"bytes=10-", "bytes=99-100", "bytes=-0", "bytes=5-3"}) {
            ResponseEntity<?> response = fileController.serve(
                    mappedTo("/api/v1/files/attachments/x.txt", bad),
                    new JwtPrincipal(userId));

            assertThat(response.getStatusCodeValue())
                    .as("range %s", bad)
                    .isEqualTo(416);
            assertThat(response.getHeaders().getFirst("Content-Range")).isEqualTo("bytes */10");
            assertThat(response.getHeaders().getFirst("Accept-Ranges")).isEqualTo("bytes");
            assertThat(response.getBody()).isNull();
        }
    }

    @Test
    void serve_malformedRange_returns416() throws Exception {
        byte[] body = "0123456789".getBytes();
        when(storageService.open("attachments/x.txt")).thenReturn(handle(body));

        for (String bad : new String[] {"bytes=abc-def", "items=0-1", "bytes=-", "bytes=1"}) {
            ResponseEntity<?> response = fileController.serve(
                    mappedTo("/api/v1/files/attachments/x.txt", bad),
                    new JwtPrincipal(userId));

            assertThat(response.getStatusCodeValue())
                    .as("range %s", bad)
                    .isEqualTo(416);
            assertThat(response.getHeaders().getFirst("Content-Range")).isEqualTo("bytes */10");
        }
    }

    @Test
    void serve_rangeOnEmptyFile_returns416() throws Exception {
        when(storageService.open("attachments/empty.txt")).thenReturn(handle(new byte[0]));

        ResponseEntity<?> response = fileController.serve(
                mappedTo("/api/v1/files/attachments/empty.txt", "bytes=0-0"),
                new JwtPrincipal(userId));

        assertThat(response.getStatusCodeValue()).isEqualTo(416);
        assertThat(response.getHeaders().getFirst("Content-Range")).isEqualTo("bytes */0");
    }

    // ----------------------------------------------------------- streaming

    @Test
    void serve_largeFile_isReadIncrementallyNotWholeIntoHeap() throws Exception {
        int size = 30 * 1024 * 1024;
        byte[] body = new byte[size];
        java.util.Arrays.fill(body, (byte) 'A');
        int[] maxSingleRead = {0};
        InputStream source = new InputStream() {
            private int position = 0;

            @Override
            public int read() {
                return position < size ? (body[position++] & 0xFF) : -1;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                maxSingleRead[0] = Math.max(maxSingleRead[0], len);
                int chunk = Math.min(len, size - position);
                if (chunk <= 0) {
                    return -1;
                }
                System.arraycopy(body, position, b, off, chunk);
                position += chunk;
                return chunk;
            }
        };
        when(storageService.open("attachments/big.bin"))
                .thenReturn(new StorageService.StreamHandle(source, size));

        ResponseEntity<?> response = fileController.serve(
                mappedTo("/api/v1/files/attachments/big.bin", "bytes=0-"),
                new JwtPrincipal(userId));

        assertThat(response.getStatusCodeValue()).isEqualTo(206);
        MockHttpServletResponse servletResponse = drain(response);
        assertThat(servletResponse.getContentLength()).isEqualTo(size);
        assertThat(servletResponse.getContentAsByteArray()).hasSize(size).containsOnly((byte) 'A');
        // The converter never asks the source for more than its bounded buffer
        // (16 KiB), never for the whole 30 MB file.
        assertThat(maxSingleRead[0]).isLessThanOrEqualTo(16 * 1024);
    }

    // ------------------------------------- ISO-BMFF prefix + suffix sniffing

    @Test
    void serve_smallVoiceNoteWithMoovAtEnd_returnsAudioMp4() throws Exception {
        byte[] content = isoBmff("isom", 1000, "soun");
        when(storageService.open("attachments/voice.m4a")).thenReturn(handle(content));

        ResponseEntity<?> response = fileController.serve(
                mappedTo("/api/v1/files/attachments/voice.m4a"), new JwtPrincipal(userId));

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("audio/mp4");
        assertThat(drain(response).getContentAsByteArray()).containsExactly(content);
    }

    @Test
    void serve_largeVoiceNoteWithMoovAtEnd_returnsAudioMp4AndIntactBody() throws Exception {
        byte[] content = isoBmff("isom", 200_000, "soun");
        StorageService.StreamHandle prefixHandle =
                new StorageService.StreamHandle(new ByteArrayInputStream(content), content.length);
        StorageService.StreamHandle suffixHandle =
                new StorageService.StreamHandle(new ByteArrayInputStream(content), content.length);
        when(storageService.open("attachments/voice.m4a")).thenReturn(prefixHandle, suffixHandle);

        ResponseEntity<?> response = fileController.serve(
                mappedTo("/api/v1/files/attachments/voice.m4a"), new JwtPrincipal(userId));

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("audio/mp4");
        // The trailing read must not consume bytes from the serving stream.
        assertThat(drain(response).getContentAsByteArray()).containsExactly(content);
    }

    @Test
    void serve_h264VoiceNoteWithBothTracks_returnsVideoMp4() throws Exception {
        byte[] content = isoBmff("isom", 200_000, "vide", "soun");
        StorageService.StreamHandle prefixHandle =
                new StorageService.StreamHandle(new ByteArrayInputStream(content), content.length);
        StorageService.StreamHandle suffixHandle =
                new StorageService.StreamHandle(new ByteArrayInputStream(content), content.length);
        when(storageService.open("attachments/clip.mp4")).thenReturn(prefixHandle, suffixHandle);

        ResponseEntity<?> response = fileController.serve(
                mappedTo("/api/v1/files/attachments/clip.mp4"), new JwtPrincipal(userId));

        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("video/mp4");
        assertThat(drain(response).getContentAsByteArray()).containsExactly(content);
    }

    @Test
    void serve_rangeOnVoiceNote_stillSlicesFromTheFullBody() throws Exception {
        byte[] content = isoBmff("isom", 200_000, "soun");
        StorageService.StreamHandle prefixHandle =
                new StorageService.StreamHandle(new ByteArrayInputStream(content), content.length);
        StorageService.StreamHandle suffixHandle =
                new StorageService.StreamHandle(new ByteArrayInputStream(content), content.length);
        when(storageService.open("attachments/voice.m4a")).thenReturn(prefixHandle, suffixHandle);

        ResponseEntity<?> response = fileController.serve(
                mappedTo("/api/v1/files/attachments/voice.m4a", "bytes=10-19"),
                new JwtPrincipal(userId));

        assertThat(response.getStatusCodeValue()).isEqualTo(206);
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("audio/mp4");
        assertThat(drain(response).getContentAsByteArray())
                .containsExactly(java.util.Arrays.copyOfRange(content, 10, 20));
    }

    /** Builds an ISO-BMFF file with a generic brand, an mdat of {@code mdatBytes}, and tracks. */
    private static byte[] isoBmff(String brand, int mdatBytes, String... handlers) {
        byte[] ftypPayload = new byte[8];
        System.arraycopy(brand.getBytes(StandardCharsets.US_ASCII), 0, ftypPayload, 0, 4);
        byte[] ftyp = box("ftyp", ftypPayload);
        byte[] mdat = box("mdat", new byte[mdatBytes]);
        byte[] moovPayload = new byte[0];
        for (String handler : handlers) {
            byte[] hdlrPayload = new byte[16];
            System.arraycopy(handler.getBytes(StandardCharsets.US_ASCII), 0, hdlrPayload, 8, 4);
            moovPayload = concat(moovPayload, box("trak", box("mdia", box("hdlr", hdlrPayload))));
        }
        return concat(ftyp, mdat, box("moov", moovPayload));
    }

    private static byte[] box(String type, byte[] payload) {
        int size = 8 + payload.length;
        byte[] out = new byte[size];
        out[0] = (byte) (size >>> 24);
        out[1] = (byte) (size >>> 16);
        out[2] = (byte) (size >>> 8);
        out[3] = (byte) size;
        for (int i = 0; i < 4; i++) {
            out[4 + i] = (byte) type.charAt(i);
        }
        System.arraycopy(payload, 0, out, 8, payload.length);
        return out;
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) {
            length += part.length;
        }
        byte[] out = new byte[length];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, offset, part.length);
            offset += part.length;
        }
        return out;
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
