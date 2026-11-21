package com.nexachat.summary;

import com.nexachat.core.network.SessionEvents;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import java.io.IOException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Summary API boundary with typed errors and a process-local five-minute cache.
 *
 * <p>The cache key names the conversation, the scope, the LAST_N count, the language, the device zone and,
 * for TODAY, the local date the result belongs to. A summary is therefore never replayed for a
 * different scope, day or zone. Membership/consent changes and session boundaries invalidate
 * cached and pending work. The repository monitor also orders cache clears with UI publication.
 */
public final class SummaryRepository {
    static final long CACHE_TTL_MILLIS = 5 * 60 * 1000L;
    private static final int MAX_CACHE_ENTRIES = 100;

    private final SummaryApi api;
    private final LongSupplier clock;
    private final Map<CacheKey, CacheEntry> cache = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<CacheKey, RequestHandle> latestFills = new HashMap<>();
    private final CopyOnWriteArrayList<Consumer<SummaryError>> invalidationListeners =
            new CopyOnWriteArrayList<>();
    // The repository is a process singleton, matching the lifetime of these subscriptions.
    private final CompositeDisposable sessionSubscriptions = new CompositeDisposable();
    private long cacheEpoch;
    private boolean sessionOpen = true;

    public SummaryRepository(SummaryApi api) {
        this(api, System::currentTimeMillis);
    }

    public SummaryRepository(SummaryApi api, SessionEvents sessionEvents) {
        this(api);
        Objects.requireNonNull(sessionEvents, "sessionEvents");
        sessionSubscriptions.add(sessionEvents.getEvents().subscribe(this::onSessionEvent));
        sessionSubscriptions.add(sessionEvents.getLifecycleEvents().subscribe(this::onSessionEvent));
    }

    private void onSessionEvent(SessionEvents.Event event) {
        if (event instanceof SessionEvents.Event.SessionClosing
                || event instanceof SessionEvents.Event.LoggedOut
                || event instanceof SessionEvents.Event.ReauthenticationRequired) {
            synchronized (this) {
                sessionOpen = false;
                clearCache();
            }
        } else if (event instanceof SessionEvents.Event.SessionAvailable) {
            synchronized (this) {
                sessionOpen = true;
                // Also handles replacing an account without an intervening logout event.
                clearCache();
            }
        }
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
        final RequestHandle handle;
        synchronized (this) {
            if (!sessionOpen) {
                callback.onError(SummaryError.UNKNOWN);
                return new RequestHandle(key, cacheEpoch);
            }
            if (!request.isRefresh()) {
                SummaryResponse cached = findCached(key);
                if (cached != null) {
                    callback.onSuccess(cached.asCached());
                    return new RequestHandle(key, cacheEpoch);
                }
            }
            handle = new RequestHandle(key, cacheEpoch);
            latestFills.put(key, handle);
        }

        final Call<SummaryResponse> call;
        try {
            call = Objects.requireNonNull(api.summarize(conversationId, request), "summary call");
        } catch (RuntimeException failure) {
            handle.finishError(SummaryErrorMapper.fromFailure(failure), callback);
            return handle;
        }

        handle.call = call;
        try {
            call.enqueue(new retrofit2.Callback<SummaryResponse>() {
                @Override
                public void onResponse(Call<SummaryResponse> ignored, Response<SummaryResponse> response) {
                    if (response.isSuccessful() && response.body() != null) {
                        handle.finishSuccess(response.body(), callback);
                        return;
                    }
                    SummaryError error = SummaryErrorMapper.fromHttp(
                            response.code(), readBody(response.errorBody()));
                    handle.finishError(error, callback);
                }

                @Override
                public void onFailure(Call<SummaryResponse> ignored, Throwable failure) {
                    handle.finishError(SummaryErrorMapper.fromFailure(failure), callback);
                }
            });
        } catch (RuntimeException failure) {
            handle.finishError(SummaryErrorMapper.fromFailure(failure), callback);
        }
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

    /** Drops cached and pending summaries at the same epoch boundary as UI publication. */
    public void clearCache() {
        invalidateCache(null);
    }

    private void invalidateCache(SummaryError reason) {
        synchronized (this) {
            cacheEpoch++;
            cache.clear();
            latestFills.clear();
            for (Consumer<SummaryError> listener : invalidationListeners) {
                listener.accept(reason);
            }
        }
    }

    synchronized long cacheEpoch() {
        return cacheEpoch;
    }

    synchronized Cancellable onCacheInvalidated(Consumer<SummaryError> listener) {
        Objects.requireNonNull(listener, "listener");
        invalidationListeners.add(listener);
        return () -> invalidationListeners.remove(listener);
    }

    private CacheKey keyFor(String conversationId, SummaryRequest request) {
        String zone = request.getTimeZone();
        String localDate = request.getScope() == SummaryScope.TODAY ? localDate(zone) : null;
        return new CacheKey(conversationId, request.getScope(), request.getLimit(),
                request.getLanguage(), zone, localDate);
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
        synchronized (this) {
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
        synchronized (this) {
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

    private final class RequestHandle implements Cancellable {
        private final CacheKey key;
        private final long epoch;
        private volatile Call<?> call;
        private boolean cancelled;
        private boolean finished;

        private RequestHandle(CacheKey key, long epoch) {
            this.key = key;
            this.epoch = epoch;
        }

        @Override
        public void cancel() {
            synchronized (SummaryRepository.this) {
                if (cancelled) {
                    return;
                }
                cancelled = true;
                latestFills.remove(key, this);
            }
            Call<?> pending = call;
            if (pending != null) {
                pending.cancel();
            }
        }

        private void finishSuccess(SummaryResponse response, Callback callback) {
            synchronized (SummaryRepository.this) {
                if (cancelled || finished) {
                    return;
                }
                finished = true;
                boolean currentEpoch = epoch == cacheEpoch;
                // A newer request owns this key even if it has already failed or finished.
                if (latestFills.remove(key, this) && currentEpoch) {
                    putCached(key, response);
                }
                // Delivery and invalidation have one ordering point. A consent clear that
                // already ran cannot be followed by success from its old request epoch.
                if (currentEpoch) {
                    callback.onSuccess(response);
                } else {
                    callback.onError(SummaryError.UNKNOWN);
                }
            }
        }

        private void finishError(SummaryError error, Callback callback) {
            synchronized (SummaryRepository.this) {
                if (cancelled || finished) {
                    return;
                }
                finished = true;
                latestFills.remove(key, this);
                if (epoch != cacheEpoch) {
                    callback.onError(SummaryError.UNKNOWN);
                    return;
                }
                if (error == SummaryError.NOT_MEMBER) {
                    invalidateCache(error);
                }
                callback.onError(error);
            }
        }
    }

    private static final class CacheKey {
        private final String conversationId;
        private final SummaryScope scope;
        private final Integer limit;
        private final SummaryLanguage language;
        private final String timeZone;
        private final String localDate;

        private CacheKey(
                String conversationId,
                SummaryScope scope,
                Integer limit,
                SummaryLanguage language,
                String timeZone,
                String localDate) {
            this.conversationId = conversationId;
            this.scope = scope;
            this.limit = limit;
            this.language = language;
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
                    && language == that.language
                    && Objects.equals(timeZone, that.timeZone)
                    && Objects.equals(localDate, that.localDate);
        }

        @Override
        public int hashCode() {
            return Objects.hash(conversationId, scope, limit, language, timeZone, localDate);
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
