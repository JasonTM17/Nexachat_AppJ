# Site script tests

Behaviour tests for the JavaScript served from `site/`. They use Node's built-in
test runner and a small in-repo DOM shim, so they need no packages, network,
browser, emulator or server.

```sh
cd tools/site-tests
npm test
```

Current coverage: `site/delete-account.js` (validation, OTP step flow, error and
rate-limit messages, focus/busy handling, phone-change reset, and the rule that
the phone number and one-time code never appear in a request URL). The tests
also check that every element id used by the script exists in
`site/delete-account.html`, so fixture drift fails red.

`social-client.test.mjs` covers the Social v1 auth/session transport with a
stubbed `fetch`: same-origin relative URLs, no bearer on login, no durable token
storage, one shared refresh for simultaneous and delayed 401 responses, exactly
one replay per request, stable JSON idempotency keys across an auth replay,
logout during a deferred refresh preventing resurrection, fail-closed ambiguous
refresh transport failure, response bodies rejected after logout, no-JS login
form credential serialization prevention, hidden-state CSS precedence, and
rejection of foreign or malformed image/request paths. These are transport and
static-contract tests only; they make no production availability claim.

CI runs the same command in the `site-js-test` job. No secrets are involved.
