# Free-tier capacity observability

This is the manual companion to the server metrics in
[docs/operations.md](operations.md). The server can measure its own work, but it
cannot read a provider's quota. The provider consoles remain the source of truth
for current limits and usage; this document intentionally does not repeat quota
numbers that can change without a code change.

## What the server records

Storage work is recorded by `StorageMetricsAspect` around every
`StorageService` bean, so the same meters cover the `local` and `s3` providers:

| Name | Type | Tags |
|---|---|---|
| `nexachat.storage.operations` | counter | `operation`, `provider`, `outcome` |
| `nexachat.storage.operation.duration` | timer | `operation`, `provider`, `outcome` |
| `nexachat.storage.operation.bytes` | summary | `operation`, `provider`, `outcome` |
| `nexachat.storage.stream.duration` | timer | `operation`, `provider`, `outcome` |
| `nexachat.storage.stream.bytes` | summary | `operation`, `provider`, `outcome` |

Bounded label values:

- `operation`: `STORE`, `OPEN`, `OPEN_RANGE`, `DELETE`.
- `provider`: `local`, `s3`.
- `outcome` for operations: `SUCCESS`, `FAILURE`.
- `outcome` for streams: `COMPLETE`, `CLOSED`, `FAILURE`.

`nexachat.storage.operation.*` ends when the service call returns. For
`OPEN`/`OPEN_RANGE` this is only the time to obtain the stream handle; it does
not include writing the response. `nexachat.storage.stream.*` starts at the
handle and ends at end-of-stream, early close or an I/O failure, so a slow client
or a slow object-store body cannot inflate the open-latency number.

`operation.bytes` is the size reported by a successful `STORE` (the attempted
input size when the store fails), the object size reported by the handle for
`OPEN`/`OPEN_RANGE`, and zero for `DELETE`. `stream.bytes` is the number of bytes
actually read or skipped, so a partial download or an early close is visible
separately.

No storage key, filename, URL, user id or token is a tag. All values above are
fixed sets, and every meter is registered eagerly with the `none` warm-up series
required by the Prometheus tag-key rule.

The Hikari, JVM, process and HTTP meters are Spring Boot Actuator meters and are
not duplicated by this instrumentation. See
[docs/operations.md](operations.md#observability) for the exposure and
authentication rules. Keep `MANAGEMENT_EXPOSURE` at `health` unless a scraper
needs `metrics`/`prometheus`, and keep the management address on loopback.

## PromQL for capacity review

Bytes accepted by the store path in the last hour:

```promql
sum(increase(nexachat_storage_operation_bytes_sum{operation="STORE"}[1h]))
```

Bytes actually consumed from streams in the last hour:

```promql
sum(increase(nexachat_storage_stream_bytes_sum[1h]))
```

Failure share by operation and provider:

```promql
sum(rate(nexachat_storage_operations_total{outcome="FAILURE"}[15m])) by (operation, provider)
  / sum(rate(nexachat_storage_operations_total[15m])) by (operation, provider)
```

p95 request-level latency and p95 stream consumption, both separate:

```promql
histogram_quantile(0.95,
  sum(rate(nexachat_storage_operation_duration_seconds_bucket[10m])) by (le, operation, provider))

histogram_quantile(0.95,
  sum(rate(nexachat_storage_stream_duration_seconds_bucket[10m])) by (le, operation, provider))
```

The timers publish histogram buckets, so average latency is also available from
the `_sum` and `_count` series if a dashboard prefers it.

The example alerts in
[tools/observability/prometheus-alerts.yml](../tools/observability/prometheus-alerts.yml)
use the same series. Validate changed rules with the current Prometheus release:

```sh
docker run --rm --entrypoint promtool \
  -v "$PWD/tools/observability/prometheus-alerts.yml:/rules.yml:ro" \
  prom/prometheus:latest check rules /rules.yml
```

If `promtool` cannot run, the rule change is unverified, not passed.

## Manual free-tier checks

There is no API from these providers that the server should call on every
scrape. Use the provider dashboards on a fixed cadence and record the values
that the dashboard shows, including the plan name and the date. Do not copy a
quota number out of an old document; read the current limit from the console.

| Surface | Where to check | What to look for | Suggested cadence |
|---|---|---|---|
| Render memory | Render dashboard → the `nexachat-api` service → Metrics → Memory | The service's memory curve against the ceiling shown for the current plan; restarts or `ExitOnOutOfMemoryError` in logs | After each deploy, after a large media/group exercise, and weekly |
| Neon compute hours | Neon console → project → Usage / Billing | Compute hours used against the allowance shown for the current plan; whether the compute is suspending when idle | Weekly, and after a period of unusual traffic |
| Neon storage | Neon console → project → Storage / Branches | Database size, WAL and branch growth; unexpected growth after retention or backup work | Weekly and after a retention run |
| Supabase storage | Supabase dashboard → Storage → Usage | Object bytes, object count and egress against the allowance shown for the current plan | Weekly and after a media-heavy test |
| Other S3-compatible storage | The bucket console (for example Cloudflare R2) | Bucket bytes, object count and operations against the current plan | Weekly if that provider is the configured `s3` backend |

The manual check is a trend review, not a substitute for the server alerts. If a
provider is near its current allowance, reduce wakeups or clean only data that
the retention policy already permits; do not enable paid overage, production
cleanup or a quota change without the owner's authorization.

## Local fault receipt

`StorageMetricsAspectTest` runs a real local `StorageService` through the
aspect: a missing open, a rejected store and a traversal delete raise the
failure counters, and a later successful store/open/delete raises the recovery
counters. It also proves that a full stream is `COMPLETE`, an early close is
`CLOSED`, and that no storage tag key or value escapes the bounded set.

```sh
mvn -B -f server/pom.xml \
  -Dtest=StorageMetricsAspectTest,ObservabilityPrometheusMetricsTest test
```

## Limits

- `open` metrics cover `open` and `openRange`; the bounded `readPrefix`,
  `readSuffix`, `exists` and `list` helpers are not part of this meter set.
- A stream that never reaches EOF or close is not recorded until it ends; the
  request timeout and server logs remain the evidence for a stuck response.
- The meters describe server work, not provider billing. A quiet Render service
  can sleep and a Neon compute can suspend, so a scrape gap is not itself an
  outage.
- `operation.bytes` for `OPEN_RANGE` is the total object size, while
  `stream.bytes` is the slice actually read.
