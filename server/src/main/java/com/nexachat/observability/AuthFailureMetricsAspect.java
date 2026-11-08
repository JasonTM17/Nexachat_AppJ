package com.nexachat.observability;

import com.nexachat.auth.AuthSecurityErrorHandler;
import com.nexachat.auth.JwtAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.stereotype.Component;

/**
 * Counts security-layer failures by the precise reason the entry point will
 * report: TOKEN_EXPIRED, TOKEN_INVALID, UNAUTHORIZED or FORBIDDEN.
 */
@Aspect
@Component
public class AuthFailureMetricsAspect {

    private final ObservabilityMetrics metrics;

    public AuthFailureMetricsAspect(ObservabilityMetrics metrics) {
        this.metrics = metrics;
    }

    @Before("execution(* com.nexachat.auth.AuthSecurityErrorHandler.commence(..))")
    public void recordAuthenticationFailure(JoinPoint joinPoint) {
        Object code = null;
        if (joinPoint.getArgs().length > 0 && joinPoint.getArgs()[0] instanceof HttpServletRequest request) {
            code = request.getAttribute(JwtAuthFilter.AUTH_ERROR_ATTRIBUTE);
        }
        metrics.recordAuthFailure(code == null ? "UNAUTHORIZED" : code.toString());
    }

    @Before("execution(* com.nexachat.auth.AuthSecurityErrorHandler.handle(..))")
    public void recordAccessDenied(JoinPoint joinPoint) {
        metrics.recordAuthFailure("FORBIDDEN");
    }
}
