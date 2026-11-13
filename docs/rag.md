# Conversation RAG — "Ask this chat"

Status: server shipped (retrieval, embeddings, ask route, caches, eval benchmark); the Android Ask screen is present in this checkout.

## What it does

A member asks a question about a conversation they belong to. The server
retrieves a small, budgeted set of relevant messages and answers with one of
four modes:

| Mode | Meaning | LLM call |
|---|---|---|
| `CACHE` | The exact normalized question was already answered, or a cached question is semantically identical and its cited messages are unchanged | no |
| `NOT_FOUND` | The best retrieval score is below the answerability threshold | no |
| `EXTRACTIVE` | A lookup-like question is answered by one high-scoring message, returned verbatim with its citation | no |
| `LLM` | The retrieved, redacted, budgeted context is sent to the configured LLM | yes (only with consent) |

The route is `POST /api/v1/conversations/{id}/ask`; see
[`docs/api-contract.md`](api-contract.md) for the wire format.

## Retrieval pipeline

1. **Candidate set.** The most recent 5,000 eligible messages of the one
   conversation (`TEXT`, not deleted, non-blank) plus a pg_trgm lexical
   pre-filter (top 300) built from the folded question terms. The trigram
   predicate uses the `f_unaccent` IMMUTABLE wrapper so it is served by
   `idx_msg_conv_content_trgm` (Flyway V8).
2. **Embeddings.** The question and the candidate messages are embedded with
   the selected provider. Missing vectors are indexed lazily at query time
   (bounded per request) and by a background backfill (bounded per minute).
   Similarity is computed in the JVM over the bounded candidate set; there is
   no pgvector dependency.
3. **Fusion.** BM25 lexical ranking and vector cosine ranking are fused with
   reciprocal-rank fusion (`k = 60`), then diversified with MMR
   (`lambda = 0.72`) reusing the summary engine's `Mmr`.
4. **Context.** Neighbour expansion (±2 messages) and reply-parent recovery,
   then short consecutive same-sender messages within two minutes are merged
   up to 400 characters. The context is capped at 3,000 characters by default
   (today's summary path can send up to 24,000).
5. **Answer.** Every citation must map to a message in the retrieved context;
   unknown indexes are dropped and an answer without a valid citation is
   rejected. The answer language follows the question.

## Embedding providers

| Provider | ID | Network | Default |
|---|---|---|---|
| Local lexical | `lexical` | none | yes |
| OpenAI-compatible / native Gemini | `openai-compatible:<model>` (for example `openai-compatible:gemini-embedding-001`) | outbound HTTPS | no |

The local provider is a deterministic 256-d hashed character-trigram + word
TF vector over accent-folded text. It works offline, costs nothing and keeps
message text inside the server.

The external provider supports two request shapes:

- `openai-compatible`: `POST {base}/embeddings` with
  `{"model", "input", "dimensions"}` and an `Authorization: Bearer` header;
- `gemini`: `POST {base}/models/{model}:embedContent` with
  `{"content":{"parts":[{"text"}]}, "outputDimensionality"}` and an
  `x-goog-api-key` header.

The base URL must be HTTPS, or loopback HTTP for local testing. The API key
is read from `NEXACHAT_RAG_EMBEDDING_API_KEY`, falling back to
`NEXACHAT_SUMMARY_LLM_API_KEY`. It is only placed in the request header and is
never logged, serialized or echoed in an exception.

Vectors are stored per message in `message_embeddings` (Flyway V9) with the
provider identity in `model`; for an external provider that identity includes
the configured model (`openai-compatible:<model>`). Only vectors whose `model`
matches the provider used for the current request are read, so a lexical
fallback never mixes spaces with an external index, and changing
`NEXACHAT_RAG_EMBEDDING_MODEL` leaves the old rows unread so they are
recomputed under the new identity. The in-memory vector cache and the ask
cache are scoped by the same identity.

## Consent and privacy

- The external embedding provider and the LLM tier are used only when the
  requesting user has `users.ai_summary_consent = true`. The LLM adapter
  re-checks the persisted flag immediately before the outbound call.
- Without consent, retrieval and extractive answers still work using the
  offline lexical provider; no external call is made.
- The requester’s consent is checked before reading the shared ask-answer cache
  or joining an in-flight ask. Without consent, the request skips cache reads
  and computes locally so a member cannot receive an answer produced by an
  external-model request made under another member’s consent.
- Before responding, the server compares the consent snapshot and serializes
  the selected response while holding a shared lock on the user row. It releases
  the database transaction and connection before writing the serialized bytes
  to the HTTP response. This serialization point orders the response against
  consent changes: a revocation committed before serialization is observed and
  suppresses the answer; a revocation committed afterward is linearized after
  that response, even if a slow client receives the bytes later.
- **Per-conversation opt-out (Flyway V13).** Any member can call
  `PUT /api/v1/conversations/{id}/ai-opt-out` to exclude their own messages
  from AI processing in that conversation; `DELETE` clears it. Both are
  member-only and idempotent, and `GET` returns only the caller's own state
  (`{conversationId, aiOptOut}`); another member's choice is never exposed.
  Opted-out messages are excluded from the candidate set, lexical candidates,
  reply-parent recovery, lazy indexing and the background backfill, including
  for the offline lexical provider. Opting out also deletes the stored vectors
  for that member's messages in the conversation and cached ask answers that
  cite them.
- The ask response carries `contextOmitted`: `true` when at least one eligible
  message in the conversation is currently excluded because its author opted
  out. It is a neutral flag and never identifies the member. The local
  extractive path honors the same exclusion because it is part of the RAG
  retrieval pipeline.
- Message text and the question are redacted (`Redactor`) before they leave
  the server: phone numbers, emails, URL paths and queries, long digit
  strings and token-like strings.
- The prompt wraps the conversation in an explicit untrusted-data block and
  instructs the model to treat it and the question as data. Prompt injection
  in a message cannot redirect the model or leak another conversation.
- Cached answers store the question hash and vector, the answer payload and
  the cited message ids/hashes. A cache hit is available only to a requester
  with account-level AI consent and is replayed only when every cited message
  still exists, is not deleted, its content hash matches, and its author has
  not opted out of AI processing in that conversation.
- Vectors are never exposed by any API. `GET /api/v1/rag/stats` returns
  aggregate counters only. This endpoint is operator telemetry restricted
  to authorized administrator phone numbers (`NEXACHAT_ADMIN_PHONES`);
  non-admin authenticated callers receive `403 FORBIDDEN`.

## Cost controls

| Control | Default |
|---|---|
| Exact question cache | 24 h TTL |
| Semantic cache | cosine ≥ 0.92, same model, cited messages unchanged |
| Answerability gate | fused score < 0.18 → `NOT_FOUND` |
| Extractive gate | lookup-like question and fused score ≥ 0.62 |
| Context budget | 3,000 characters |
| Lazy indexing per request | 500 messages |
| Background backfill | 200 messages/minute |
| Ask rate limit | 20/minute, 200/day per user |

The background backfill always indexes with the local `lexical` provider, even
when an external embedding provider is configured. It holds a PostgreSQL
advisory lock (`ScheduledAdvisoryLock`), so with several application instances
only the node that acquires the lock runs a tick. External embeddings are used
only on the consent-gated request path; operators must have a lawful basis (for
example user consent) before setting `NEXACHAT_RAG_EMBEDDING_PROVIDER=openai-compatible`.
The default `lexical` provider keeps all message text on the server.

Incremental summaries: the ask cache already avoids re-answering unchanged
questions. Merging new messages into an existing summary instead of
re-sending the whole window is a follow-up for the summary path.

## LLM provider fallback

The ask path uses the same LLM tier as summaries. The primary cloud provider
(`nexachat.summary.llm.*`) can be paired with a fallback provider
(`nexachat.summary.llm-fallback.*`), disabled by default and activated only
when its key is configured. On a primary transport error, timeout, HTTP 5xx,
HTTP 429 or an open circuit, the fallback is tried once with the same
already-redacted prompt and the same output validation; a primary 4xx other
than 429 (bad request, auth) is not retried against the fallback, and the
provider error body is never leaked. If both providers fail, the ask router
degrades to `EXTRACTIVE` or `NOT_FOUND` as before.

`usage.provider` is `primary` or `fallback` for an `LLM` answer and `null`
otherwise. `GET /api/v1/rag/stats` exposes `llmFallbackCalls` next to
`llmCalls`. A fallback call counts toward the same per-user rate limit and the
same question cache as a primary call; a cache hit never calls either provider.

## Configuration

| Configuration key | Environment variable | Default |
|---|---|---|
| `nexachat.rag.enabled` | `NEXACHAT_RAG_ENABLED` | `true` |
| `nexachat.rag.embedding.provider` | `NEXACHAT_RAG_EMBEDDING_PROVIDER` | `lexical` |
| `nexachat.rag.embedding.mode` | `NEXACHAT_RAG_EMBEDDING_MODE` | `openai-compatible` |
| `nexachat.rag.embedding.base-url` | `NEXACHAT_RAG_EMBEDDING_BASE_URL` | — |
| `nexachat.rag.embedding.model` | `NEXACHAT_RAG_EMBEDDING_MODEL` | `gemini-embedding-001` |
| `nexachat.rag.embedding.api-key` | `NEXACHAT_RAG_EMBEDDING_API_KEY` | falls back to `NEXACHAT_SUMMARY_LLM_API_KEY` |
| `nexachat.rag.embedding.dim` | `NEXACHAT_RAG_EMBEDDING_DIM` | `256` |
| `nexachat.rag.embedding.timeout-ms` | `NEXACHAT_RAG_EMBEDDING_TIMEOUT_MS` | `20000` |
| `nexachat.rag.retrieval.max-context-chars` | `NEXACHAT_RAG_MAX_CONTEXT_CHARS` | `3000` |
| `nexachat.rag.retrieval.not-found-threshold` | `NEXACHAT_RAG_NOT_FOUND_THRESHOLD` | `0.18` |
| `nexachat.rag.retrieval.extractive-threshold` | `NEXACHAT_RAG_EXTRACTIVE_THRESHOLD` | `0.62` |
| `nexachat.rag.retrieval.semantic-cache-threshold` | `NEXACHAT_RAG_SEMANTIC_CACHE_THRESHOLD` | `0.92` |
| `nexachat.rag.index.lazy-limit` | `NEXACHAT_RAG_INDEX_LAZY_LIMIT` | `500` |
| `nexachat.rag.index.backfill-rate-per-minute` | `NEXACHAT_RAG_BACKFILL_RATE_PER_MINUTE` | `200` |
| `nexachat.rag.ask.rate-limit-per-minute` | `NEXACHAT_RAG_ASK_RATE_LIMIT_PER_MINUTE` | `20` |
| `nexachat.rag.ask.rate-limit-per-day` | `NEXACHAT_RAG_ASK_RATE_LIMIT_PER_DAY` | `200` |
| `nexachat.rag.ask.cache-ttl-hours` | `NEXACHAT_RAG_ASK_CACHE_TTL_HOURS` | `24` |
| `nexachat.security.admin-phones` | `NEXACHAT_ADMIN_PHONES` | — (empty ⇒ stats endpoint denied to all) |
| `nexachat.summary.llm-fallback.enabled` | `NEXACHAT_SUMMARY_LLM_FALLBACK_ENABLED` | `false` |
| `nexachat.summary.llm-fallback.base-url` | `NEXACHAT_SUMMARY_LLM_FALLBACK_BASE_URL` | `https://api.deepseek.com` |
| `nexachat.summary.llm-fallback.model` | `NEXACHAT_SUMMARY_LLM_FALLBACK_MODEL` | `deepseek-flash` |
| `nexachat.summary.llm-fallback.api-key` | `NEXACHAT_SUMMARY_LLM_FALLBACK_API_KEY` | — (absent ⇒ fallback unavailable) |
| `nexachat.summary.llm-fallback.timeout-ms` | `NEXACHAT_SUMMARY_LLM_FALLBACK_TIMEOUT_MS` | `30000` |

All settings work offline with no key.

## Benchmark

`tools/rag-eval/run-benchmark.sh` runs `RagBenchmarkEvaluationTest`, a
synthetic Vietnamese + English corpus (32 conversations, 6,400 messages, 192
lookup questions with gold message ids). Latest reported result:

```
recall@3=1.000 recall@5=1.000 mrr=1.000 citationValidity=1.000
zeroLlm=70.0% meanInputChars=1640.6 baselineChars=7951.4 reduction=79.4%
```

Targets: recall@5 ≥ 0.85 on lookup questions with the lexical provider,
≥ 40% of requests with zero LLM calls, and input characters reduced by ≥ 70%
against the whole-window baseline.
