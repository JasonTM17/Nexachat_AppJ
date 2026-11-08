package com.nexachat.core.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public final class BlurHashPlaceholderCacheRegressionTest {

    @Test
    public void validHashCreatesPlaceholderOnlyOnce() {
        BlurHashPlaceholderCache<Object> cache = new BlurHashPlaceholderCache<>();
        Object placeholder = new Object();
        AtomicInteger creations = new AtomicInteger();

        Object first = cache.getOrCreate("LEHV6nWB2yk8pyo0adR*.7kCMdnj", hash -> {
            creations.incrementAndGet();
            return placeholder;
        });
        Object second = cache.getOrCreate("LEHV6nWB2yk8pyo0adR*.7kCMdnj", hash -> {
            creations.incrementAndGet();
            return new Object();
        });

        assertSame(placeholder, first);
        assertSame(first, second);
        assertEquals(1, creations.get());
    }

    @Test
    public void invalidHashDoesNotInvokeFactory() {
        BlurHashPlaceholderCache<Object> cache = new BlurHashPlaceholderCache<>();
        AtomicInteger creations = new AtomicInteger();

        assertNull(cache.getOrCreate("not a blurhash", hash -> {
            creations.incrementAndGet();
            return new Object();
        }));

        assertEquals(0, creations.get());
        assertEquals(0, cache.size());
    }

    @Test
    public void nullFactoryResultIsRetriedInsteadOfCached() {
        BlurHashPlaceholderCache<Object> cache = new BlurHashPlaceholderCache<>();
        AtomicInteger creations = new AtomicInteger();
        Object expected = new Object();

        assertNull(cache.getOrCreate("000000", hash -> {
            creations.incrementAndGet();
            return null;
        }));
        assertSame(expected, cache.getOrCreate("000000", hash -> {
            creations.incrementAndGet();
            return expected;
        }));

        assertEquals(2, creations.get());
        assertEquals(1, cache.size());
    }

    @Test
    public void illegalArgumentFromFactoryReturnsNoPlaceholderAndCanRetry() {
        BlurHashPlaceholderCache<Object> cache = new BlurHashPlaceholderCache<>();

        assertNull(cache.getOrCreate("000000", hash -> {
            throw new IllegalArgumentException("unsupported dimensions");
        }));
        Object placeholder = new Object();
        assertSame(placeholder, cache.getOrCreate("000000", hash -> placeholder));
    }

    @Test
    public void accessOrderDeterminesWhichPlaceholderIsEvicted() {
        BlurHashPlaceholderCache<Object> cache = new BlurHashPlaceholderCache<>();
        List<Object> placeholders = new ArrayList<>();
        for (int i = 0; i < BlurHashPlaceholderCache.MAX_ENTRIES; i++) {
            Object placeholder = new Object();
            placeholders.add(placeholder);
            assertSame(placeholder, cache.getOrCreate(hash(i), ignored -> placeholder));
        }

        assertSame(placeholders.get(0), cache.getOrCreate(hash(0), ignored -> new Object()));
        Object newest = new Object();
        cache.getOrCreate(hash(BlurHashPlaceholderCache.MAX_ENTRIES), ignored -> newest);

        assertEquals(BlurHashPlaceholderCache.MAX_ENTRIES, cache.size());
        AtomicInteger recreated = new AtomicInteger();
        Object replacement = cache.getOrCreate(hash(1), ignored -> {
            recreated.incrementAndGet();
            return new Object();
        });
        assertEquals(1, recreated.get());
        assertFalse(placeholders.contains(replacement));
        assertSame(newest, cache.getOrCreate(
                hash(BlurHashPlaceholderCache.MAX_ENTRIES), ignored -> new Object()));
    }

    @Test
    public void concurrentRequestsForOneHashCreateOnePlaceholder() throws InterruptedException {
        BlurHashPlaceholderCache<Object> cache = new BlurHashPlaceholderCache<>();
        int workers = 8;
        Object expected = new Object();
        AtomicInteger creations = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Object> results = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(workers);
        ExecutorService executor = Executors.newFixedThreadPool(workers);

        for (int i = 0; i < workers; i++) {
            executor.execute(() -> {
                try {
                    start.await();
                    results.add(cache.getOrCreate("000000", hash -> {
                        creations.incrementAndGet();
                        return expected;
                    }));
                } catch (Throwable throwable) {
                    failure.compareAndSet(null, throwable);
                } finally {
                    done.countDown();
                }
            });
        }

        try {
            start.countDown();
            assertTrue("placeholder workers must finish", done.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());
        } finally {
            executor.shutdownNow();
        }

        assertEquals(workers, results.size());
        assertEquals(1, creations.get());
        for (Object result : results) {
            assertSame(expected, result);
        }
    }

    private static String hash(int value) {
        return String.format(Locale.ROOT, "%06d", value);
    }
}
