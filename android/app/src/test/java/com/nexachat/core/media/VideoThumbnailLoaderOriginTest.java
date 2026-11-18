package com.nexachat.core.media;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import okhttp3.HttpUrl;
import org.junit.Test;

/** The thumbnail loader sends the bearer token, so it may only talk to the API origin. */
public final class VideoThumbnailLoaderOriginTest {

    private static final HttpUrl API = HttpUrl.get("https://api.example.test");

    @Test
    public void sameSchemeHostAndPortIsTrusted() {
        assertTrue(VideoThumbnailLoader.isSameOrigin(
                HttpUrl.get("https://api.example.test/api/v1/files/attachments/a.mp4"), API));
    }

    @Test
    public void otherHostSchemeOrPortIsRejected() {
        assertFalse(VideoThumbnailLoader.isSameOrigin(HttpUrl.get("https://evil.example.test/a.mp4"), API));
        assertFalse(VideoThumbnailLoader.isSameOrigin(HttpUrl.get("http://api.example.test/a.mp4"), API));
        assertFalse(VideoThumbnailLoader.isSameOrigin(HttpUrl.get("https://api.example.test:8443/a.mp4"), API));
        assertFalse(VideoThumbnailLoader.isSameOrigin(HttpUrl.get("https://api.example.test.evil.test/a.mp4"), API));
    }

    @Test
    public void unparsableUrlIsRejected() {
        assertFalse(VideoThumbnailLoader.isSameOrigin(null, API));
        assertFalse(VideoThumbnailLoader.isSameOrigin(HttpUrl.get("https://api.example.test/a.mp4"), null));
    }

    @Test
    public void apiOriginUrlsAreAccepted() {
        assertTrue(VideoThumbnailLoader.isApiOrigin(
                com.nexachat.core.common.AppConfig.API_BASE_URL + "/api/v1/attachments/a.mp4/content"));
    }

    @Test
    public void fileAndContentSchemesFromServerDataAreRejected() {
        // Audit F-05: server-supplied contentUrl must never reach
        // MediaMetadataRetriever as a local scheme.
        assertFalse(VideoThumbnailLoader.isApiOrigin(
                "file:///data/data/com.nexachat/databases/nexachat.db"));
        assertFalse(VideoThumbnailLoader.isApiOrigin("content://media/external/video/1"));
        assertFalse(VideoThumbnailLoader.isApiOrigin("content://com.other.app.provider/secret"));
        assertFalse(VideoThumbnailLoader.isApiOrigin("data:video/mp4;base64,AAAA"));
        assertFalse(VideoThumbnailLoader.isApiOrigin("https://evil.example.test/a.mp4"));
    }
}
