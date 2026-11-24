# Virtual threads on the Render profile — evaluation

Evaluation for GH#320: whether the server should run on Java 21 virtual threads
in the Render Free deployment. The flag is off and no code change is proposed
here. This document records what the flag would change, what pins or blocks it
in this codebase, how to measure the difference locally, and the criteria for
turning it on later. The measurement tables are empty on purpose: no runtime
comparison has been run, and no number below is a projection.

## 1. Where the server is today

| Fact | Value | Source |
|---|---|---|
| Stack | Spring Boot 3.5.16 on Java 21 — virtual threads are available, unused | `server/pom.xml:7-12,20-21` |
| Flag set anywhere | no — `spring.threads.virtual.enabled` appears in no `application*.yml`, no `render.yaml`, no `Dockerfile`; the only mentions are old plans in `docs/archive/TECHNICAL_ROADMAP.md:275,927` | `grep` over `server/src`, `render.yaml`, `.github` |
| HTTP request threads | Tomcat, max 20, min-spare 2, `accept-count: 50`, `max-connections: 200` | `application-render.yml:10-18` |
| STOMP inbound threads | explicit core 4 / max 8 / queue 500 | `WebSocketConfig.java:88-94` |
| Broker heartbeat | own 1-thread `stomp-heartbeat-` scheduler | `WebSocketConfig.java:78-86` |
| Database connections | Hikari max 5, connect timeout 20 s | `application-render.yml:26,31` |
| Hand-built pools | OTP mail executor (`recovery/OtpMailDispatchConfiguration.java:13-20`), reminder and scheduled-message schedulers (`ReminderScheduler.java:244-245`, `ScheduledMessageScheduler.java:280-291`) | code |
| `@Async` usage | none in the server, so Boot's `applicationTaskExecutor` is not on any request path | `grep` |
| JVM fit | `-XX:MaxRAMPercentage=60.0`, SerialGC, `-Xss512k`, `-XX:ActiveProcessorCount=1`, C1-only JIT | `render.yaml:48-56` |
| Boot RSS | 348–358 MiB with the AppCDS archive, 397–400 MiB without, inside 512 MB | [deploy-render.md §7](deploy-render.md#7-cold-starts-and-free-tier-limits) |

Every pool that matters is already sized by hand for one 0.1-CPU box. The
round-trips that make threads wait are concentrated in one place: every STOMP
`SUBSCRIBE` runs a membership query (`StompAuthChannelInterceptor.java:503`) and
every `SEND` runs a per-frame validity probe that is cache-first and fills from
Postgres on a miss (`StompAuthChannelInterceptor.java:640-655`). That is the
workload a virtual thread would park on, and it is also why virtual threads
look attractive on paper.

This is "never enabled", not "tried and reverted": there is no commit, note or
benchmark in either direction.

## 2. What the flag would change

`spring.threads.virtual.enabled=true` — or `SPRING_THREADS_VIRTUAL_ENABLED=true`
in the environment — swaps the executors Spring Boot owns for virtual-thread
ones, so Tomcat serves each request on a virtual thread instead of a pooled
platform thread. In this codebase that has three concrete consequences:

- **`server.tomcat.threads.max: 20` stops being the concurrency limit.** Tomcat
  does not apply `maxThreads` to virtual threads, so `max-connections: 200`
  (`application-render.yml:17-18`) becomes the real ceiling. Twenty concurrent
  requests can become two hundred, and every one of them queues on five Hikari
  connections with a 20 s `connection-timeout` — a queue that does not exist
  today.
- **The hand-built pools do not move.** `WebSocketConfig` sizes the STOMP
  inbound executor itself (`WebSocketConfig.java:88-94`), as do
  `OtpMailDispatchConfiguration` and both schedulers. "Virtual threads are on"
  will not mean "every thread in the process is virtual"; any thread-count claim
  has to be checked per pool, and the 4/8 STOMP sizing stays in force whatever
  executor type ends up behind it.
- **`-Xss512k` changes meaning.** It keeps sizing the platform threads that
  remain — carriers, scheduler workers, the explicit pools — while
  virtual-thread stacks live on the heap and grow on demand. The 60% heap budget
  absorbs that in both directions: cheaper per idle connection, more heap per
  parked request.

## 3. What can pin or block a carrier on JDK 21

On JDK 21 a virtual thread **pins** its carrier while it blocks inside a
`synchronized` block or method, or inside a native frame. JEP 491 removes the
`synchronized` case in JDK 24; on 21 it applies in full. With
`-XX:ActiveProcessorCount=1` the default virtual-thread scheduler has **one**
carrier (`jdk.virtualThreadScheduler.parallelism` defaults to the processor
count), so a pinned critical section does not merely delay its own thread — it
takes the only carrier away from every other virtual thread until it exits.

| Site | Lines | What the monitor guards | How hot |
|---|---|---|---|
| `auth/RateLimitService.java` | 74, 144, 151, 162 | bucket state behind every STOMP SEND and every rate-limited REST call | SEND path |
| `auth/AccessTokenValidityCache.java` | 98, 113, 120, 127, 138, 158 | access-token validity cache read/write | every REST request and every STOMP frame |
| `ws/StompSessionRegistry.java` | 99, 129, 149, 172, 181, 214, 242, 298, 374 | per-session transport locks and per-user lifecycle locks | CONNECT / SUBSCRIBE / DISCONNECT |
| `presence/PresenceSessionRegistry.java` | 89, 122, 137 | per-user session set | connect / disconnect |
| `call/CallRegistry.java` | 41–213 (17 `synchronized` methods) | call state machine | call signaling |
| `reminder/ReminderScheduler.java` | 166, 180, 221, 237 | in-memory timer arming | scheduler thread |
| `scheduled/ScheduledMessageScheduler.java` | 198, 215, 257, 273 | in-memory timer arming | scheduler thread |
| `rag/VectorCache.java` | 62 | cache iteration under a synchronized map | AI query |
| `summary/llm/CircuitBreaker.java` | 29, 41, 46, 54 | breaker state | AI calls |
| `firebase/projection/FirebaseProjectionRetryQueue.java` | 41 | retry queue | Firebase bridge |
| `admin/AlertService.java` | 270, 279 | alert windows | admin tooling |

What is **not** a pinning risk here, so it should stay out of the discussion:

- `ReentrantLock` / `ReentrantReadWriteLock` — `StompRevocationFence.java:26-37`
  builds 256 fair stripes; `LockSupport` parks the virtual thread and releases
  the carrier.
- `Thread.sleep` — the single call site on the server,
  `mail/ResendMailSender.java:108` (the Resend retry pause), unmounts instead of
  pinning, and it runs on `otpMailTaskExecutor`, not on the request path.
- JDBC and HTTP calls park on sockets, not on monitors. On JDK 21 the JDK's
  socket implementations unmount a blocked virtual thread, so a slow Neon
  round-trip does not hold a carrier — unless the call is made inside one of the
  `synchronized` sections above. That is why the table matters more than the
  query count.

## 4. Risk assessment

Not "safe" and not "unsafe" — four risks, each with the check that would settle
it:

| Risk | Why it applies here | How it would surface | Check / mitigation |
|---|---|---|---|
| Single carrier blocked by `synchronized` | `ActiveProcessorCount=1` gives one carrier; `RateLimitService` and `AccessTokenValidityCache` sit on every SEND and every frame | p95 rises with concurrency while CPU stays low; throughput flattens | thread-dump census (§5 step 5) and the send/sync p95 pair; if a convoy shows, keep platform threads or move those sections to `ReentrantLock` — a separate change |
| Deeper concurrency into a 5-connection pool | Tomcat's 20-thread ceiling disappears; Hikari stays at 5 | bursts wait on the 20 s connection timeout and fail as 500s | keep `max-connections` / `accept-count` conservative; measure a burst, not a single request |
| Heap instead of stacks | each in-flight request keeps a stack on the heap, and boot RSS is already 348–358 MiB of 512 | RSS above 512 MB exits the JVM (`-XX:+ExitOnOutOfMemoryError`) | `tools/perf/jvm-footprint.sh` RSS pair, plus the unchanged 8×20 MB upload burst |
| Thread-name and ThreadLocal assumptions | `StompAuthChannelInterceptor.java:119-121` keeps the revocation actor and execution frames in `ThreadLocal`s, and `CallService.java:54` keeps a mutation scope | thread names in logs change (`http-nio-…` disappears); a `ThreadLocal` value lives as long as its virtual thread instead of being recycled by a pool | grep logs and tests for thread-name assertions before enabling; confirm the per-frame clearing still happens |

Scheduler pools are the least interesting axis: the Boot-managed `@Scheduled`
executor runs 30-minute jobs on Render (`application-render.yml:46-57`) and the
advisory locks are Postgres session locks (`ScheduledAdvisoryLock`), so thread
type changes nothing about correctness there.

## 5. Benchmark protocol — local, flag off vs on

The point is a paired comparison on the same machine and the same seed, not a
single impressive run. Same jar, same database snapshot, same JVM options; the
only difference between the two variants is the flag.

1. **Stack.** Local Compose Postgres (`docker-compose.yml`, service `db`) with
   the server built from `server/` and started with the Render JVM options from
   `render.yaml:48-56`. Expose metrics for the run:
   `MANAGEMENT_EXPOSURE=health,metrics` (`application.yml:65`; the default
   exposes only `health`, per
   [operations.md](operations.md#observability)).
2. **Variants.** A (baseline) `SPRING_THREADS_VIRTUAL_ENABLED=false`, B
   `SPRING_THREADS_VIRTUAL_ENABLED=true`. Run A, then B, then A again on the
   same host; a difference smaller than the A-to-A spread is noise.
3. **Load.** `python tools/e2e/perf/reconnect_workload.py` seeds 30
   conversations and a 55-message gap through REST, replays the bounded
   `SyncEngine` pagination and reconnects a raw STOMP client five times; it
   prints `sync_requests=… p95_ms=…` plus Docker and cgroup memory peaks.
   Add `python tools/e2e/perf_e2e.py --base-url http://localhost:8080` for REST
   p50/p95 across edit/delete/search. Both scripts deliberately stay under the
   app's own buckets (30 messages / 10 s per user,
   [websocket-protocol.md §4](../architecture/websocket-protocol.md)); exceed
   them and the run measures rate limiting, not threading.
4. **Footprint.**
   `tools/perf/jvm-footprint.sh --label vthreads-on --java-opts '<render flags> -Dspring.threads.virtual.enabled=true'`
   against the same command without the flag. It records time to
   liveness/readiness, RSS after boot, the native-memory summary and the upload
   burst.
5. **Thread census.** After each run,
   `jcmd <pid> Thread.dump_to_file -format=text /tmp/vt.txt`; count
   `VirtualThread` entries, and read the carrier annotations on parked stacks.
   Also read `/actuator/metrics/jvm.memory.used` with the `area=heap` tag.
6. **Record per run:** `{variant, run_at_utc, app_sha, java_opts, seed
   {conversations, messages}, samples, send p50/p95 ms, sync p95 ms, boot →
   readiness s, RSS after boot MiB, heap used MiB, virtual-thread count}`. Keep
   the raw logs and evidence directory outside the repository.

| Variant | Runs | send p50 (ms) | send p95 (ms) | sync p95 (ms) | boot → readiness (s) | RSS after boot (MiB) | Heap used (MiB) | Notes |
|---|---|---|---|---|---|---|---|---|
| A — platform (flag off) | — | — | — | — | — | — | — | — |
| B — virtual (flag on) | — | — | — | — | — | — | — | — |

*Not measured yet — fill in when runtime numbers exist.*

| Variant | `VirtualThread` count | Platform threads | Carrier-pinned stacks on the send path | Thread-named pools observed |
|---|---|---|---|---|
| A — platform (flag off) | — | — | — | — |
| B — virtual (flag on) | — | — | — | — |

*Not measured yet — fill in when runtime numbers exist.*

## 6. Verification checklist before enabling anywhere

Adapted from GH#320; every item needs evidence from §5, not a code reading:

- Startup passes and `readiness` reports `db`, `flyway`, `diskSpace`,
  `refreshReplay` UP.
- Smoke suite passes against the flagged build:
  `python tools/e2e/smoke_api.py --base-url http://localhost:8080`.
- Tomcat request threads are actually virtual — confirmed in the thread dump,
  not inferred from the property.
- The STOMP path is unchanged in behaviour: `SUBSCRIBE`, `SEND`, heartbeat at
  10 s/10 s, and the dual topic/inbox fan-out all still work with a raw STOMP
  client (the reconnect workload covers the reconnect half).
- No memory regression: RSS stays inside the 512 MB budget with headroom for
  two concurrent 25 MB uploads.
- Scheduled jobs still fire on their configured cadence.

## 7. Decision criteria

Enable only if all of these hold, measured against variant A on the same host,
back to back:

1. send and sync p95 are no worse than baseline — treat a regression larger than
   the A-to-A spread (and no smaller than 10%) as a blocker;
2. RSS after boot stays inside 512 MB with upload headroom;
3. no carrier-pinned stack sits on the SEND/SUBSCRIBE path in the dump;
4. the smoke suite and the scheduled jobs pass.

Otherwise record "evaluated, not enabled" with the numbers and close GH#320
with that evidence.

## 8. Recommendation for this wave

**Keep the default OFF; change no code.** Four reasons, all from the sections
above:

- there is no runtime comparison yet (the tables in §5 are empty), so any claim
  about gains would be a guess;
- the only blocking on the request path today is Neon round-trips, which the
  explicit 4–8 thread STOMP executor and the Hikari pool already absorb;
- the `synchronized` inventory sits exactly on the SEND path, on a scheduler
  that would have a single carrier under `-XX:ActiveProcessorCount=1`;
- the box is memory-tight (348–358 MiB of 512 MB after boot), and the flag
  trades stack budget for heap budget rather than removing work.

Turning it on is a separate decision for the TeamLead, taken after §5 numbers
exist and §3 has been reviewed. When that decision is made, the wiring is one
line: `spring.threads.virtual.enabled: true` under `spring:` in
`application-render.yml`, or `SPRING_THREADS_VIRTUAL_ENABLED: "true"` in the
`render.yaml` envVars. Only the `render` profile should change — Compose, tests
and CI keep platform threads. Deploy through the exact-SHA flow
([deploy-render.md §3](deploy-render.md)), because the production service is not
Blueprint-managed; reverting is deleting that line or variable and redeploying.

## 9. Non-goals

- Rewriting the `synchronized` registries to `ReentrantLock`. Separate change,
  separate issue, needs its own benchmark.
- Turning the STOMP inbound executor, the schedulers or the OTP mail executor
  into virtual-thread pools. They are sized deliberately for this box.
- Re-tuning `-Xss512k`, the heap percentage, SerialGC or the C1-only JIT.
- Measuring production. This document specifies local runs only; the Render
  performance profile and its cold-start scenarios live in
  [deploy-render.md §7](deploy-render.md#7-cold-starts-and-free-tier-limits).
