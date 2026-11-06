package com.nexachat.core.common;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class AppConfigMediaUrlTest {

    @Test
    public void absoluteHttpAndHttpsUrlsStayUnchanged() {
        assertEquals("https://cdn.example/a.png", AppConfig.absoluteMediaUrl("https://cdn.example/a.png"));
        assertEquals("http://cdn.example/a.png", AppConfig.absoluteMediaUrl("http://cdn.example/a.png"));
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
