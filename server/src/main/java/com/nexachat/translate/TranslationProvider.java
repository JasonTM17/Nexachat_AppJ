package com.nexachat.translate;

/**
 * Provider boundary for the translate path — the only seam tests mock.
 * Implementations own tier ordering (primary cloud, configured fallback,
 * local model), timeouts and the circuit breaker; they never log prompt
 * content.
 */
public interface TranslationProvider {

    /**
     * Runs one translate completion.
     *
     * @return the raw completion plus the model/provider label that produced it
     * @throws TranslationUnavailableException when no configured tier produced
     *         a usable completion (timeouts, HTTP failures, disabled LLM)
     */
    ProviderCompletion translate(TranslationPrompt prompt);

    /**
     * The completion text plus provenance. {@code provider} is the stable
     * cache/API label: {@code "primary"}, {@code "fallback"} or {@code "local"}.
     */
    record ProviderCompletion(String content, String model, String provider) {
    }
}
