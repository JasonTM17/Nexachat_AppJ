package com.nexachat.call.core;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public final class CallQueueSubscriptionsTest {
    @Test
    public void subscriptionsAreRecreatedOnReconnectAndRemovedOnStop() {
        FakeTransport transport = new FakeTransport();
        CallQueueSubscriptions subscriptions = new CallQueueSubscriptions(transport);

        subscriptions.start(false);
        assertEquals(0, transport.subscribed.size());
        subscriptions.onOpen();
        subscriptions.onOpen();
        assertEquals(1, transport.subscribed.size());

        subscriptions.onClosed();
        subscriptions.onOpen();
        assertEquals(2, transport.subscribed.size());
        subscriptions.stop();
        assertEquals(1, transport.unsubscribed.size());
        subscriptions.onOpen();
        assertEquals(2, transport.subscribed.size());
    }

    private static final class FakeTransport implements CallQueueSubscriptions.Transport {
        final List<String> subscribed = new ArrayList<>();
        final List<String> unsubscribed = new ArrayList<>();

        @Override
        public void subscribe(String destination) {
            subscribed.add(destination);
        }

        @Override
        public void unsubscribe(String destination) {
            unsubscribed.add(destination);
        }
    }
}
