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
    public void bothInternalCachesShareOneTwoGiBCeiling() throws Exception {
        File root = Files.createTempDirectory("nexa-media-cache").toFile();
        File glide = MediaCacheConfig.glideDirectory(root);
        File okHttp = MediaCacheConfig.okHttpDirectory(root);

        assertEquals(2L * 1024L * 1024L * 1024L,
                MediaCacheConfig.MAX_TOTAL_DISK_CACHE_BYTES);
        assertEquals(MediaCacheConfig.MAX_TOTAL_DISK_CACHE_BYTES,
                MediaCacheConfig.GLIDE_DISK_CACHE_BYTES
                        + MediaCacheConfig.OKHTTP_DISK_CACHE_BYTES);
        assertTrue(glide.getCanonicalFile().toPath().startsWith(root.getCanonicalFile().toPath()));
        assertTrue(okHttp.getCanonicalFile().toPath().startsWith(root.getCanonicalFile().toPath()));
        assertFalse(glide.getCanonicalPath().contains("external"));
        assertFalse(okHttp.getCanonicalPath().contains("external"));
    }

    @Test
    public void onlyAttachmentContentGetsAreCacheCandidates() {
        Request media = request("GET", "https://chat.test/api/v1/attachments/a-1/content");
        Request wrongMethod = request("POST", "https://chat.test/api/v1/attachments/a-1/content");
        Request json = request("GET", "https://chat.test/api/v1/messages");
        Request nestedPath = request("GET", "https://chat.test/api/v1/attachments/a-1/content/extra");

        assertTrue(MediaCacheConfig.isAttachmentContentGet(media));
        assertFalse(MediaCacheConfig.isAttachmentContentGet(wrongMethod));
        assertFalse(MediaCacheConfig.isAttachmentContentGet(json));
        assertFalse(MediaCacheConfig.isAttachmentContentGet(nestedPath));
    }

    @Test
    public void cacheableMediaResponseIsScopedToAuthorization() throws Exception {
        Request request = request("GET", "https://chat.test/api/v1/attachments/a-1/content");
        Response response = response(request, 200, "image/jpeg", "private, max-age=60");
        Interceptor.Chain chain = mock(Interceptor.Chain.class);
        when(chain.request()).thenReturn(request);
        when(chain.proceed(any(Request.class))).thenReturn(response);

        Response result = new MediaCacheResponseInterceptor().intercept(chain);

        assertEquals("Authorization", result.header("Vary"));
        assertEquals("private, max-age=60", result.header("Cache-Control"));
    }

    @Test
    public void apiJsonAndAttachmentErrorBodiesAreNoStore() throws Exception {
        Request jsonRequest = request("GET", "https://chat.test/api/v1/messages");
        Response jsonResponse = response(jsonRequest, 200, "application/json", "private, max-age=60");
        Interceptor.Chain jsonChain = mock(Interceptor.Chain.class);
        when(jsonChain.request()).thenReturn(jsonRequest);
        when(jsonChain.proceed(any(Request.class))).thenReturn(jsonResponse);

        Response jsonResult = new MediaCacheResponseInterceptor().intercept(jsonChain);

        assertEquals("no-store", jsonResult.header("Cache-Control"));

        Request attachmentRequest = request(
                "GET", "https://chat.test/api/v1/attachments/a-1/content");
        Response errorResponse = response(
                attachmentRequest, 403, "application/json", "private, max-age=60");
        Interceptor.Chain errorChain = mock(Interceptor.Chain.class);
        when(errorChain.request()).thenReturn(attachmentRequest);
        when(errorChain.proceed(any(Request.class))).thenReturn(errorResponse);

        Response errorResult = new MediaCacheResponseInterceptor().intercept(errorChain);

        assertEquals("no-store", errorResult.header("Cache-Control"));
    }

    @Test
    public void requestPolicyBypassesCacheForNonMediaUrls() throws Exception {
        Request request = request("GET", "https://chat.test/api/v1/messages");
        Interceptor.Chain chain = mock(Interceptor.Chain.class);
        when(chain.request()).thenReturn(request);
        when(chain.proceed(any(Request.class))).thenReturn(response(
                request, 200, "application/json", null));

        new MediaCacheRequestInterceptor().intercept(chain);

        org.mockito.ArgumentCaptor<Request> captured =
                org.mockito.ArgumentCaptor.forClass(Request.class);
        org.mockito.Mockito.verify(chain).proceed(captured.capture());
        assertEquals("no-store", captured.getValue().header("Cache-Control"));
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
