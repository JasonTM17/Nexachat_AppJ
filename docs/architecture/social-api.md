# Social web API v1

NexaChat Social v1 is a same-domain browser client for the existing public
feed. It is **not** a separate identity system, federation protocol, browser
chat product, or end-to-end-encrypted surface. The user signs in with the same
NexaChat password account (phone number or verified email) and the resulting
posts are visible to NexaChat accounts as server-readable plaintext.

The browser UI and proxy contract are configured in the repository, but the
production same-domain route is **not proven live until an authorized backend
and Vercel deployment rolls it out**. Do not treat examples here as evidence
that production is currently serving them.

## Canonical and legacy path map

The Spring `FeedController` exposes the same controller, service, security,
validation, rate-limit, moderation and block behavior under two class-level
prefixes:

| Canonical social path | Legacy feed path | Notes |
| --- | --- | --- |
| `/api/social/v1/posts` | `/api/v1/feed/posts` | GET newest-first keyset page; POST create |
| `/api/social/v1/posts/{postId}` | `/api/v1/feed/posts/{postId}` | GET visible post; author DELETE |
| `/api/social/v1/posts/{postId}/comments` | `/api/v1/feed/posts/{postId}/comments` | GET oldest-first page; POST create |
| `/api/social/v1/comments/{commentId}` | `/api/v1/feed/comments/{commentId}` | author DELETE |
| `/api/social/v1/posts/{postId}/reactions/{emoji}` | `/api/v1/feed/posts/{postId}/reactions/{emoji}` | PUT/DELETE idempotent reaction |
| `/api/social/v1/images` | `/api/v1/feed/images` | authenticated multipart upload |
| `/api/social/v1/images/{imageId}` | `/api/v1/feed/images/{imageId}` | authorized image bytes |

The response contract is intentionally unchanged. In particular, a post's
legacy `image.url` value remains `/api/v1/feed/images/{imageId}` even when the
post was read through the canonical social prefix. The web client accepts only
that exact legacy UUID path or the equivalent canonical `/api/social/v1/images/{uuid}`
path, converts it to the same-origin canonical `/images/{uuid}` request, and
never treats user content as a URL.

The same-origin Vercel proxy additionally maps session and report shortcuts;
these are proxy mappings, not extra Spring controller prefixes:

| Browser same-origin path | Backend path |
| --- | --- |
| `/api/social/v1/session/login` | `/api/v1/auth/login` |
| `/api/social/v1/session/refresh` | `/api/v1/auth/refresh` |
| `/api/social/v1/session/logout` | `/api/v1/auth/logout` |
| `/api/social/v1/reports` | `/api/v1/reports` |

The proxy deliberately does **not** expose a catch-all
`/api/social/v1/:path*` rewrite. It is limited to those auth/report routes and
the posts, comments and images subtrees. Browser requests use
`credentials: 'omit'`, `cache: 'no-store'`, `redirect: 'error'`, relative
same-origin URLs, and an explicit bearer only on authenticated calls. The
proxy adds `Cache-Control: private, no-store` plus CDN no-store headers to
`/api/social/v1/(.*)` so personalized canonical responses — especially images
that may become invisible after a block or moderation action — are not stored
by browser or CDN caches. The direct Spring image endpoint still has its
legacy private cache behavior for existing clients; canonical browser traffic
is protected at the same-origin proxy boundary.

## Methods and examples

All examples are relative to `https://nexachat.io.vn` once the authorized
deployment exists. JSON authenticated requests carry
`Authorization: Bearer <accessToken>`; credentials and tokens are never placed
in the URL.

### Sign in, refresh, sign out

```sh
curl -X POST https://nexachat.io.vn/api/social/v1/session/login \
  -H 'Content-Type: application/json' \
  -d '{"identifier":"user@example.com","password":"<password>"}'
```

`identifier` may be a phone number or verified email. The response is the
existing `AuthResponse`: user display identity, `accessToken`,
`accessTokenExpiresIn`, `refreshToken`, and client policy fields. The web
client keeps both tokens in module memory only.

```sh
curl -X POST https://nexachat.io.vn/api/social/v1/session/refresh \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: <uuid>' \
  -d '{"refreshToken":"<refresh-token>"}'
```

The browser uses one in-flight refresh, a refresh idempotency key, and replays
a request once after a 401. Concurrent or delayed 401s compare the captured
access token before refreshing again. If refresh transport/response outcome is
uncertain, the client fails closed and requires sign-in rather than retrying a
potentially rotated refresh token under a new idempotency key; a second 401 on
the replayed resource also expires the local session instead of looping.

```sh
curl -X POST https://nexachat.io.vn/api/social/v1/session/logout \
  -H "Authorization: Bearer <access-token>" \
  -H 'Content-Type: application/json' \
  -d '{"refreshToken":"<refresh-token>"}'
```

Browser logout clears local session state immediately and does not refresh the
access token first. If server revocation is not confirmed (including an invalid
access token or network failure), the UI says local sign-out happened but server
revocation was not confirmed.

### Posts

```sh
curl 'https://nexachat.io.vn/api/social/v1/posts?limit=20&cursor=<opaque-cursor>' \
  -H "Authorization: Bearer <access-token>"
```

Returns `{ "items": [PostResponse], "nextCursor": string|null }`, newest first.
`nextCursor` is opaque and must be URL-encoded by clients.

```sh
curl -X POST https://nexachat.io.vn/api/social/v1/posts \
  -H "Authorization: Bearer <access-token>" \
  -H 'Content-Type: application/json' \
  -d '{"body":"hello","clientPostId":"<uuid>"}'
```

`clientPostId` is the durable create key. The first successful create returns
`201`; replaying the same key returns `200` with the same stored row. The
browser generates one UUID per post draft and keeps it when the user retries an
identical draft after an uncertain network failure; it does not auto-retry
server errors.

### Comments

```sh
curl 'https://nexachat.io.vn/api/social/v1/posts/<post-id>/comments?limit=20&cursor=<opaque-cursor>' \
  -H "Authorization: Bearer <access-token>"

curl -X POST https://nexachat.io.vn/api/social/v1/posts/<post-id>/comments \
  -H "Authorization: Bearer <access-token>" \
  -H 'Content-Type: application/json' \
  -d '{"body":"first","clientCommentId":"<uuid>"}'
```

Comment bodies are limited to 500 characters. `clientCommentId` has the same
durable replay semantics as `clientPostId`; the browser keeps a separate stable
UUID per comment draft.

### Reactions and deletes

```sh
curl -X PUT 'https://nexachat.io.vn/api/social/v1/posts/<post-id>/reactions/%E2%9D%A4%EF%B8%8F' \
  -H "Authorization: Bearer <access-token>"
curl -X DELETE 'https://nexachat.io.vn/api/social/v1/posts/<post-id>/reactions/%E2%9D%A4%EF%B8%8F' \
  -H "Authorization: Bearer <access-token>"
curl -X DELETE https://nexachat.io.vn/api/social/v1/posts/<post-id> \
  -H "Authorization: Bearer <access-token>"
curl -X DELETE https://nexachat.io.vn/api/social/v1/comments/<comment-id> \
  -H "Authorization: Bearer <access-token>"
```

Emoji path segments must be percent-encoded. PUT and DELETE are idempotent and
return the post's aggregate reactions. Only authors see delete affordances; the
server remains the authority.

### Reports

```sh
curl -X POST https://nexachat.io.vn/api/social/v1/reports \
  -H "Authorization: Bearer <access-token>" \
  -H 'Content-Type: application/json' \
  -d '{"targetType":"POST","targetId":"<uuid>","reason":"SPAM"}'
```

`targetType` is `POST` or `COMMENT` for this UI. Existing reason choices are
`SPAM`, `HARASSMENT`, `VIOLENCE`, `SEXUAL`, `SCAM`, and `OTHER`. A duplicate
open report by the same reporter returns `200` with the existing row instead of
creating another report.

### Images

The backend accepts JPEG, PNG and WebP feed images. Browser v1 does not expose
a new upload control; upload remains deferred/optional. Posts that reference
previously uploaded images are rendered by an authorized blob request:

```sh
curl https://nexachat.io.vn/api/social/v1/images/<image-id> \
  -H "Authorization: Bearer <access-token>"
```

The browser validates the image path shape before fetching, accepts only the
backend-supported image MIME types, creates a local object URL, and revokes it
when the post or feed is cleared. It does not reuse author avatars as image
sources and does not fetch third-party or storage-key URLs.

## Compatibility and rollout

- Legacy Android and other existing clients continue using `/api/v1/feed`;
  their response shape and image URLs do not change.
- The canonical Spring mapping can ship backend-first with no database schema
  or migration change. Older clients are unaffected because the mapping is
  additive.
- The static site can then ship the restricted proxy and `/social/` page.
- Production activation requires the normal authorized backend deploy and
  Vercel deploy; this document does not claim either has happened.
- Prelaunch smoke after an authorized deploy must verify on the real Vercel route
  that exact base and child paths forward upstream, bearer/auth behavior is
  preserved, authenticated responses stay private, and `Cache-Control`,
  `CDN-Cache-Control`, and `Vercel-CDN-Cache-Control` are no-store. The local
  fixture cannot prove those deployment properties.
- If a client depends on direct Spring canonical image caching, note that only
  the same-origin proxy currently adds CDN/browser no-store headers. Direct
  backend image behavior remains the legacy private-cache contract.
- Social remains public feed behavior: visible to NexaChat accounts, not
  end-to-end encrypted, subject to blocks, moderation, retention, validation
  and per-user rate limits exactly as implemented by the shared controller.

## Browser UI provenance and boundaries

The `/social/` visual language is hand-built from `docs/design/stitch/DESIGN.md`
and `docs/design/design-notes.md`, with colors overridden by the shipped Android
`colors_nexachat.xml` light/night tokens and call/feed layout constraints. A
late optional Stitch reference image only confirmed the compact composer-first
layout and quiet audience panel; its invented channels/DMs/voice rooms/QR and
generated HTML were not used. No external design provider, font CDN, script,
image or runtime service is used at runtime.
The “open chat” affordance is an honest link to the Android app/download path;
there is no browser messaging claim.
