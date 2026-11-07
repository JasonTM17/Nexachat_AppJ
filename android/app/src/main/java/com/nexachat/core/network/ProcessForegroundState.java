package com.nexachat.core.network;

import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ProcessLifecycleOwner;
import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.core.Flowable;
import javax.inject.Inject;
import javax.inject.Singleton;

/** Process foreground state exposed as a replaying RxJava stream. */
@Singleton
public final class ProcessForegroundState implements AppForegroundState {

    private final Flowable<Boolean> isForeground;

    @Inject
    public ProcessForegroundState() {
        Handler main = new Handler(Looper.getMainLooper());
        isForeground = Flowable.<Boolean>create(emitter -> {
            LifecycleOwner owner = ProcessLifecycleOwner.get();
            DefaultLifecycleObserver observer = new DefaultLifecycleObserver() {
                @Override
                public void onStart(@NonNull LifecycleOwner lifecycleOwner) {
                    emitter.onNext(true);
                }

                @Override
                public void onStop(@NonNull LifecycleOwner lifecycleOwner) {
                    emitter.onNext(false);
                }
            };
            Runnable register = () -> {
                if (emitter.isCancelled()) return;
                owner.getLifecycle().addObserver(observer);
                emitter.onNext(owner.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED));
            };
            main.post(register);
            emitter.setCancellable(() -> main.post(() -> owner.getLifecycle().removeObserver(observer)));
        }, BackpressureStrategy.LATEST)
                .distinctUntilChanged()
                .replay(1)
                .refCount();
    }

    @NonNull
    @Override
    public Flowable<Boolean> getIsForeground() {
        return isForeground;
    }
}
