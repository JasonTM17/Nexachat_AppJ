package com.nexachat.notification;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

public interface FcmGateway {

    CompletableFuture<PushResult> sendDataPush(
            String targetToken,
            Map<String, String> data,
            String priority,
            long ttlSeconds
    );
}
