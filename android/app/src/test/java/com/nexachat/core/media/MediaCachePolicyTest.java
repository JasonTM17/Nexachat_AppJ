package com.nexachat.core.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.nio.file.Files;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Test;

public final class MediaCachePolicyTest {

    @Test
    public void mediaDirectoriesLiveUnderCacheDirAndNeverExternal() throws Exception {
        File root = Files.createTempDirectory("nexa-media-cache").toFile();
        File glide = MediaCacheConfig.glideDirectory(root);
        File okHttp = MediaCacheConfig.okHttpDirectory(root);
        File attachments = MediaCacheConfig.attachmentDirectory(root);
        File transientDir = MediaCacheConfig.transientAttachmentDirectory(root);

        assertEquals(2L * 1024L * 1024L * 1024L,
                MediaCacheConfig.MAX_TOTAL_DISK_CACHE_BYTES);
        assertEquals(MediaCacheConfig.MAX_TOTAL_DISK_CACHE_BYTES / 2L,
                MediaCacheConfig.GLIDE_DISK_CACHE_BYTES);
        for (File dir : new File[] {glide, okHttp, attachments, transientDir}) {
            assertTrue(dir.getCanonicalFile().toPath()
                    .startsWith(root.getCanonicalFile().toPath()));
            assertFalse(dir.getCanonicalPath().contains("external"));
        }
    }

    @Test
    public void requestPolicyNoStoresEveryRequestIncludingAttachmentContent() throws Exception {
        // Audit F-02: no response body — authenticated attachment content included —
        // may ever be written to a disk cache.
        Request attachmentGet = request("GET", "https://chat.test/api/v1/attachments/a-1/content");
        Request jsonGet = request("GET", "https://chat.test/api/v1/messages");

        for (Request request : new Request[] {attachmentGet, jsonGet}) {
            Interceptor.Chain chain = mock(Interceptor.Chain.class);
            when(chain.request()).thenReturn(request);
            when(chain.proceed(any(Request.class))).thenReturn(response(
                    request, 200, "image/jpeg", "private, max-age=60"));

            new MediaCacheRequestInterceptor().intercept(chain);

            org.mockito.ArgumentCaptor<Request> captured =
                    org.mockito.ArgumentCaptor.forClass(Request.class);
            org.mockito.Mockito.verify(chain).proceed(captured.capture());
            assertEquals("no-store", captured.getValue().header("Cache-Control"));
        }
    }

    private static Request request(String method, String url) {
        Request.Builder builder = new Request.Builder().url(url);
        return "GET".equals(method)
                ? builder.get().build()
                : builder.post(RequestBody.create(new byte[0], MediaType.parse("text/plain"))).build();
    }

    private static Response response(
            Request request, int status, String contentType, String cacheControl) {
        Response.Builder builder = new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message(status >= 400 ? "Error" : "OK")
                .header("Content-Type", contentType)
                .body(ResponseBody.create(MediaType.parse(contentType), new byte[0]));
        if (cacheControl != null) {
            builder.header("Cache-Control", cacheControl);
        }
        return builder.build();
    }
}
