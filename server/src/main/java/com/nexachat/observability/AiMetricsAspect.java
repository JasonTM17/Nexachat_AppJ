package com.nexachat.observability;

import com.nexachat.rag.AskResponse;
import com.nexachat.rag.RagLlmAnswerService;
import com.nexachat.summary.PreparedSummaryProvider;
import com.nexachat.summary.SummaryGeneration;
import com.nexachat.summary.SummaryProviderSelector;
import com.nexachat.summary.SummaryResponse;
import com.nexachat.summary.SummaryService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * AI request metrics: RAG ask by answer mode and embedding provider, summary
 * by provider/mode/fallback, and LLM completion latency for the LLM paths.
 */
@Aspect
@Component
public class AiMetricsAspect {

    private final ObservabilityMetrics metrics;
    private final String ragProvider;

    public AiMetricsAspect(ObservabilityMetrics metrics,
                           @Value("${nexachat.rag.embedding.provider:lexical}") String ragProvider) {
        this.metrics = metrics;
        this.ragProvider = ragProvider;
    }

    @AfterReturning(pointcut = "execution(* com.nexachat.rag.AskService.ask(..))",
            returning = "response")
    public void recordRagRequest(AskResponse response) {
        metrics.recordRagRequest(response.mode().name(), ragProvider);
    }

    @AfterReturning(pointcut = "execution(* com.nexachat.summary.SummaryService.summarize(..))",
            returning = "response")
    public void recordSummaryRequest(SummaryResponse response) {
        metrics.recordSummaryRequest(response.generatedBy(),
                response.cached() ? "CACHE" : "GENERATE",
                response.fallbackReason() == null ? "NONE" : response.fallbackReason());
    }

    @Around("execution(* com.nexachat.rag.RagLlmAnswerService.answer(..))")
    public Object recordRagLlmLatency(ProceedingJoinPoint joinPoint) throws Throwable {
        long start = System.nanoTime();
        try {
            Object result = joinPoint.proceed();
            metrics.recordLlmCall("rag", "llm", result == null ? "FAILURE" : "SUCCESS",
                    "NONE", elapsedMillis(start));
            return result;
        } catch (Throwable failure) {
            metrics.recordLlmCall("rag", "llm", "FAILURE", "NONE", elapsedMillis(start));
            throw failure;
        }
    }

    @Around("execution(* com.nexachat.summary.SummaryProviderSelector.generate(..))")
    public Object recordSummaryLlmLatency(ProceedingJoinPoint joinPoint) throws Throwable {
        long start = System.nanoTime();
        PreparedSummaryProvider prepared = (PreparedSummaryProvider) joinPoint.getArgs()[0];
        String provider = prepared.provider().id();
        if (!"LLM".equals(provider)) {
            return joinPoint.proceed();
        }
        try {
            SummaryGeneration result = (SummaryGeneration) joinPoint.proceed();
            String fallback = result.fallbackReason() == null ? "NONE" : result.fallbackReason().name();
            metrics.recordLlmCall("summary", provider, "SUCCESS", fallback, elapsedMillis(start));
            return result;
        } catch (Throwable failure) {
            metrics.recordLlmCall("summary", provider, "FAILURE", "NONE", elapsedMillis(start));
            throw failure;
        }
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
