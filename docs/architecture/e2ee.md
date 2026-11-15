# End-to-end encryption (v1)

E2EE direct chats keep message plaintext on the devices only. The server
stores public identity keys and opaque ciphertext; it never decrypts and never
sees plaintext for rows where `messages.encryption IS NOT NULL`.

## Trust model

- v1 protects stored traffic against *passive* database exposure: rows hold
  `e2ee1:` ciphertext and wrapped content keys, so reading the database alone
  does not reveal plaintext. The scheme has **no independent cryptographic
  audit or production certification**.
- v1 still *trusts the authenticated server* for key-directory truth, sender
  identity, and conversation toggle state. There are no sender signatures,
  no persistent key pinning or verification, no key-change alerts, and no
  ratchet. An actively malicious or compromised server could substitute
  device keys, forge sender metadata, or downgrade the conversation flag.
- Comparing the safety code is a *manual* out-of-band check, not enforced
  verification: clients can compare the code (SHA-512 over sorted
  `userId:deviceId:publicKey` lines of both members' active devices), but
  nothing blocks a send when the code is never compared. Matching codes do
  **not** authenticate device ownership: both clients hash a wholly
  server-supplied directory, so an active malicious server can serve both
  members the same manipulated list and matching codes. No independent
  audit is claimed.
- Device identity is per installation: an X25519 key pair whose private half
  never leaves the client (Android seals it with the Keystore-backed
  `AesGcmTokenCipher`; it is wiped on sign-out). `e2ee_devices` only stores
  `public_key` (32 bytes) plus `revoked_at`.

## Crypto scheme (client-side, Tink `subtle`)

Per message:

1. `contentKey` = 32 random bytes; `nonce` = 12 random bytes.
2. `ct` = AES-256-GCM(contentKey, nonce, utf8(text), aad) where
   `aad = "nexachat-e2ee-v1|" + conversationId + "|" + senderId + "|" + clientMessageId`.
3. `eph` = fresh X25519 pair. For each active member device `d` (including the
   sender's own devices):
   `kek = HKDF-SHA256(X25519(eph.priv, d.pub), salt = utf8(clientMessageId),
   info = "nexachat-e2ee-v1-kek|" + d.deviceId, len = 32)` and
   `wrapped = AES-256-GCM(kek, random nonce, contentKey, aad)` → `nonce || ct`.
4. Wire `content` = `"e2ee1:" + base64url-nopad(eph.pub(32) || nonce(12) || ct)`.
5. `e2eeEnvelopes` = `[{deviceId, wrappedKey: base64url-nopad(nonce||ct)}]`.

Decrypt: find the envelope for your own deviceId, derive `kek` from your
private key and `eph.pub`, unwrap the content key, decrypt. Any failure
renders a "can't decrypt" placeholder — never a crash, never a plaintext
fallback.

## Server surface

Experimental status: v1 is a rollout-gated preview, not a certified feature.
`nexachat.e2ee.v1-activation-enabled`
(`NEXACHAT_E2EE_V1_ACTIVATION_ENABLED`, default `false`) controls whether a
conversation may newly turn encryption on. The gate is deliberately narrow:
it rejects only false→true transitions with `E2EE_V1_ACTIVATION_DISABLED`
before any readiness check or write. It is **not** a kill switch — already
enabled conversations keep sending, reading and turning off, device
registration and encrypted sends are unaffected, and nothing is
automatically rewritten when the flag flips. On Android, the compiled
`BuildConfig.E2EE_V1_ACTIVATION_ALLOWED` must also be true: it compiles
`false` in every variant by default, with an explicit opt-in for
`debug`/`localSmoke` via `-Pe2eeV1ActivationEnabled=true`, and stays
`false` in release even when the property is passed.

- `PUT /api/v1/e2ee/devices/{deviceId}` — idempotent registration of a
  base64url 32-byte public key; 409 `E2EE_DEVICE_CONFLICT` on id/owner/key
  mismatch or reuse of a revoked id; 409 `E2EE_DEVICE_LIMIT` beyond 10 active
  devices; rate limited. `DELETE` revokes (own device only, idempotent).
  Logout-all and account deletion revoke every device of the user.
- `GET|PUT /api/v1/conversations/{id}/e2ee` — DIRECT only. GET returns
  `{enabled, devices, experimentalV1ActivationAllowed}` (active devices of
  all members, plus the server rollout capability). PUT toggles; enabling
  requires every member to own an active device (`E2EE_PEER_NOT_READY`), is
  a no-op when unchanged, and otherwise posts a SYSTEM line plus the
  regular `CONVERSATION_UPDATED` snapshot. New false→true activation is
  additionally gated by the experimental rollout flag and returns 409
  `E2EE_V1_ACTIVATION_DISABLED` while it is off; turning encryption off
  never consults the gate.
- `POST /conversations/{id}/messages` with `encryption: "E2EE_V1"` and
  `e2eeEnvelopes` (1–20 unique entries). The envelope device set must EQUAL
  the active devices of all current members — including every device of the
  sender, which wraps a content key for itself — else `E2EE_STALE_DEVICES`.
  Before the conversation row lock, the send takes `FOR NO KEY UPDATE` on
  every member's `users` row in UUID order — the same lock registration
  holds for its whole transaction (`FOR KEY SHARE` cannot substitute:
  PostgreSQL does not conflict it with `FOR NO KEY UPDATE`). A device
  enrolled concurrently therefore either commits before the send's locks
  (and must be covered) or waits for the send to finish; a client on a
  stale snapshot refetches the directory and re-encrypts on this error. Enabled conversations require the field
  (`E2EE_REQUIRED`), accept TEXT only (`E2EE_MEDIA_UNSUPPORTED`), and reject
  mentions. Idempotent replay returns the stored row unchanged.
- `MessageResponse` carries `encryption` and `e2eeEnvelopes` on every read
  surface (history, sync delta, realtime broadcast, send result).
- `InboxEntry` carries `e2eeEnabled` and `lastMessageEncrypted` (snippet
  suppressed); `ReplyRefResponse` carries `encrypted` with `content: null`.

## What the server deliberately does not see

Encrypted rows are excluded from: message search, archive extraction, summary
windows, RAG indexing/retrieval (both endpoints return `E2EE_AI_UNAVAILABLE`
on enabled conversations), inbox snippets, and quote/pin previews. Push
payloads contain only ids and the sender name — never ciphertext or
plaintext. Server logs mask `content` fields regardless.

## Limits (v1)

- No forward secrecy or double ratchet: a leaked device private key exposes
  messages addressed to it.
- No encrypted media: attachments, voice, and polls are refused while E2EE is
  on.
- No history for devices added later; no key backup.
- No encrypted edits (`E2EE_EDIT_UNSUPPORTED`); delete/recall still works.
- DIRECT conversations only; groups are rejected
  (`E2EE_UNSUPPORTED_CONVERSATION`, enforced by a DB CHECK).
