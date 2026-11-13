# Firebase Realtime Database rules contract

Offline emulator tests for the NexaChat RTDB candidate
(`database.rules.json`). The suite uses the demo project
`demo-nexachat-presence`, needs no Google credentials, and needs no network
once npm dependencies and the emulator JAR are cached.

```bash
npm ci
npm test
```

The first run downloads the pinned database emulator JAR into
`~/.cache/firebase/emulators`. A failed assertion fails the command through
`firebase emulators:exec`.

Scope, data model, CI job and the known rule gaps:
[docs/architecture/firebase-realtime.md](../../docs/architecture/firebase-realtime.md).
