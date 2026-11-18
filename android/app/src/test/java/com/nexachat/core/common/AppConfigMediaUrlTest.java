package com.nexachat.core.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import okhttp3.HttpUrl;
import org.junit.Test;

public final class AppConfigMediaUrlTest {

    private static final HttpUrl API = HttpUrl.get(AppConfig.API_BASE_URL);

    @Test
    public void absoluteUrlOnTheApiOriginPassesThroughUnchanged() {
        String url = API.newBuilder().addPathSegment("api").addPathSegment("v1")
                .addPathSegment("files").addPathSegment("a.png").build().toString();
        assertEquals(url, AppConfig.absoluteMediaUrl(url));
    }

    @Test
    public void absoluteUrlOnAnotherHostIsRejected() {
        String evil = API.newBuilder().host("evil.example.test")
                .addPathSegment("pixel.png").build().toString();
        assertNull(AppConfig.absoluteMediaUrl(evil));
        // Suffix-host trick must not pass either.
        String suffix = API.newBuilder().host(API.host() + ".evil.test")
                .addPathSegment("pixel.png").build().toString();
        assertNull(AppConfig.absoluteMediaUrl(suffix));
    }

    @Test
    public void absoluteUrlWithDifferentSchemeOrPortIsRejected() {
        String otherScheme = API.newBuilder().scheme(API.scheme().equals("https") ? "http" : "https")
                .addPathSegment("a.png").build().toString();
        assertNull(AppConfig.absoluteMediaUrl(otherScheme));
        String otherPort = API.newBuilder().port(API.port() + 1)
                .addPathSegment("a.png").build().toString();
        assertNull(AppConfig.absoluteMediaUrl(otherPort));
    }

    @Test
    public void absoluteUrlWithUserInfoIsRejected() {
        String withUser = API.newBuilder().username("user").password("pass")
                .addPathSegment("a.png").build().toString();
        assertNull(AppConfig.absoluteMediaUrl(withUser));
    }

    @Test
    public void nonHttpSchemesAreRejected() {
        assertNull(AppConfig.absoluteMediaUrl("file:///data/data/com.nexachat/databases/nexachat.db"));
        assertNull(AppConfig.absoluteMediaUrl("content://media/external/images/1"));
        assertNull(AppConfig.absoluteMediaUrl("data:image/png;base64,AAAA"));
        assertNull(AppConfig.absoluteMediaUrl("javascript:alert(1)"));
    }

    @Test
    public void nullAndEmptyAreRejected() {
        assertNull(AppConfig.absoluteMediaUrl(null));
        assertNull(AppConfig.absoluteMediaUrl(""));
    }

    @Test
    public void rootRelativePathIsJoinedToTheApiBase() {
        assertEquals(AppConfig.API_BASE_URL + "/api/v1/files/a", AppConfig.absoluteMediaUrl("/api/v1/files/a"));
    }

    @Test
    public void bareRelativePathGainsASingleSlash() {
        assertEquals(AppConfig.API_BASE_URL + "/files/a", AppConfig.absoluteMediaUrl("files/a"));
    }
}
