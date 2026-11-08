package com.nexachat.core.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public final class BoundedLruCacheTest {

    @Test
    public void constructorRejectsNonPositiveCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new BoundedLruCache<>(0));
        assertThrows(IllegalArgumentException.class, () -> new BoundedLruCache<>(-1));
    }

    @Test
    public void accessPromotesEntryBeforeLeastRecentlyUsedEviction() {
        BoundedLruCache<String, String> cache = new BoundedLruCache<>(2);
        cache.put("first", "1");
        cache.put("second", "2");

        assertEquals("1", cache.get("first"));
        cache.put("third", "3");

        assertEquals("1", cache.get("first"));
        assertNull(cache.get("second"));
        assertEquals("3", cache.get("third"));
    }

    @Test
    public void replacingEntryDoesNotGrowCacheAndPromotesItsKey() {
        BoundedLruCache<String, String> cache = new BoundedLruCache<>(2);
        cache.put("first", "old");
        cache.put("second", "2");

        cache.put("first", "new");
        cache.put("third", "3");

        assertEquals(2, cache.size());
        assertEquals("new", cache.get("first"));
        assertNull(cache.get("second"));
    }

    @Test
    public void singleEntryCapacityRetainsOnlyMostRecentValue() {
        BoundedLruCache<String, Integer> cache = new BoundedLruCache<>(1);
        cache.put("first", 1);
        cache.put("second", 2);

        assertEquals(1, cache.maximumEntries());
        assertEquals(1, cache.size());
        assertNull(cache.get("first"));
        assertEquals(Integer.valueOf(2), cache.get("second"));
    }

    @Test
    public void concurrentAccessKeepsCacheWithinConfiguredBound() throws InterruptedException {
        int workers = 8;
        BoundedLruCache<String, Integer> cache = new BoundedLruCache<>(8);
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(workers);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        for (int worker = 0; worker < workers; worker++) {
            int workerId = worker;
            executor.execute(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 500; i++) {
                        String key = "worker-" + workerId + "-" + i;
                        cache.put(key, i);
                        cache.get(key);
                    }
                } catch (Throwable throwable) {
                    failure.compareAndSet(null, throwable);
                } finally {
                    done.countDown();
                }
            });
        }

        try {
            start.countDown();
            assertTrue("cache workers must finish", done.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertTrue(cache.size() <= cache.maximumEntries());
        } finally {
            executor.shutdownNow();
        }
    }
}
