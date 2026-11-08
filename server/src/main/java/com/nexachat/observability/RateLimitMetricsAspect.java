package com.nexachat.observability;

import com.nexachat.auth.RateLimitService;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * Counts rate-limit rejections by rule. The bucket key carries a user id or
 * IP; only the rule prefix becomes a tag, so no identity is exposed.
 */
@Aspect
@Component
public class RateLimitMetricsAspect {

    private final ObservabilityMetrics metrics;

    public RateLimitMetricsAspect(ObservabilityMetrics metrics) {
        this.metrics = metrics;
    }

    @AfterReturning(pointcut = "execution(* com.nexachat.auth.RateLimitService.tryConsume(..))",
            returning = "consumption")
    public void recordRejection(JoinPoint joinPoint, RateLimitService.Consumption consumption) {
        if (consumption == null || consumption.allowed()) {
            return;
        }
        Object key = joinPoint.getArgs().length > 0 ? joinPoint.getArgs()[0] : null;
        metrics.recordRateLimitRejection(rulePrefix(String.valueOf(key)));
    }

    /** Extracts the bounded rule name from a bucket key such as {@code message-send|user:...}. */
    static String rulePrefix(String bucketKey) {
        if (bucketKey == null || bucketKey.isBlank()) {
            return "UNKNOWN";
        }
        int separator = bucketKey.lastIndexOf('|');
        return separator > 0 ? bucketKey.substring(0, separator) : bucketKey;
    }
}
