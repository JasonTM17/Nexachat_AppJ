# Conversation catch-up summary

Status: server shipped (route, engines, consent, per-conversation opt-out V13, Flyway V6/V13); Android summary UI and per-conversation opt-out switch shipped.

## What it does

A member requests a structured summary of a conversation window: overview, key points, decisions, action items, open questions, topics, participants. Every item cites source messages (`messageIds`), so the client can jump to them.

Two engines:

| Engine | `generatedBy` | Network | When |
|---|---|---|---|
| Local extractive (heuristic, deterministic) | `LOCAL` | none | default, always available |
| External LLM (OpenAI-compatible endpoint) | `LLM` | outbound HTTPS (cloud) or HTTP to the configured local-server host | only if the operator configured a usable cloud key or the local-server model **and** the requesting user opted in; any failure falls back to local |

The local engine selects and labels existing messages; it does not generate new prose.

## API

`POST /api/v1/conversations/{id}/summary` (members only)

```json
{ "scope": "UNREAD|LAST_N|TODAY", "limit": 50, "language": "AUTO|VI|EN", "refresh": false }
```

`limit` applies to `LAST_N` (5..500). The window is capped at the latest 500 usable messages: `TEXT`, non-empty, not deleted, without attachments, not system; each message is truncated to 1,000 characters.

`refresh: true` skips a valid cache read, regenerates the summary, and replaces the cache entry for the provider that produced the result. Refresh requests still count toward both rate limits.

Response `200`:

```json
{
  "summaryId": "uuid", "conversationId": "uuid",
  "fromSeq": 120, "toSeq": 187, "messageCount": 64,
  "language": "VI", "generatedBy": "LOCAL", "model": null,
  "provider": null, "fallbackReason": null, "cached": false, "contextOmitted": false,
  "createdAt": "2026-11-05T12:00:00Z",
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
`provider` is `primary` when the primary LLM target answered, `fallback` when the
configured fallback target answered, and `null` when the local engine or a
secondary LLM target answered. `contextOmitted` is `true` only when the returned
summary was produced by the external LLM and at least one message in the window
was excluded because its author opted out in this conversation; it never
identifies the member. Local summaries report `false` because the local engine
may still use those messages.

Errors use the standard envelope: `400 VALIDATION_FAILED`, `400 TOO_FEW_MESSAGES` (fewer than 5 usable messages), `403 NOT_MEMBER`, `404` unknown conversation, `429 RATE_LIMITED`. The endpoint never answers `503`: provider trouble degrades to the local engine (`fallbackReason`: `NO_CONSENT | LLM_DISABLED | LLM_TIMEOUT | LLM_BAD_OUTPUT | LLM_HTTP_ERROR | NO_EXTERNAL_CONTEXT`).

Scopes: `UNREAD` = messages after the caller's `last_read_seq`; `LAST_N` = latest N; `TODAY` = since 00:00 UTC of the current day.

Limits: 10 requests/min and 60/day per user.

## Settings and consent

`GET /api/v1/users/me/settings` returns `showPresence`, `showSeen` and `aiSummaryConsent` as booleans; `showPresence` and `showSeen` default to `true`, and `aiSummaryConsent` defaults to `false`. `PATCH` accepts any of the three fields independently; an omitted field keeps its current value, and an empty object is rejected.

Provider selection uses the persisted `users.ai_summary_consent` value. The LLM adapter checks that value again immediately before its outbound request, so consent revoked after provider selection still prevents the external call.

**Per-conversation opt-out.** Any member can stop their own messages from
being summarized by an external LLM in one conversation:

```
PUT    /api/v1/conversations/{id}/ai-opt-out   -> 200 {"conversationId": "...", "aiOptOut": true}
DELETE /api/v1/conversations/{id}/ai-opt-out   -> 200 {"conversationId": "...", "aiOptOut": false}
GET    /api/v1/conversations/{id}/ai-opt-out   -> 200 {"conversationId": "...", "aiOptOut": <bool>}
```

The endpoints are member-only and idempotent, and the state is always scoped to
the caller; another member's choice is never exposed. The LLM provider re-reads
the opt-out rows and the caller's persisted consent immediately before building
the external prompt, so a change committed after the request started still
applies. The external input excludes messages authored by opted-out members;
the built-in local extractive engine may still quote them because no text
leaves the server. An external summary cache entry that cites an opted-out
member's message (or lists that member as a participant) is deleted and
regenerated instead of being replayed. If every message in the window is
excluded, the LLM is not called and the local engine answers with
`fallbackReason: NO_EXTERNAL_CONTEXT`.

## Privacy

- Without a usable external LLM tier configured (a cloud key or an enabled local-server model) or without the caller's consent, no text leaves the server.
- With both, the server sends only usable message text, after redaction: phone numbers, emails, URL paths and queries, long digit strings, token-like strings; sender names become stable labels (`U1`, `U2`, ...). Message ids never leave the server (the model cites short indexes).
- Eligible text from every participant in the window, including the requester, is sent on the requester's behalf, except text authored by a member who opted out in that conversation. The cloud primary, cloud fallback and the local server model all receive the filtered window; only the built-in local extractive engine may still use the opted-out messages.
- Prompts and raw model output are not stored. Only the validated summary is cached for 24 hours; edits, deletes and opt-out changes change the cache key or invalidate the cached entry.

## Operator configuration

| Configuration key | Environment variable | Default |
|---|---|---|
| `nexachat.summary.provider` | `NEXACHAT_SUMMARY_PROVIDER` | `auto` |
| `nexachat.summary.complexity.min-messages` | `NEXACHAT_SUMMARY_COMPLEXITY_MIN_MESSAGES` | `12` |
| `nexachat.summary.complexity.min-chars` | `NEXACHAT_SUMMARY_COMPLEXITY_MIN_CHARS` | `1500` |
| `nexachat.summary.complexity.min-participants` | `NEXACHAT_SUMMARY_COMPLEXITY_MIN_PARTICIPANTS` | `4` |
| `nexachat.summary.llm.enabled` | `NEXACHAT_SUMMARY_LLM_ENABLED` | `false` |
| `nexachat.summary.llm.base-url` | `NEXACHAT_SUMMARY_LLM_BASE_URL` | `https://generativelanguage.googleapis.com/v1beta/openai` |
| `nexachat.summary.llm.model` | `NEXACHAT_SUMMARY_LLM_MODEL` | `gemini-3.1-flash-lite` |
| `nexachat.summary.llm.api-key` | `NEXACHAT_SUMMARY_LLM_API_KEY` | — (absent ⇒ provider unavailable) |
| `nexachat.summary.llm.timeout-ms` | `NEXACHAT_SUMMARY_LLM_TIMEOUT_MS` | `30000` |
| `nexachat.summary.llm.max-input-chars` | `NEXACHAT_SUMMARY_LLM_MAX_INPUT_CHARS` | `24000` |
| `nexachat.summary.llm.max-output-tokens` | `NEXACHAT_SUMMARY_LLM_MAX_OUTPUT_TOKENS` | `4000` |
| `nexachat.summary.llm-fallback.enabled` | `NEXACHAT_SUMMARY_LLM_FALLBACK_ENABLED` | `false` |
| `nexachat.summary.llm-fallback.base-url` | `NEXACHAT_SUMMARY_LLM_FALLBACK_BASE_URL` | `https://api.deepseek.com` |
| `nexachat.summary.llm-fallback.model` | `NEXACHAT_SUMMARY_LLM_FALLBACK_MODEL` | `deepseek-flash` |
| `nexachat.summary.llm-fallback.api-key` | `NEXACHAT_SUMMARY_LLM_FALLBACK_API_KEY` | — (absent ⇒ fallback unavailable) |
| `nexachat.summary.llm-fallback.timeout-ms` | `NEXACHAT_SUMMARY_LLM_FALLBACK_TIMEOUT_MS` | `30000` |
| `nexachat.summary.llm.local-enabled` | `NEXACHAT_SUMMARY_LOCAL_LLM_ENABLED` | `false` |
| `nexachat.summary.llm.local-base-url` | `NEXACHAT_SUMMARY_LOCAL_LLM_BASE_URL` | `http://host.docker.internal:11434/v1` |
| `nexachat.summary.llm.local-model` | `NEXACHAT_SUMMARY_LOCAL_LLM_MODEL` | `qwen2.5:3b` |
| `nexachat.summary.llm.local-api-key` | `NEXACHAT_SUMMARY_LOCAL_LLM_API_KEY` | `local-ollama` |
| `nexachat.summary.llm.local-timeout-ms` | `NEXACHAT_SUMMARY_LOCAL_LLM_TIMEOUT_MS` | `60000` |

`provider` is `auto` (LLM only with a usable cloud key or local-server model, and user consent), `local`, or `llm`. The base
URL is an OpenAI-compatible root; `/chat/completions` is appended. Plain `http://`
endpoints are rejected unless the host is loopback; the local-server tier allows
plain HTTP to its configured host. The provider is OpenAI-compatible
(tested with Google Gemini `gemini-3.1-flash-lite` through the Gemini OpenAI-compatible
base URL `https://generativelanguage.googleapis.com/v1beta/openai/`, as well as standard
OpenAI-compatible endpoints supporting JSON mode `response_format: {"type":"json_object"}`).
Any compatible endpoint works by changing the base URL, model, and API key. The key
lives only in the gitignored `.env` and is never logged, serialized, or echoed in errors.

Safety behaviour:

- No outbound call is made unless a usable cloud key or enabled local-server model
  is configured, the relevant tier is enabled, and the requesting user has
  `aiSummaryConsent = true`. The provider re-checks the persisted consent
  immediately before the call. The local-server tier sends the same filtered prompt
  to its configured `nexachat.summary.llm.local-base-url`, which must stay inside
  the deployment's trust boundary.
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
becomes usable only when `nexachat.summary.llm-fallback.enabled=true` and its
key is configured, so a deployment with an empty fallback balance never calls
it. It uses the same OpenAI-compatible client,
consent check, opt-out filtering, redaction, base-URL validation and output
validation as the primary, and it receives the same already-filtered,
already-redacted prompt.

On a primary transport error, timeout, HTTP 5xx, HTTP 429 or an open circuit,
the fallback is tried once. A primary 4xx other than 429 (bad request, auth) is
not retried against the fallback: it would repeat the same bug, and the
provider error body is never leaked. If both providers fail, the request
degrades to the local engine exactly as before. The response `provider` field
is `primary` when the primary LLM target answered, `fallback` when the configured
fallback target answered, and `null` when the local engine or a secondary LLM
target answered.

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

2. **Tier 1: Local Server Model Tier (Ollama / vLLM)**
   - Configured via `NEXACHAT_SUMMARY_LOCAL_LLM_*` (e.g. `http://host.docker.internal:11434/v1`, model `qwen2.5:3b` or `llama3.2:3b`).
   - Handles routine AI generative summaries on the server machine without burning cloud tokens or transmitting data outside your deployment infrastructure.
   - Both LLM tiers report `generatedBy: LLM`; the response `model` field is the model identifier of the target that answered.

3. **Tier 2: Cloud LLM Tier (`LLM` / OpenAI-compatible, Gemini tested)**
   - Configured via `NEXACHAT_SUMMARY_LLM_*` (e.g. Gemini OpenAI-compatible base URL `https://generativelanguage.googleapis.com/v1beta/openai/`, model `gemini-3.1-flash-lite`).
   - Preferred when the summary task is complex; an explicit "Regenerate / Refresh"
     keeps the request on an LLM tier rather than the built-in local engine. Refresh
     never selects cloud over the local-server model.
   - Escalation trigger thresholds (configured via `NEXACHAT_SUMMARY_COMPLEXITY_*`):
     - Window message count $\ge 12$ messages (default `min-messages: 12`).
     - Window total length $\ge 1500$ characters (default `min-chars: 1500`).
     - Participant count $\ge 4$ active senders (default `min-participants: 4`).

4. **Multi-Stage Graceful Fallback**
   When both the cloud and local-server tiers are usable, the order is:
   - complex window: cloud primary → configured cloud fallback
     (`nexachat.summary.llm-fallback.*`, retryable failures only) → local-server
     model → built-in `LOCAL`;
   - routine window with the local-server model enabled: local-server model →
     configured cloud fallback (retryable failures only) → cloud primary →
     built-in `LOCAL`.

   The cloud fallback is tried once on a retryable primary failure (timeout,
   transport error, HTTP 5xx/429) or when the primary circuit is open; a
   non-retryable primary 4xx goes straight to the secondary target. If every LLM
   tier fails, the built-in `LOCAL` engine answers with a `fallbackReason`, so the
   user still receives a validated summary without an error banner.
