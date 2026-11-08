package com.nexachat.observability;

import com.nexachat.message.IdempotencyService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Counts idempotent mutation replays. The aspect wraps the caller's
 * {@link Runnable} so it can tell a replay (the action was not run) from a
 * first attempt, without changing the service.
 */
@Aspect
@Component
public class IdempotencyMetricsAspect {

    private final ObservabilityMetrics metrics;

    public IdempotencyMetricsAspect(ObservabilityMetrics metrics) {
        this.metrics = metrics;
    }

    @Around("execution(* com.nexachat.message.IdempotencyService.execute(..))")
    public Object recordReplay(ProceedingJoinPoint joinPoint) throws Throwable {
        Object[] args = joinPoint.getArgs();
        String key = args.length > 1 ? castable(args[1]) : null;
        Runnable action = args.length > 4 && args[4] instanceof Runnable runnable ? runnable : null;
        boolean track = key != null && !key.isBlank() && action != null;
        AtomicBoolean ran = new AtomicBoolean();
        if (track) {
            args[4] = (Runnable) () -> {
                ran.set(true);
                action.run();
            };
        }
        boolean completed = false;
        try {
            Object result = joinPoint.proceed(args);
            completed = true;
            return result;
        } finally {
            if (track && completed && !ran.get()) {
                metrics.recordIdempotencyReplay();
            }
        }
    }

    private static String castable(Object value) {
        return value instanceof String string ? string : null;
    }
}
