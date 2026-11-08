# Conversation catch-up summary

Status: shipped (route, engines, consent, Flyway V6 and Android UI).

## What it does

A member requests a structured summary of a conversation window: overview, key points, decisions, action items, open questions, topics, participants. Every item cites source messages (`messageIds`), so the client can jump to them.

Two engines:

| Engine | `generatedBy` | Network | When |
|---|---|---|---|
| Local extractive (heuristic, deterministic) | `LOCAL` | none | default, always available |
| External LLM (OpenAI-compatible endpoint) | `LLM` | outbound HTTPS | only if the operator configured a key **and** the requesting user opted in; any failure falls back to local |

The local engine selects and labels existing messages; it does not generate new prose.

## API

`POST /api/v1/conversations/{id}/summary` (members only)

```json
{ "scope": "UNREAD|LAST_N|TODAY", "limit": 50, "language": "AUTO|VI|EN", "refresh": false }
```

`limit` applies to `LAST_N` (5..500). The window is capped at the latest 500 usable messages: `TEXT`, not deleted, not system; each message is truncated to 1,000 characters.

`refresh: true` skips a valid cache read, regenerates the summary, and replaces the cache entry for the provider that produced the result. Refresh requests still count toward both rate limits.

Response `200`:

```json
{
  "summaryId": "uuid", "conversationId": "uuid",
  "fromSeq": 120, "toSeq": 187, "messageCount": 64,
  "language": "VI", "generatedBy": "LOCAL", "model": null,
  "provider": null, "fallbackReason": null, "cached": false, "createdAt": "2026-11-05T12:00:00Z",
  "overview": "string, at most 400 chars",
  "keyPoints":   [{ "text": "...", "messageIds": ["uuid"] }],
  "decisions":   [{ "text": "...", "messageIds": ["uuid"] }],
  "actionItems": [{ "text": "...", "ownerUserId": "uuid|null", "dueHint": "string|null", "messageIds": ["uuid"] }],
  "questions":   [{ "text": "...", "messageIds": ["uuid"] }],
  "topics": ["string"],
  "participants": [{ "userId": "uuid", "displayName": "string", "messageCount": 12 }]
}
```

Arrays are never null. Every `messageIds` is non-empty and a subset of the input window.
`provider` is `primary` or `fallback` when an LLM produced the summary, and `null` when
the local engine produced it.

Errors use the standard envelope: `400 VALIDATION_FAILED`, `400 TOO_FEW_MESSAGES` (fewer than 5 usable messages), `403 NOT_MEMBER`, `404` unknown conversation, `429 RATE_LIMITED`. The endpoint never answers `503`: provider trouble degrades to the local engine (`fallbackReason`: `NO_CONSENT | LLM_DISABLED | LLM_TIMEOUT | LLM_BAD_OUTPUT | LLM_HTTP_ERROR`).

Scopes: `UNREAD` = messages after the caller's `last_read_seq`; `LAST_N` = latest N; `TODAY` = since 00:00 UTC of the current day.

Limits: 10 requests/min and 60/day per user.

## Settings and consent

`GET /api/v1/users/me/settings` returns `showPresence` and `aiSummaryConsent` as booleans; `aiSummaryConsent` defaults to `false`. `PATCH` accepts either field independently; an omitted field keeps its current value, and an empty object is rejected.

Provider selection uses the persisted `users.ai_summary_consent` value. The LLM adapter checks that value again immediately before its outbound request, so consent revoked after provider selection still prevents the external call.

## Privacy

- Without the operator key or without the caller's consent no text leaves the server.
- With both, the server sends only usable message text, after redaction: phone numbers, emails, URL paths and queries, long digit strings, token-like strings; sender names become stable labels (`U1`, `U2`, ...). Message ids never leave the server (the model cites short indexes).
- Prompts and raw model output are not stored. Only the validated summary is cached for 24 hours; edits and deletes change the cache key.
- Eligible text from every participant in the window, including the requester, is sent on the requester's behalf. There is no per-conversation veto yet (see known limitations).

## Operator configuration

| Configuration key | Environment variable | Default |
|---|---|---|
| `nexachat.summary.provider` | `NEXACHAT_SUMMARY_PROVIDER` | `auto` |
| `nexachat.summary.llm.enabled` | `NEXACHAT_SUMMARY_LLM_ENABLED` | `false` |
| `nexachat.summary.llm.base-url` | `NEXACHAT_SUMMARY_LLM_BASE_URL` | `https://generativelanguage.googleapis.com/v1beta/openai/` |
| `nexachat.summary.llm.model` | `NEXACHAT_SUMMARY_LLM_MODEL` | `gemini-3.1-flash-lite` |
| `nexachat.summary.llm.api-key` | `NEXACHAT_SUMMARY_LLM_API_KEY` | — (absent ⇒ provider unavailable) |
| `nexachat.summary.llm.timeout-ms` | `NEXACHAT_SUMMARY_LLM_TIMEOUT_MS` | `30000` |
| `nexachat.summary.llm.max-input-chars` | `NEXACHAT_SUMMARY_LLM_MAX_INPUT_CHARS` | `24000` |
| `nexachat.summary.llm.max-output-tokens` | `NEXACHAT_SUMMARY_LLM_MAX_OUTPUT_TOKENS` | `4000` |
| `nexachat.summary.llm-fallback.enabled` | `NEXACHAT_SUMMARY_LLM_FALLBACK_ENABLED` | `false` |
| `nexachat.summary.llm-fallback.base-url` | `NEXACHAT_SUMMARY_LLM_FALLBACK_BASE_URL` | `https://api.deepseek.com` |
| `nexachat.summary.llm-fallback.model` | `NEXACHAT_SUMMARY_LLM_FALLBACK_MODEL` | `deepseek-v4.1-flash` |
| `nexachat.summary.llm-fallback.api-key` | `NEXACHAT_SUMMARY_LLM_FALLBACK_API_KEY` | — (absent ⇒ fallback unavailable) |
| `nexachat.summary.llm-fallback.timeout-ms` | `NEXACHAT_SUMMARY_LLM_FALLBACK_TIMEOUT_MS` | `30000` |

`provider` is `auto` (LLM only with a key and user consent), `local`, or `llm`. The base
URL is an OpenAI-compatible root; `/chat/completions` is appended. Plain `http://`
endpoints are rejected unless the host is loopback. The provider is OpenAI-compatible
(tested with Google Gemini `gemini-3.1-flash-lite` through the Gemini OpenAI-compatible
base URL `https://generativelanguage.googleapis.com/v1beta/openai/`, as well as standard
OpenAI-compatible endpoints supporting JSON mode `response_format: {"type":"json_object"}`).
Any compatible endpoint works by changing the base URL, model, and API key. The key
lives only in the gitignored `.env` and is never logged, serialized, or echoed in errors.

Safety behaviour:

- No outbound call is made unless the operator key is configured, the provider is
  enabled, and the requesting user has `aiSummaryConsent = true`. The provider
  re-checks the persisted consent immediately before the call.
- Message text is redacted before sending (phone numbers, emails, URL paths and
  queries, long digit strings, token-like strings); sender names become stable labels
  `U1..Un`; message ids never leave the server — the model cites short indexes and the
  server maps them back, dropping unknown indexes.
- The model output is schema-validated; items without at least one valid citation are
  dropped; markup and links are stripped; a language check is applied. The vendor's
  `reasoning_content` field is ignored and never stored or logged.
- Any HTTP error, timeout, parse failure, or validation failure falls back to the
  local engine with `fallbackReason` (`LLM_TIMEOUT | LLM_BAD_OUTPUT | LLM_HTTP_ERROR |
  LLM_DISABLED | NO_CONSENT`). The endpoint never answers `503`.
- One retry on 5xx within the timeout budget, none on 4xx. A circuit breaker opens for
  60 seconds after 5 consecutive failures. Response bodies are capped at 256 KiB.

## Cloud provider fallback

The primary cloud provider can be paired with a fallback provider under
`nexachat.summary.llm-fallback.*`. The fallback is disabled by default and
becomes usable only when its key is configured, so a deployment with an empty
fallback balance never calls it. It uses the same OpenAI-compatible client,
consent check, redaction, base-URL validation and output validation as the
primary, and it receives the same already-redacted prompt.

On a primary transport error, timeout, HTTP 5xx, HTTP 429 or an open circuit,
the fallback is tried once. A primary 4xx other than 429 (bad request, auth) is
not retried against the fallback: it would repeat the same bug, and the
provider error body is never leaked. If both providers fail, the request
degrades to the local engine exactly as before. The response `provider` field
is `primary` or `fallback` when an LLM answered, and `null` when the local
engine answered.

Limits: 10 requests/min and 60/day per user; the window is capped at 500 usable
messages; each message is truncated to 1,000 characters before redaction.

Verify the provider manually (owner-authorised, one call on a fixed synthetic
Vietnamese conversation of 30 messages):

```bash
tools/summary-eval/live-llm-smoke.sh      # or live-llm-smoke.ps1
```

The script loads `.env` into the process environment, runs the real adapter path
(`LlmClient` → `PromptBuilder` → `LlmOutputValidator`), and prints only status, latency,
token counts, the schema/citation validation verdict, and the number of items per
section. It is skipped unless `RUN_LIVE_LLM=1`.

## Hybrid Local-First Tiered Routing (Local Server Model + Cloud LLM)

NexaChat supports a 3-tier hybrid AI summary architecture designed to optimize privacy, latency, and cloud API token costs:

1. **Tier 0: Built-in Native MMR Engine (`LOCAL`)**
   - Pure Java extractive summarizer running directly in the backend process.
   - Zero external calls, zero API token spend, instant response.
   - Always used when the user has not consented (`aiSummaryConsent = false`) or as the ultimate offline fallback.

2. **Tier 1: Local Server Model Tier (`LOCAL_LLM` / Ollama / vLLM)**
   - Configured via `NEXACHAT_SUMMARY_LOCAL_LLM_*` (e.g. `http://host.docker.internal:11434/v1`, model `qwen2.5:3b` or `llama3.2:3b`).
   - Handles routine AI generative summaries on the server machine without burning cloud tokens or transmitting data outside your deployment infrastructure.

3. **Tier 2: Cloud LLM Tier (`LLM` / OpenAI-compatible, Gemini tested)**
   - Configured via `NEXACHAT_SUMMARY_LLM_*` (e.g. Gemini OpenAI-compatible base URL `https://generativelanguage.googleapis.com/v1beta/openai/`, model `gemini-3.1-flash-lite`).
   - Automatically escalated to when the summary task is complex or when the user explicitly triggers "Regenerate / Refresh" in AI mode.
   - Escalation trigger thresholds (configured via `NEXACHAT_SUMMARY_COMPLEXITY_*`):
     - Window message count $\ge 12$ messages (default `min-messages: 12`).
     - Window total length $\ge 1500$ characters (default `min-chars: 1500`).
     - Participant count $\ge 4$ active senders (default `min-participants: 4`).
     - Explicit user refresh (`request.refresh() == true`).

4. **Multi-Stage Graceful Fallback**
   - If Cloud LLM times out, encounters a network issue, or fails schema validation, the request automatically falls back to the Local Server Model (if configured).
   - If the Local Server Model is unreachable or fails, the request automatically falls back to the Native MMR Engine (`LOCAL`), guaranteeing the user always receives a valid, verified summary without an error banner.
