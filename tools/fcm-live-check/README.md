# FCM live check

Proves that the NexaChat server really authenticates against Google and that the
push path works, using the user's Firebase project. These are the only checks in
the repository that touch the live Google API; everything else runs against
local fakes.

## Prerequisites

- Python 3 with `cryptography` (`python -m pip install cryptography`).
- `FCM_SERVICE_ACCOUNT_JSON` set in the environment (inline service-account JSON
  or a path to it), plus `FCM_PROJECT_ID`.
- `server-e2e` additionally needs the running containers: `nexachat-app-1`
  (with `FCM_ENABLED=true`) and `nexachat-db-1`.

## Secret rules

The service-account JSON is read from the environment only. The tool:

- refuses to run (exit code 2) when `FCM_SERVICE_ACCOUNT_JSON` is missing;
- never prints key material, the account email, the key id, an access token or
  a device registration token;
- never places a secret on a command line;
- prints only booleans, HTTP status codes, Google error status words, lengths
  and lifetimes.

`server-e2e` generates its own synthetic device token and stores only a SHA-256
fingerprint of it in the fixture file.

## Commands

| command | network | what it proves |
| --- | --- | --- |
| `check-credential` | none | The JSON parses, `type` is `service_account`, `project_id` matches `FCM_PROJECT_ID`, and the RSA key is PKCS#8. Prints five booleans. |
| `oauth-exchange` | Google token endpoint | A signed RS256 JWT assertion is exchanged for an access token with the `firebase.messaging` scope. Prints `ok`, `http_status`, `lifetime_seconds`, or Google's error status word. |
| `validate-send` | Google FCM endpoint | `messages:send` with `validate_only=true` and a synthetic token. Prints the HTTP status class and the nested `error.details[].errorCode`. Exits 0 only for the shape-specific expected rejection: 404 `UNREGISTERED` for `synthetic`, 400 `INVALID_ARGUMENT` for `malformed`. |
| `self-test` | none | Proves the response classification: the expected rejections pass; 5xx, 401/403, 429 and 2xx fail. |
| `server-e2e` | local server API + Docker logs | Registers two fixture users, registers a synthetic device token for the recipient, sends a message, then confirms the gateway reached Google, Google rejected the token, the token row was removed, and no secret was logged. |

```sh
python tools/fcm-live-check/fcm_live_check.py check-credential
python tools/fcm-live-check/fcm_live_check.py oauth-exchange
python tools/fcm-live-check/fcm_live_check.py validate-send
python tools/fcm-live-check/fcm_live_check.py validate-send --token-shape malformed
python tools/fcm-live-check/fcm_live_check.py self-test
python tools/fcm-live-check/fcm_live_check.py server-e2e --base-url http://localhost:8080
```

`server-e2e` writes `p1-longcat-fcmlive-fixtures.json` into the fixture
directory (`--fixture-dir`, default `tools/fcm-live-check/.out/`, created on
demand). That directory is git-ignored because the fixture holds throwaway
account credentials. The fixture users are throwaway accounts on the local
server in the reserved `+84908` test range; they are not deleted because the
public API has no account-deletion endpoint.

## Interpreting failures

- `oauth-exchange` fails with `error_status=PERMISSION_DENIED` → the service
  account is not a member of the Firebase project, or lacks a role that can mint
  FCM tokens. In the Google Cloud console, IAM & Admin → IAM → find the service
  account and grant a product-scoped role such as **Firebase Cloud Messaging
  API Admin**, or a custom role containing `cloudmessaging.messages.create`
  (the FCM HTTP v1 send permission). Do not grant project-wide **Firebase
  Admin** (`roles/firebase.admin`); it is broader than push needs. Verify the
  current role definitions in the IAM documentation before granting.
- `error_status=SERVICE_DISABLED` → the Cloud Messaging API is not enabled. In
  the Google Cloud console, APIs & Services → Library → search
  **Cloud Messaging** → Enable.
- `validate-send` returns 401/403 → the access token was rejected; see above.
- `validate-send` returns the expected rejection for the requested shape (404
  `UNREGISTERED` for `synthetic`, 400 `INVALID_ARGUMENT` for `malformed`) →
  authentication works and the synthetic token was correctly rejected.
- `validate-send` returns any other status, including 5xx → the request failed;
  this is not evidence of authentication or token validation. Check quota,
  endpoint and payload before retrying.
