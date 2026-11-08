package com.nexachat.observability;

import com.nexachat.notification.PushResult;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Counts push dispatch results. The registration token is never read; only
 * the future outcome is classified into a bounded tag.
 */
@Aspect
@Component
public class FcmPushMetricsAspect {

    private final ObservabilityMetrics metrics;

    public FcmPushMetricsAspect(ObservabilityMetrics metrics) {
        this.metrics = metrics;
    }

    @Around("execution(* com.nexachat.notification.FcmGateway.sendDataPush(..))")
    public Object recordPushResult(ProceedingJoinPoint joinPoint) throws Throwable {
        @SuppressWarnings("unchecked")
        CompletableFuture<PushResult> future =
                (CompletableFuture<PushResult>) joinPoint.proceed();
        if (future == null) {
            metrics.recordPushSend("UNKNOWN");
            return null;
        }
        future.whenComplete((result, error) -> {
            if (error != null) {
                metrics.recordPushSend("ERROR");
            } else if (result == null) {
                metrics.recordPushSend("UNKNOWN");
            } else if (result.unregistered()) {
                metrics.recordPushSend("UNREGISTERED");
            } else if (result.success()) {
                metrics.recordPushSend("SUCCESS");
            } else {
                metrics.recordPushSend("FAILURE");
            }
        });
        return future;
    }
}
