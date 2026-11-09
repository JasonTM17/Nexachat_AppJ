package com.nexachat.observability;

import com.nexachat.auth.RateLimitService;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Counts rate-limit rejections by a fixed rule label. Bucket keys carry a user
 * id or IP; values outside the allow-list collapse to {@code OTHER}.
 */
@Aspect
@Component
public class RateLimitMetricsAspect {

    private static final Set<String> ALLOWED_RULES = Set.of(
            "login", "register", "refresh",
            "login|phone", "register|phone",
            "message-send", "message-write", "message-search",
            "user-search", "contact-add",
            "stomp:messages", "stomp:typing", "stomp:acks",
            "call:invite", "call:media");

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

    /** Extracts a fixed rule name from the identity-bearing bucket key. */
    static String rulePrefix(String bucketKey) {
        if (bucketKey == null || bucketKey.isBlank()) {
            return "OTHER";
        }
        String rule = bucketKey;
        int pipe = bucketKey.lastIndexOf('|');
        if (pipe > 0) {
            rule = bucketKey.substring(0, pipe);
        } else if (bucketKey.startsWith("stomp:") || bucketKey.startsWith("call:")) {
            int identitySeparator = bucketKey.lastIndexOf(':');
            if (identitySeparator > bucketKey.indexOf(':')) {
                rule = bucketKey.substring(0, identitySeparator);
            }
        }
        return ALLOWED_RULES.contains(rule) ? rule : "OTHER";
    }
}
