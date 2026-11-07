# NexaChat Architecture

> Current implementation view: Android app (Java 17, XML Views + Fragments +
> ViewBinding, ViewModel + LiveData, RxJava 3, offline-first Room) → REST
> `/api/v1` + STOMP over WebSocket → Spring Boot modular monolith → PostgreSQL 16
> + file volume. Call signaling shares STOMP (`server/call`,
> `app/call/core/StompCallSignalingClient.java`), paired with Android call screens
> (`CallActivity`, `IncomingCallActivity`) and WebRTC audio (`WebRtcMediaEngine`);
> see [docs/calls.md](calls.md).

## 1. Container diagram (§7.1 — sơ đồ tổng)

Overall system containers and their connections:

```mermaid
flowchart LR
    subgraph Device["Android (Java 17, XML Views + ViewBinding)"]
        UI["Fragments + XML Views<br/>(Material 3)"]
        VM["ViewModel + LiveData<br/>(RxJava 3 streams)"]
        REPO["Repositories<br/>(offline-first)"]
        subgraph DS["Data Sources"]
            REST["Retrofit/OkHttp<br/>+ AuthInterceptor/Authenticator"]
            STOMP["StompClient<br/>(OkHttp WebSocket + STOMP frames)"]
            ROOM[("Room DB (RxJava 3)<br/>cache + outbox")]
            STORE["RxDataStore + TokenStore<br/>(Android Keystore AES)"]
            SETTINGS["Local theme + locale settings"]
        end
        UI --> VM --> REPO
        REPO --> REST & STOMP & ROOM & STORE
        UI --> SETTINGS
    end

    subgraph Server["Backend — Modular Monolith (Java + Spring Boot)"]
        direction TB
        SEC["Spring Security<br/>JWT filter · Rate limit"]
        subgraph Mod["Modules"]
            AUTH["auth"]
            USER["user · contact"]
            CONV["conversation"]
            MSG["message"]
            ATT["attachment"]
            PRES["presence"]
            CALL["call signaling"]
            SUM["summary"]
            COMMON["common: errors, logging,<br/>config, E.164 util"]
        end
        WS["WebSocket/STOMP layer<br/>@MessageMapping +<br/>ChannelInterceptor authZ"]
        BROKER["SimpleBroker<br/>(in-process)"]
    PRESENCE["PresenceSessionRegistry<br/>(in-memory, per node)"]
    end

    PG[("PostgreSQL 16<br/>Flyway migrations")]
    VOL[("Local file volume<br/>(StorageService boundary)")]
    FCM[["FCM (optional, chưa bật)"]]

    REST -- "HTTPS REST /api/v1" --> SEC
    STOMP -- "WSS · STOMP frames" --> WS
    SEC --> Mod
    WS --> SEC
    WS --> MSG
    WS --> CALL
    WS --> PRESENCE
    PRES --> PRESENCE --> BROKER
    MSG --> BROKER --> STOMP
    Mod --> PG
    ATT --> VOL
    MSG -. "khi WS offline (optional)" .-> FCM
```

## 2. Backend module diagram

The monolith separates feature code by package and deploys as one backend
unit. Individual modules use the controller/service/repository layers they
need; the diagram is a package overview, not a claim that every package has
all three layers.

```mermaid
flowchart TB
    subgraph Mod["Modules"]
        AUTH["auth"]
        USER["user · contact"]
        CONV["conversation"]
        MSG["message"]
        ATT["attachment"]
        PRES["presence"]
        CALL["call signaling"]
        SUM["summary"]
        COMMON["common: errors, logging,<br/>config, E.164 util"]
    end
```

## 3. Send-message sequence

Persist-before-broadcast with `clientMessageId` idempotency and watermark read
receipts:

```mermaid
sequenceDiagram
    participant A as Máy A (Fragment UI)
    participant S as Spring Backend
    participant DB as PostgreSQL
    participant B as Máy B

    A->>A: optimistic insert Room (SENDING, clientMessageId)
    A->>S: STOMP SEND /app/conversations/42/messages<br/>{clientMessageId, content}
    S->>S: authN (JWT) + authZ (member of 42) + validate
    S->>DB: TX: INSERT messages (seq BIGSERIAL)<br/>ON CONFLICT (sender,clientMessageId) → return existing<br/>UPDATE conversations.last_message_*
    DB-->>S: message{seq, createdAt}
    S-->>A+B: STOMP MESSAGE → /topic/conversations.42<br/>event MESSAGE_CREATED
    A->>A: match clientMessageId → SENT
    B->>B: render + unread++ (watermark chưa đọc)
    Note over B: khi mở chat → POST /conversations/42/read {upToSeq}<br/>→ A nhận MESSAGE_READ cập nhật "Đã xem"
```

## 4. Edit, delete and search

- **Edit:** an authenticated conversation member may edit their own `TEXT`
  message. The server trims and validates content, updates `editedAt`, commits,
  then broadcasts `MESSAGE_UPDATED`. Repeating the same normalized content is
  a no-op. The REST search endpoint searches live `TEXT` rows in one
  conversation using escaped, accent-insensitive `unaccent` + `ILIKE` matching.
- **Delete:** the author soft-deletes their message. The row and sequence stay
  in history as a tombstone (`content: null`); reactions and pins are removed.
  Attachment metadata and stored bytes are retained, but the deleted message's
  attachments cannot be downloaded through the message binding.
- **Presence:** each authenticated STOMP session contributes to an
  in-memory per-user count. After commit, presence goes only to DIRECT peers
  when the subject allows presence. Hiding one's presence is one-way; it does
  not hide the peers from that user. Last-disconnect offline notification is
  coalesced for five seconds.

Theme mode (`SYSTEM`, `LIGHT`, `DARK`) and locale are local Android settings;
they are applied by the app and are not persisted as server account settings.

*Why monolith, not microservices:* modules are separated by package with clear
boundaries, deployed as one unit — clean enough to discuss architecture,
simple enough for the team to operate; microservices at this scale only add
network/distributed-transaction complexity.
