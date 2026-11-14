/**
 * Conversation question answering ("ask"): {@code AskController} and the
 * {@code AskService} router (question cache, hybrid lexical/vector retrieval,
 * extractive answer, then an optional LLM tier gated by the AI consent snapshot),
 * embedding index and backfill, output validation, rate limits, and aggregate stats.
 */
package com.nexachat.rag;
