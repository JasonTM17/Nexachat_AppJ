package com.nexachat.translate;

import com.nexachat.summary.llm.CircuitBreaker;
import com.nexachat.summary.llm.LlmClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Translate completions through the shared {@link LlmClient} stack: primary
 * cloud model first, the configured cloud fallback once on a retryable
 * failure, then the local model tier. Mirrors {@code RagLlmAnswerService}'s
 * ordering — the caller's consent was already re-checked upstream, and the
 * dedicated {@code translateCircuitBreaker} only gates the primary tier.
 */
@Service
@Profile("!test")
public class LlmTranslationProvider implements TranslationProvider {

    private final LlmClient llmClient;
    private final CircuitBreaker circuitBreaker;

    public LlmTranslationProvider(
            LlmClient llmClient,
            @Qualifier("translateCircuitBreaker") CircuitBreaker circuitBreaker) {
        this.llmClient = llmClient;
        this.circuitBreaker = circuitBreaker;
    }

    @Override
    public ProviderCompletion translate(TranslationPrompt prompt) {
        LlmClient.TargetConfig primary = llmClient.cloudTarget();
        LlmClient.TargetConfig secondary = llmClient.localTarget();
        LlmClient.TargetConfig fallback = primary == null ? null : llmClient.fallbackTarget();

        LlmClient.TargetConfig first = primary != null ? primary : secondary;
        if (first == null) {
            throw new TranslationUnavailableException("No translation provider is configured");
        }

        boolean breakerAllows = circuitBreaker.allowRequest();
        if (!breakerAllows && fallback == null && secondary == null) {
            throw new TranslationUnavailableException("Translation provider circuit is open");
        }

        boolean primaryAttempted = false;
        boolean breakerRecorded = false;
        try {
            LlmClient.LlmCallResult completion;
            boolean fallbackUsed = false;
            boolean primaryUsed = false;
            boolean primaryFailed = false;
            String provider;
            if (primary != null) {
                primaryAttempted = breakerAllows;
                try {
                    completion = llmClient.completeTextWithFallback(
                            prompt.systemMessage(), prompt.userMessage(),
                            breakerAllows ? primary : null, fallback);
                    fallbackUsed = completion.fallbackUsed();
                    primaryUsed = breakerAllows && !fallbackUsed;
                    primaryFailed = breakerAllows && fallbackUsed;
                    provider = fallbackUsed ? "fallback" : "primary";
                } catch (RuntimeException exception) {
                    primaryFailed = breakerAllows;
                    if (secondary == null) {
                        throw exception;
                    }
                    completion = llmClient.completeTextWithTargetResult(
                            prompt.systemMessage(), prompt.userMessage(), secondary);
                    provider = "local";
                }
            } else {
                completion = llmClient.completeTextWithTargetResult(
                        prompt.systemMessage(), prompt.userMessage(), secondary);
                provider = "local";
            }
            if (TranslationOutputValidator.whitespaceOnly(completion.content())) {
                // A blank or whitespace-only completion is a provider
                // failure, not a success: reject it before any breaker record
                // so this attempt counts against the primary tier (the catch
                // below records it) instead of for it, and it never reaches
                // the output validator or the cache.
                throw new TranslationUnavailableException("Provider returned an empty completion");
            }
            if (primaryUsed) {
                circuitBreaker.recordSuccess();
                breakerRecorded = true;
            } else if (primaryFailed) {
                circuitBreaker.recordFailure();
                breakerRecorded = true;
            }
            return new ProviderCompletion(completion.content(), completion.model(), provider);
        } catch (TranslationUnavailableException exception) {
            // One breaker record per primary attempt: the guarded block above
            // already recorded success or failure for usable completions; a
            // blank one is thrown before any record and counts as a failure
            // here; and a local-tier-only run (no cloud target) never touches
            // the primary-gated breaker.
            if (primaryAttempted && !breakerRecorded) {
                circuitBreaker.recordFailure();
            }
            throw exception;
        } catch (RuntimeException exception) {
            if (primaryAttempted && !breakerRecorded) {
                circuitBreaker.recordFailure();
            }
            throw new TranslationUnavailableException("Translation providers failed", exception);
        }
    }
}
