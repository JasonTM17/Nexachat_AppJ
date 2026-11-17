package com.nexachat.summary;

import java.io.IOException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Summary API boundary with typed errors and a process-local five-minute cache.
 *
 * <p>The cache key names the conversation, the scope, the LAST_N count, the device zone and,
 * for TODAY, the local date the result belongs to. A summary is therefore never replayed for a
 * different scope, day or zone, and losing membership or changing AI consent drops the cache.
 */
public final class SummaryRepository {
    static final long CACHE_TTL_MILLIS = 5 * 60 * 1000L;
    private static final int MAX_CACHE_ENTRIES = 100;

    private final SummaryApi api;
    private final LongSupplier clock;
    private final Map<CacheKey, CacheEntry> cache = new LinkedHashMap<>(16, 0.75f, true);

    public SummaryRepository(SummaryApi api) {
        this(api, System::currentTimeMillis);
    }

    SummaryRepository(SummaryApi api, LongSupplier clock) {
        this.api = Objects.requireNonNull(api, "api");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Cancellable request(
            String conversationId,
            SummaryRequest request,
            Callback callback) {
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(callback, "callback");

        CacheKey key = keyFor(conversationId, request);
        if (!request.isRefresh()) {
            SummaryResponse cached = findCached(key);
            if (cached != null) {
                callback.onSuccess(cached.asCached());
                return new RequestHandle(null);
            }
        }

        final Call<SummaryResponse> call;
        try {
            call = api.summarize(conversationId, request);
        } catch (RuntimeException failure) {
            callback.onError(SummaryErrorMapper.fromFailure(failure));
            return new RequestHandle(null);
        }

        RequestHandle handle = new RequestHandle(call);
        call.enqueue(new retrofit2.Callback<SummaryResponse>() {
            @Override
            public void onResponse(Call<SummaryResponse> ignored, Response<SummaryResponse> response) {
                if (handle.isCancelled()) {
                    return;
                }
                if (response.isSuccessful() && response.body() != null) {
                    putCached(key, response.body());
                    callback.onSuccess(response.body());
                    return;
                }
                SummaryError error = SummaryErrorMapper.fromHttp(
                        response.code(), readBody(response.errorBody()));
                if (error == SummaryError.NOT_MEMBER) {
                    // Access is gone: nothing cached for this conversation may be shown again.
                    clearCache();
                }
                callback.onError(error);
            }

            @Override
            public void onFailure(Call<SummaryResponse> ignored, Throwable failure) {
                if (!handle.isCancelled()) {
                    callback.onError(SummaryErrorMapper.fromFailure(failure));
                }
            }
        });
        return handle;
    }

    /** Returns a still-valid cached response only when its id matches saved UI state. */
    public Optional<SummaryResponse> findCachedById(
            String conversationId,
            SummaryRequest request,
            String summaryId) {
        if (conversationId == null || request == null || summaryId == null) {
            return Optional.empty();
        }
        SummaryResponse response = findCached(keyFor(conversationId, request));
        if (response == null || !summaryId.equals(response.getSummaryId())) {
            return Optional.empty();
        }
        return Optional.of(response);
    }

    /** Drops every cached summary; used when AI consent changes. */
    public void clearCache() {
        synchronized (cache) {
            cache.clear();
        }
    }

    private CacheKey keyFor(String conversationId, SummaryRequest request) {
        String zone = request.getTimeZone();
        String localDate = request.getScope() == SummaryScope.TODAY ? localDate(zone) : null;
        return new CacheKey(conversationId, request.getScope(), request.getLimit(), zone, localDate);
    }

    private String localDate(String zone) {
        long now = clock.getAsLong();
        try {
            ZoneId zoneId = zone == null || zone.trim().isEmpty()
                    ? ZoneId.systemDefault()
                    : ZoneId.of(zone);
            return Instant.ofEpochMilli(now).atZone(zoneId).toLocalDate().toString();
        } catch (DateTimeException invalidZone) {
            return Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().toString();
        }
    }

    private SummaryResponse findCached(CacheKey key) {
        synchronized (cache) {
            CacheEntry entry = cache.get(key);
            if (entry == null) {
                return null;
            }
            long age = clock.getAsLong() - entry.createdAtMillis;
            if (age < 0 || age >= CACHE_TTL_MILLIS) {
                cache.remove(key);
                return null;
            }
            return entry.response;
        }
    }

    private void putCached(CacheKey key, SummaryResponse response) {
        synchronized (cache) {
            cache.put(key, new CacheEntry(response, clock.getAsLong()));
            while (cache.size() > MAX_CACHE_ENTRIES) {
                Iterator<CacheKey> keys = cache.keySet().iterator();
                keys.next();
                keys.remove();
            }
        }
    }

    private static String readBody(ResponseBody body) {
        if (body == null) {
            return null;
        }
        try {
            return body.string();
        } catch (IOException ignored) {
            return null;
        }
    }

    public interface Callback {
        void onSuccess(SummaryResponse response);

        void onError(SummaryError error);
    }

    public interface Cancellable {
        void cancel();
    }

    private static final class RequestHandle implements Cancellable {
        private final Call<?> call;
        private final AtomicBoolean cancelled = new AtomicBoolean();

        private RequestHandle(Call<?> call) {
            this.call = call;
        }

        @Override
        public void cancel() {
            if (cancelled.compareAndSet(false, true) && call != null) {
                call.cancel();
            }
        }

        private boolean isCancelled() {
            return cancelled.get();
        }
    }

    private static final class CacheKey {
        private final String conversationId;
        private final SummaryScope scope;
        private final Integer limit;
        private final String timeZone;
        private final String localDate;

        private CacheKey(
                String conversationId,
                SummaryScope scope,
                Integer limit,
                String timeZone,
                String localDate) {
            this.conversationId = conversationId;
            this.scope = scope;
            this.limit = limit;
            this.timeZone = timeZone;
            this.localDate = localDate;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof CacheKey)) {
                return false;
            }
            CacheKey that = (CacheKey) other;
            return conversationId.equals(that.conversationId)
                    && scope == that.scope
                    && Objects.equals(limit, that.limit)
                    && Objects.equals(timeZone, that.timeZone)
                    && Objects.equals(localDate, that.localDate);
        }

        @Override
        public int hashCode() {
            return Objects.hash(conversationId, scope, limit, timeZone, localDate);
        }
    }

    private static final class CacheEntry {
        private final SummaryResponse response;
        private final long createdAtMillis;

        private CacheEntry(SummaryResponse response, long createdAtMillis) {
            this.response = response;
            this.createdAtMillis = createdAtMillis;
        }
    }
}
