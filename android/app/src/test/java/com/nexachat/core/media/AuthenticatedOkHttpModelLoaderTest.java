package com.nexachat.core.media;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import org.junit.Test;

public final class AuthenticatedOkHttpModelLoaderTest {

    @Test
    public void authenticatedLoaderOnlyHandlesTheConfiguredApiOrigin() {
        AuthenticatedOkHttpModelLoader loader = new AuthenticatedOkHttpModelLoader(
                mock(OkHttpClient.class), HttpUrl.get("https://chat.test:8443"));

        assertTrue(loader.handles("https://chat.test:8443/api/v1/attachments/a/content"));
        assertFalse(loader.handles("https://cdn.test:8443/avatar.png"));
        assertFalse(loader.handles("https://chat.test/avatar.png"));
        assertFalse(loader.handles("http://chat.test:8443/api/v1/attachments/a/content"));
        assertFalse(loader.handles("file:///private/avatar.png"));
        assertFalse(loader.handles("not a URL"));
    }
}
