/**
 * Conversation summaries and AI privacy: {@code SummaryController}/{@code SummaryService}
 * with window selection, caching and rate limits; local-first provider routing that uses
 * an external LLM ({@code llm}) only with AI consent, otherwise the local extractive
 * engine ({@code engine}); and the per-conversation AI opt-out ({@code ConversationAiOptOutController}).
 */
package com.nexachat.summary;
