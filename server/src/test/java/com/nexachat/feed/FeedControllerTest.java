package com.nexachat.feed;

import com.nexachat.attachment.RangeNotSatisfiableException;
import com.nexachat.attachment.StorageService;
import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.social.SocialSettingsService;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.ResourceHttpMessageConverter;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeedControllerTest {

    private final FeedService feedService = mock(FeedService.class);
    private final FeedImageService imageService = mock(FeedImageService.class);
    private final SocialSettingsService socialSettings = mock(SocialSettingsService.class);
    private final FeedController controller = new FeedController(feedService, imageService, socialSettings);
    private final UUID viewerId = UUID.randomUUID();

    private static MockHttpServletResponse drain(ResponseEntity<Resource> response) throws IOException {
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();
        servletResponse.setStatus(response.getStatusCode().value());
        response.getHeaders().forEach((name, values) ->
                values.forEach(value -> servletResponse.addHeader(name, value)));
        new ResourceHttpMessageConverter().write(
                response.getBody(),
                response.getHeaders().getContentType(),
                new ServletServerHttpResponse(servletResponse));
        return servletResponse;
    }

    @Test
    void videoWithoutRangeReturns200WithAcceptRangesAndLength() throws Exception {
        UUID videoId = UUID.randomUUID();
        byte[] bytes = "0123456789".getBytes(StandardCharsets.US_ASCII);
        FeedStore.ImageRow row = new FeedStore.ImageRow(
                videoId, viewerId, "feed/v.mp4", "video/mp4", bytes.length, null, null, null);
        when(imageService.openVideo(viewerId, videoId)).thenReturn(new FeedImageService.ImageContent(
                row, new StorageService.StreamHandle(new ByteArrayInputStream(bytes), bytes.length)));

        ResponseEntity<Resource> response = controller.video(new JwtPrincipal(viewerId), videoId, null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        MockHttpServletResponse drained = drain(response);
        assertThat(drained.getHeader("Accept-Ranges")).isEqualTo("bytes");
        assertThat(drained.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(drained.getHeader("Cache-Control")).contains("no-store");
        assertThat(drained.getContentLength()).isEqualTo(bytes.length);
        assertThat(drained.getContentAsByteArray()).containsExactly(bytes);
        verify(imageService, never()).openVideoRange(any(), any(), anyString());
    }

    @Test
    void videoWithRangeReturns206WithoutOpeningFullStream() throws Exception {
        UUID videoId = UUID.randomUUID();
        byte[] slice = "2345".getBytes(StandardCharsets.US_ASCII);
        FeedStore.ImageRow row = new FeedStore.ImageRow(
                videoId, viewerId, "feed/v.mp4", "video/mp4", 10L, null, null, null);
        when(imageService.openVideoRange(viewerId, videoId, "bytes=2-5"))
                .thenReturn(new FeedImageService.VideoRangeContent(
                        row,
                        new StorageService.StreamHandle(new ByteArrayInputStream(slice), 10L),
                        2L, 5L, 4L, 10L));

        ResponseEntity<Resource> response = controller.video(
                new JwtPrincipal(viewerId), videoId, "bytes=2-5");

        assertThat(response.getStatusCode().value()).isEqualTo(206);
        MockHttpServletResponse drained = drain(response);
        assertThat(drained.getHeader("Accept-Ranges")).isEqualTo("bytes");
        assertThat(drained.getHeader("Content-Range")).isEqualTo("bytes 2-5/10");
        assertThat(drained.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(drained.getHeader("Cache-Control")).contains("no-store");
        assertThat(drained.getContentLength()).isEqualTo(4);
        assertThat(drained.getContentAsByteArray()).containsExactly(slice);
        verify(imageService, never()).openVideo(any(), any());
    }

    @Test
    void videoWithInvalidRangePropagates416() {
        UUID videoId = UUID.randomUUID();
        when(imageService.openVideoRange(viewerId, videoId, "bytes=99-100"))
                .thenThrow(new RangeNotSatisfiableException(10L, "Requested range not satisfiable"));

        RangeNotSatisfiableException ex = catchThrowableOfType(RangeNotSatisfiableException.class,
                () -> controller.video(new JwtPrincipal(viewerId), videoId, "bytes=99-100"));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.RANGE_NOT_SATISFIABLE);
        assertThat(ex.getSizeBytes()).isEqualTo(10L);
        verify(imageService, never()).openVideo(any(), any());
    }

    @Test
    void uploadImageAndVideoRejectDuplicateOrMissingFileParts() {
        List<MultipartFile> two = List.of(
                new MockMultipartFile("file", "a.png", "image/png", new byte[] {1}),
                new MockMultipartFile("file", "b.png", "image/png", new byte[] {2}));

        ApiException imgEx = catchThrowableOfType(ApiException.class,
                () -> controller.uploadImage(new JwtPrincipal(viewerId), two, null, null));
        assertThat(imgEx.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
        verify(imageService, never()).upload(any(), any(), any(), any());

        when(socialSettings.isVideoUploadsEnabled()).thenReturn(true);
        ApiException vidEx = catchThrowableOfType(ApiException.class,
                () -> controller.uploadVideo(new JwtPrincipal(viewerId), two));
        assertThat(vidEx.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
        verify(imageService, never()).uploadVideo(any(), any());

        when(socialSettings.isVideoUploadsEnabled()).thenReturn(false);
        ApiException disabledEx = catchThrowableOfType(ApiException.class,
                () -> controller.uploadVideo(new JwtPrincipal(viewerId), two));
        assertThat(disabledEx.getCode()).isEqualTo(ApiErrorCode.SOCIAL_FEATURE_DISABLED);
    }
}
