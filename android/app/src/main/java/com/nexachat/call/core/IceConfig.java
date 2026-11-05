package com.nexachat.call.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** ICE server settings supplied by the authenticated API. */
public final class IceConfig {
    public static final class IceServer {
        private final List<String> urls;
        private final String username;
        private final String credential;

        public IceServer(List<String> urls, String username, String credential) {
            this.urls = Collections.unmodifiableList(
                    urls == null ? new ArrayList<>() : new ArrayList<>(urls));
            this.username = username;
            this.credential = credential;
        }

        public List<String> getUrls() { return urls; }
        public String getUsername() { return username; }
        public String getCredential() { return credential; }
    }

    private final List<IceServer> iceServers;
    private final int ttlSeconds;

    public IceConfig(List<IceServer> iceServers, int ttlSeconds) {
        this.iceServers = Collections.unmodifiableList(
                iceServers == null ? new ArrayList<>() : new ArrayList<>(iceServers));
        this.ttlSeconds = Math.max(0, ttlSeconds);
    }

    public List<IceServer> getIceServers() { return iceServers; }
    public int getTtlSeconds() { return ttlSeconds; }
}
