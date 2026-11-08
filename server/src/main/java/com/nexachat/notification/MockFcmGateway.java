package com.nexachat.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Mock FCM Gateway enabled by default in local dev, offline environments, and CI/CD tests.
 * Maintains zero leaked secrets and requires zero network access.
 */
@Component
@ConditionalOnProperty(name = "nexachat.fcm.enabled", havingValue = "false", matchIfMissing = true)
public class MockFcmGateway implements FcmGateway {

    private static final Logger log = LoggerFactory.getLogger(MockFcmGateway.class);

    public record SentPush(String token, Map<String, String> data, String priority, long ttlSeconds) {
    }

    private final ConcurrentLinkedQueue<SentPush> sentPushes = new ConcurrentLinkedQueue<>();
    private volatile boolean simulateUnregistered = false;

    @Override
    public CompletableFuture<PushResult> sendDataPush(String targetToken, Map<String, String> data,
                                                      String priority, long ttlSeconds) {
        SentPush record = new SentPush(targetToken, Map.copyOf(data), priority, ttlSeconds);
        sentPushes.add(record);
        log.info("MockFcmGateway: Recorded data push to token {} with type {}",
                targetToken, data.get("type"));

        if (simulateUnregistered) {
            return CompletableFuture.completedFuture(PushResult.unregistered("UNREGISTERED"));
        }
        return CompletableFuture.completedFuture(PushResult.success("mock-msg-" + UUID.randomUUID()));
    }

    public List<SentPush> getSentPushes() {
        return new ArrayList<>(sentPushes);
    }

    public void clear() {
        sentPushes.clear();
        simulateUnregistered = false;
    }

    public void setSimulateUnregistered(boolean simulateUnregistered) {
        this.simulateUnregistered = simulateUnregistered;
    }
}
