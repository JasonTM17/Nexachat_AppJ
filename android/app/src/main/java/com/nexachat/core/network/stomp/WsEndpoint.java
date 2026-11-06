package com.nexachat.core.network.stomp;

import okhttp3.HttpUrl;

/** Builds the STOMP WebSocket endpoint while retaining its HTTP(S) scheme. */
public final class WsEndpoint {

    private WsEndpoint() {
    }

    public static HttpUrl fromBaseUrl(String baseUrl) {
        return HttpUrl.get(baseUrl).newBuilder().addPathSegment("ws").build();
    }
}
