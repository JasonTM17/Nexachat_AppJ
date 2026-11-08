package com.nexachat.observability;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Times every Spring Data repository call and logs calls slower than the
 * configured threshold. The slow-call log carries the method name only —
 * never arguments, which can contain user data or message text.
 */
@Aspect
@Component
public class RepositoryTimingAspect {

    private static final Logger log = LoggerFactory.getLogger(RepositoryTimingAspect.class);

    private final ObservabilityMetrics metrics;
    private final ObservabilityProperties properties;

    public RepositoryTimingAspect(ObservabilityMetrics metrics, ObservabilityProperties properties) {
        this.metrics = metrics;
        this.properties = properties;
    }

    @Around("execution(* com.nexachat..*Repository.*(..))")
    public Object recordRepositoryCall(ProceedingJoinPoint joinPoint) throws Throwable {
        long start = System.nanoTime();
        String method = methodKey(joinPoint);
        try {
            Object result = joinPoint.proceed();
            long millis = elapsedMillis(start);
            metrics.recordRepositoryCall(method, millis, true);
            logSlowCall(method, millis);
            return result;
        } catch (Throwable failure) {
            long millis = elapsedMillis(start);
            metrics.recordRepositoryCall(method, millis, false);
            logSlowCall(method, millis);
            throw failure;
        }
    }

    private void logSlowCall(String method, long millis) {
        if (millis >= properties.slowQueryThresholdMs()) {
            log.warn("Slow repository call {} took {} ms", method, millis);
        }
    }

    static String methodKey(ProceedingJoinPoint joinPoint) {
        return joinPoint.getSignature().getDeclaringType().getSimpleName()
                + "." + joinPoint.getSignature().getName();
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
