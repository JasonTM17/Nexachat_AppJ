package com.nexachat.call;

import java.util.List;

public record CallIceConfig(List<IceServer> iceServers, int ttlSeconds) {

    public record IceServer(List<String> urls) {
    }
}
