package com.nexachat.observability;

import com.nexachat.call.CallRegistry;
import com.nexachat.call.CallSession;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Counts call state transitions with bounded action/state/reason tags.
 */
@Aspect
@Component
public class CallStateMetricsAspect {

    private final ObservabilityMetrics metrics;

    public CallStateMetricsAspect(ObservabilityMetrics metrics) {
        this.metrics = metrics;
    }

    @AfterReturning(pointcut = "execution(* com.nexachat.call.CallRegistry.*(..))",
            returning = "value")
    public void recordTransition(JoinPoint joinPoint, Object value) {
        String action = joinPoint.getSignature().getName();
        if (value instanceof CallRegistry.Invitation invitation) {
            record(action, invitation.session());
        } else if (value instanceof CallRegistry.Transition transition) {
            record(action, transition.session());
        } else if (value instanceof Optional<?> optional && optional.isPresent()
                && optional.get() instanceof CallRegistry.Transition transition) {
            record(action, transition.session());
        }
    }

    private void record(String action, CallSession session) {
        metrics.recordCallTransition(action, session.state().name(),
                session.endReason() == null ? "NONE" : session.endReason().name());
    }
}
