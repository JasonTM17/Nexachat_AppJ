# NexaChat Architecture

> Diagrams verbatim from the master design (§7). Summary: Android client
> (Compose + MVVM/UDF, offline-first Room) → REST `/api/v1` + STOMP over
> WebSocket → Spring Boot modular monolith → PostgreSQL 16 + file volume.

## 1. Container diagram (§7.1 — sơ đồ tổng)

Overall system containers and their connections:

```mermaid
flowchart LR
    subgraph Device["Android (Kotlin + Compose)"]
        UI["Compose Screens<br/>(Material 3)"]
        VM["ViewModel + StateFlow<br/>(UDF)"]
        REPO["Repositories<br/>(offline-first)"]
        subgraph DS["Data Sources"]
            REST["Retrofit/OkHttp<br/>+ AuthInterceptor/Authenticator"]
            STOMP["StompClient<br/>(OkHttp WebSocket + STOMP frames)"]
            ROOM[("Room DB<br/>cache + outbox")]
            STORE["DataStore + TokenStore<br/>(Android Keystore AES)"]
        end
        UI --> VM --> REPO
        REPO --> REST & STOMP & ROOM & STORE
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
            COMMON["common: errors, logging,<br/>config, E.164 util"]
        end
        WS["WebSocket/STOMP layer<br/>@MessageMapping +<br/>ChannelInterceptor authZ"]
        BROKER["SimpleBroker<br/>(in-process)"]
    end

    PG[("PostgreSQL 16<br/>Flyway migrations")]
    VOL[("Volume: /data/uploads<br/>(StorageService interface<br/>→ S3 swappable)")]
    FCM[["FCM (optional, chưa bật)"]]

    REST -- "HTTPS REST /api/v1" --> SEC
    STOMP -- "WSS · STOMP frames" --> WS
    SEC --> Mod
    WS --> SEC
    WS --> MSG
    MSG --> BROKER --> STOMP
    Mod --> PG
    ATT --> VOL
    MSG -. "khi WS offline (optional)" .-> FCM
```

## 2. Module diagram (backend modules — extracted verbatim from §7.1)

The monolith is split into feature modules, each with its own
Controller/Service/Repository — one deployable unit:

```mermaid
flowchart TB
    subgraph Mod["Modules"]
        AUTH["auth"]
        USER["user · contact"]
        CONV["conversation"]
        MSG["message"]
        ATT["attachment"]
        COMMON["common: errors, logging,<br/>config, E.164 util"]
    end
```

## 3. Send-message sequence (§7.2 — luồng gửi tin)

Persist-before-broadcast with `clientMessageId` idempotency and watermark read
receipts:

```mermaid
sequenceDiagram
    participant A as Máy A (Compose)
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

*Why monolith, not microservices:* modules are separated by package with clear
boundaries, deployed as one unit — clean enough to discuss architecture,
simple enough for the team to operate; microservices at this scale only add
network/distributed-transaction complexity.
