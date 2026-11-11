package com.nexachat.core.network;

import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.processors.BehaviorProcessor;
import io.reactivex.rxjava3.processors.FlowableProcessor;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Response;

/** Waits for Render's idempotent health endpoint before an auth mutation is sent. */
@Singleton
public final class ServerWakeGate {
    /** Render Free measured 123 s from container start to healthy; this adds a margin. */
    public static final long MAX_WAIT_MS = 150_000L;

    private final HealthApi healthApi;
    private final Scheduler attemptScheduler;
    private final Scheduler retryScheduler;
    private final LongSupplier elapsedRealtimeMillis;
    private final FlowableProcessor<Boolean> starting =
            BehaviorProcessor.<Boolean>createDefault(false).toSerialized();
    private final AtomicInteger activeWakeWaits = new AtomicInteger();

    @Inject
    public ServerWakeGate(HealthApi healthApi) {
        this(healthApi, Schedulers.io(), Schedulers.computation(),
                () -> android.os.SystemClock.elapsedRealtime());
    }

    /** Deterministic scheduler and clock seam for JVM tests. */
    public ServerWakeGate(
            HealthApi healthApi,
            Scheduler attemptScheduler,
            Scheduler retryScheduler,
            LongSupplier elapsedRealtimeMillis) {
        this.healthApi = Objects.requireNonNull(healthApi, "healthApi");
        this.attemptScheduler = Objects.requireNonNull(attemptScheduler, "attemptScheduler");
        this.retryScheduler = Objects.requireNonNull(retryScheduler, "retryScheduler");
        this.elapsedRealtimeMillis = Objects.requireNonNull(
                elapsedRealtimeMillis, "elapsedRealtimeMillis");
    }

    public Flowable<Boolean> getIsStarting() {
        return starting.onBackpressureLatest().distinctUntilChanged();
    }

    /**
     * Retries only the safe readiness GET. Auth POSTs are sent once after the
     * server is ready, so an ambiguous timeout cannot mint a duplicate login.
     */
    public Single<Boolean> awaitReady() {
        return Single.defer(() -> {
            WakeCycle cycle = new WakeCycle(elapsedRealtimeMillis.getAsLong());
            return attempt(cycle, 0).doFinally(() -> leaveWakeState(cycle));
        });
    }

    private Single<Boolean> attempt(WakeCycle cycle, int failureCount) {
        long elapsed = elapsedMillis(cycle.startedAtMillis);
        if (elapsed >= MAX_WAIT_MS) {
            return Single.just(false);
        }
        long remaining = MAX_WAIT_MS - elapsed;
        Call<ResponseBody> call = healthApi.health();
        call.timeout().timeout(remaining, TimeUnit.MILLISECONDS);
        return Single.fromCallable(() -> execute(call))
                .subscribeOn(attemptScheduler)
                .onErrorReturn(ProbeResult::failed)
                .flatMap(result -> {
                    if (result.ready) {
                        return Single.just(true);
                    }
                    if (!result.retryable) {
                        return Single.just(false);
                    }
                    enterWakeState(cycle);
                    long timeLeft = MAX_WAIT_MS - elapsedMillis(cycle.startedAtMillis);
                    if (timeLeft <= 0L) {
                        return Single.just(false);
                    }
                    long delay = Math.min(backoffMillis(failureCount + 1), timeLeft);
                    return Single.timer(delay, TimeUnit.MILLISECONDS, retryScheduler)
                            .flatMap(ignored -> attempt(cycle, failureCount + 1));
                });
    }

    private ProbeResult execute(Call<ResponseBody> call) throws IOException {
        Response<ResponseBody> response = call.execute();
        boolean successful = response.isSuccessful();
        int status = response.code();
        close(response.body());
        close(response.errorBody());
        return ProbeResult.response(status, successful);
    }

    private static void close(ResponseBody body) throws IOException {
        if (body != null) {
            body.close();
        }
    }

    private void enterWakeState(WakeCycle cycle) {
        if (cycle.waiting.compareAndSet(false, true)
                && activeWakeWaits.getAndIncrement() == 0) {
            starting.onNext(true);
        }
    }

    private void leaveWakeState(WakeCycle cycle) {
        if (cycle.waiting.compareAndSet(true, false)
                && activeWakeWaits.decrementAndGet() == 0) {
            starting.onNext(false);
        }
    }

    private long elapsedMillis(long startedAtMillis) {
        return Math.max(0L, elapsedRealtimeMillis.getAsLong() - startedAtMillis);
    }

    static long backoffMillis(int failureCount) {
        int shift = Math.max(0, Math.min(failureCount - 1, 4));
        return 1_000L << shift;
    }

    private static boolean isWakeStatus(int status) {
        return status == 502 || status == 503 || status == 504;
    }

    private static boolean isSocketTimeout(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SocketTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static final class WakeCycle {
        private final long startedAtMillis;
        private final AtomicBoolean waiting = new AtomicBoolean();

        private WakeCycle(long startedAtMillis) {
            this.startedAtMillis = startedAtMillis;
        }
    }

    private static final class ProbeResult {
        private final boolean ready;
        private final boolean retryable;

        private ProbeResult(boolean ready, boolean retryable) {
            this.ready = ready;
            this.retryable = retryable;
        }

        private static ProbeResult response(int status, boolean successful) {
            return new ProbeResult(successful, isWakeStatus(status));
        }

        private static ProbeResult failed(Throwable failure) {
            return new ProbeResult(false, isSocketTimeout(failure));
        }
    }
}
