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

CI runs the same command in the `site-js-test` job. No secrets are involved.
