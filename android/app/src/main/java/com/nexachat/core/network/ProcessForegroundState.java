package com.nexachat.core.network;

import androidx.annotation.NonNull;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ProcessLifecycleOwner;
import javax.inject.Inject;
import javax.inject.Singleton;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.Dispatchers;
import kotlinx.coroutines.channels.ProduceKt;
import kotlinx.coroutines.channels.ProducerScope;
import kotlinx.coroutines.flow.Flow;
import kotlinx.coroutines.flow.FlowKt;

/**
 * {@link AppForegroundState} backed by {@link ProcessLifecycleOwner} (lifecycle-process is
 * already on the app's classpath and initialises itself via App Startup).
 *
 * <p>The reporter is only ever registered/removed on the main thread —
 * {@code LifecycleRegistry} enforces that — hence {@code flowOn(Main.immediate)}.
 */
@Singleton
public final class ProcessForegroundState implements AppForegroundState {

    private final Flow<Boolean> isForeground;

    @Inject
    public ProcessForegroundState() {
        Flow<Boolean> source = FlowKt.callbackFlow(
                new Function2<ProducerScope<? super Boolean>, Continuation<? super Unit>, Object>() {
                    @Override
                    public Object invoke(
                            ProducerScope<? super Boolean> scope,
                            Continuation<? super Unit> continuation) {
                        LifecycleOwner owner = ProcessLifecycleOwner.get();
                        DefaultLifecycleObserver observer = new DefaultLifecycleObserver() {
                            @Override
                            public void onStart(@NonNull LifecycleOwner o) {
                                scope.offer(Boolean.TRUE);
                            }

                            @Override
                            public void onStop(@NonNull LifecycleOwner o) {
                                scope.offer(Boolean.FALSE);
                            }
                        };
                        owner.getLifecycle().addObserver(observer);
                        scope.offer(
                                owner.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED));
                        return ProduceKt.awaitClose(
                                scope,
                                new Function0<Unit>() {
                                    @Override
                                    public Unit invoke() {
                                        owner.getLifecycle().removeObserver(observer);
                                        return Unit.INSTANCE;
                                    }
                                },
                                continuation);
                    }
                });
        this.isForeground = FlowKt.flowOn(
                FlowKt.distinctUntilChanged(FlowKt.conflate(source)),
                Dispatchers.getMain().getImmediate());
    }

    @Override
    public Flow<Boolean> getIsForeground() {
        return isForeground;
    }
}
