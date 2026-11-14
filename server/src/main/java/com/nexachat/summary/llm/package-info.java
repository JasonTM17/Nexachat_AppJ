/**
 * External LLM summaries ({@code LlmSummaryProvider}) through an OpenAI-compatible
 * {@code LlmClient} with optional fallback provider and circuit breaker. Prompts drop
 * opted-out authors and redact emails, URLs, phones and tokens; output is validated
 * before use. Disabled unless {@code nexachat.summary.llm} is configured and enabled.
 */
package com.nexachat.summary.llm;
