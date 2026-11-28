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
| `/api/social/v1/videos` | `/api/v1/feed/videos` | authenticated multipart upload |
| `/api/social/v1/videos/{videoId}` | `/api/v1/feed/videos/{videoId}` | authorized video bytes |

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
the posts, comments, images and videos subtrees. Browser requests use
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

The backend accepts JPEG, PNG and WebP feed images, one attachment per post.

```sh
curl -X POST https://nexachat.io.vn/api/social/v1/images \
  -H "Authorization: Bearer <access-token>" \
  -F "file=@photo.jpg;type=image/jpeg"
```

Posts that reference uploaded images are rendered by an authorized blob request:

```sh
curl https://nexachat.io.vn/api/social/v1/images/<image-id> \
  -H "Authorization: Bearer <access-token>"
```

The browser validates the image path shape before fetching, accepts only the
backend-supported image MIME types, creates a local object URL, and revokes it
when the post or feed is cleared. It does not reuse author avatars as image
sources and does not fetch third-party or storage-key URLs.

### Videos

One video per post, in the same storage slot and visibility/retention model as
images. Uploads are capped at 4 MiB (well below the storage ceiling) and the
server validates the actual bytes structurally: the ISO-BMFF box tree must
contain a complete `moov` box with at least one video track
(`MagicBytes.hasMp4VideoTrack`). This is structural identification, not a
codec scan — an audio-only MP4 or a bare `ftyp` brand is rejected, but a
passing file is not guaranteed to use H.264/AAC or to play on every device.
There is no transcoding, thumbnailing, or adaptive bitrate in this slice. The
client filename and declared MIME are never trusted. The 4 MiB cap is a byte
cap, not a duration limit — clip length depends on the encoding. Timed
subtitle/text tracks are not supported: the structural check only requires a
video track and the player renders no captions track. Image and video uploads
share the same admission, per-minute/per-day and unbound quotas, so selecting
another type is never a bypass.

```sh
curl -X POST https://nexachat.io.vn/api/social/v1/videos \
  -H "Authorization: Bearer <access-token>" \
  -F "file=@clip.mp4;type=video/mp4"

curl -X POST https://nexachat.io.vn/api/social/v1/posts \
  -H "Authorization: Bearer <access-token>" \
  -H 'Content-Type: application/json' \
  -d '{"body":"a caption","videoId":"<uuid>","clientPostId":"<uuid>"}'

curl https://nexachat.io.vn/api/social/v1/videos/<video-id> \
  -H "Authorization: Bearer <access-token>"
```

A video post **requires a non-blank caption** and cannot also carry an image,
so older Android builds without video support still show useful text (they see
the caption; the legacy `image` field stays `null` and the video appears only
in the additive `video` field, which is omitted on non-video posts).
`GET /videos/{id}` serves the bytes only under the same feed visibility rule
as images, always as `video/mp4` with `Cache-Control: no-store` + private,
`nosniff` and an exact Content-Length. The image and video routes are mutually
exclusive: an image id on the video route (and vice versa) is a 404. Reads of
the raw storage key through `/api/v1/files/**` stay behind the same feed
guard.

The browser composer accepts JPEG/PNG/WebP/MP4 up to 4 MiB (the image cap is a
conservative client cap; the server image contract is unchanged). Upload and
publication are separate honest phases ('Uploading…', then 'Posting…') with no
fake progress. A confirmed upload is reused when publishing fails and the user
retries, so bytes are not re-uploaded; an ambiguous upload failure may be
retried and the unbound duplicate is swept by retention. Feed rendering never
auto-downloads video bytes: the video is fetched as an authenticated blob only
after the reader clicks 'Load video', is validated as `video/mp4`, and plays
in a controls-enabled, playsInline, non-autoplaying `<video>` whose object URL
is revoked on sign-out, refresh, or post removal. Native Android posting and
playback are not part of this slice; older builds see the caption only.

Before opening video to production, the rollout still needs exact-SHA CI,
the deployment/proxy smoke above, device validation, and capacity
observation. Larger future uploads belong behind resumable private ingest with
isolated validation/transcoding workers, never on chat request threads.

### Staff preview of reported videos

`POST /api/v1/admin/feed/reports/{reportId}/video` gives staff an on-demand
preview of the video attached to a *reported post* for triage. It is not a
public or general-purpose media route:

- Auth: the `/api/v1/admin/**` filter-chain staff gate (`ADMIN` or
  `MODERATOR`) plus a per-staff-member rate limit (20/min). Plain users get
  403, anonymous 401. Role changes propagate through the access-token
  validity cache rather than instantly, so a demoted staff member's existing
  bearer dies at latest with the cache TTL or token expiry — the same
  revocation model as every other admin route, not a preview-specific one.
- Eligibility: the report must target a `POST` whose single media slot holds
  a bound, undeleted MP4 within the 4 MiB cap, joined by the exact same
  `POST_VIDEO_JOIN` predicate the report queue uses for its `targetHasVideo`
  flag. Hidden and already-reviewed reports stay eligible (triage often needs
  the video exactly then); a post the author deleted, a comment report, and
  any unknown report id each answer `404 FILE_NOT_FOUND` with no audit row.
  There is no arbitrary key or UUID input: the storage key is resolved
  server-side from the report id alone.
- Audit: one `FEED_REPORT_VIDEO_VIEW` row is written in the same transaction
  before the key leaves the service — the audit row is the preview grant, so
  a failed audit yields no bytes. The row proves the bytes were released to a
  staff member, not that a human actually watched them.
- Response: exact stored bytes as `video/mp4` with `Cache-Control: no-store`
  private, `nosniff`, and exact Content-Length, read with a bounded prefix
  read (`MAX_VIDEO_BYTES`) after authorization. Storage keys never appear in
  the response. The admin console fetches it click-to-load only, exactly like
  the public player.

STRIDE notes for this boundary: spoofing is bounded by the staff role check
plus fresh-per-request role resolution; tampering with which video is served
is impossible because the key comes only from the eligibility join, not the
request; repudiation is covered by the same-transaction audit row (with the
residual that audit proves release, not viewing); information disclosure is
limited to staff who already moderate the report queue, with no-store
responses and no key leakage — the residual is that bytes legitimately sit in
staff browser memory like any moderation content; denial of service is capped
by the per-staff rate limit and the 4 MiB bounded read; elevation of privilege
is blocked at both the filter chain and `AdminGuard.requireStaff`, with no
path for a plain reporter to reach it.

The admin queue flag `targetHasVideo` is `true` only when that same
eligibility join matches, so staff never see a preview button that cannot
resolve.

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
