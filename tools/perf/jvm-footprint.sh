#!/usr/bin/env bash
# Measure the Render Free JVM footprint (0.1 CPU / 512 MB) of the server image.
#
# The script builds server/Dockerfile, runs the image with the Render JAVA_OPTS
# against a throw-away local Postgres, and records:
#   - seconds until /actuator/health/liveness and readiness are UP
#   - Spring's "Started NexaChatApplication in ..." line
#   - docker stats memory after boot
#   - heap / Metaspace / Code Cache / direct buffers / threads (Actuator)
#   - peak cgroup memory during 8 concurrent 20 MB uploads
#   - the native-memory summary printed at JVM exit
#
# Usage:
#   tools/perf/jvm-footprint.sh --label baseline [options]
#   tools/perf/jvm-footprint.sh --label cds --image nexachat-jvm-footprint:cds --skip-build --in-image-archive
#
# Options:
#   --label NAME          result folder name (required)
#   --java-opts "..."     JAVA_OPTS for the run (default: render.yaml value)
#   --archive PATH        AppCDS archive to mount at /app/application.jsa
#   --in-image-archive    use the archive already baked at /app/application.jsa
#   --image NAME          image tag (default: nexachat-jvm-footprint:local)
#   --skip-build          reuse an existing image tag
#   --no-upload           skip the concurrent-upload phase
#   --skip-migrate        reuse the existing database instead of dropping it
#   --teardown            remove the database container and network at the end
#   --out-dir PATH        result directory (default: server/target/jvm-footprint)
#   --port PORT           host port for the app (default: 18097)
#   --db-port PORT        host port for Postgres (default: 15447)
#
# Requires: docker, curl, python (for timestamps/JSON parsing).
# Everything it creates is prefixed nexachat-jvm- and removed with --teardown.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SERVER="$ROOT/server"
LABEL=""
JAVA_OPTS_DEFAULT="-XX:MaxRAMPercentage=60.0 -XX:+UseSerialGC -Xss512k -XX:MaxMetaspaceSize=128m -XX:ActiveProcessorCount=1 -XX:TieredStopAtLevel=1 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"
JAVA_OPTS="$JAVA_OPTS_DEFAULT"
ARCHIVE=""
IN_IMAGE_ARCHIVE=0
IMAGE="nexachat-jvm-footprint:local"
BUILD=1
UPLOAD=1
MIGRATE=1
TEARDOWN=0
OUT_BASE="$SERVER/target/jvm-footprint"
APP_PORT=18097
DB_PORT=15447
NET=nexachat-jvm-net
DB=nexachat-jvm-db
APP=nexachat-jvm-app
DB_NAME=nexachat_jvm_measure

while [ $# -gt 0 ]; do
  case "$1" in
    --label) LABEL="$2"; shift 2 ;;
    --java-opts) JAVA_OPTS="$2"; shift 2 ;;
    --archive) ARCHIVE="$2"; shift 2 ;;
    --in-image-archive) IN_IMAGE_ARCHIVE=1; shift ;;
    --image) IMAGE="$2"; shift 2 ;;
    --skip-build) BUILD=0; shift ;;
    --no-upload) UPLOAD=0; shift ;;
    --skip-migrate) MIGRATE=0; shift ;;
    --teardown) TEARDOWN=1; shift ;;
    --out-dir) OUT_BASE="$2"; shift 2 ;;
    --port) APP_PORT="$2"; shift 2 ;;
    --db-port) DB_PORT="$2"; shift 2 ;;
    -h|--help) sed -n '2,31p' "$0"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

if [ -z "$LABEL" ]; then
  echo "--label is required" >&2
  exit 2
fi

OUT="$OUT_BASE/$LABEL"
mkdir -p "$OUT"

# Git Bash rewrites arguments that look like Unix paths; Docker needs /app/... verbatim.
export MSYS_NO_PATHCONV=1
winpath() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else echo "$1"; fi; }

timestamp() { python -c 'import time; print("%.3f" % time.time())'; }
elapsed() { python -c "print('%.1f' % ($2 - $1))"; }

cleanup_app() { docker rm -f "$APP" >/dev/null 2>&1 || true; }

wait_for_postgres() {
  for _ in $(seq 1 60); do
    if docker exec "$DB" pg_isready -U nexachat -d postgres >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  echo "Postgres did not become ready" >&2
  return 1
}

if [ "$BUILD" = 1 ]; then
  echo "[$LABEL] building $IMAGE"
  docker build -t "$IMAGE" "$(winpath "$SERVER")" >"$OUT/build.log" 2>&1
fi

docker network inspect "$NET" >/dev/null 2>&1 || docker network create "$NET" >/dev/null
docker rm -fv "$DB" >/dev/null 2>&1 || true
docker run -d --name "$DB" --network "$NET" \
  -e POSTGRES_DB=postgres -e POSTGRES_USER=nexachat -e POSTGRES_PASSWORD=nexachat \
  -p "$DB_PORT:5432" postgres:16-alpine >/dev/null
wait_for_postgres

if [ "$MIGRATE" = 1 ]; then
  docker exec "$DB" psql -U nexachat -d postgres \
    -c "drop database if exists $DB_NAME with (force)" \
    -c "create database $DB_NAME" >/dev/null
fi

JWT_SECRET="jvm-footprint-secret-$(python -c 'print("x" * 60)')"
REFRESH_KEY="BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc="
ADMIN_PHONE="+84900000001"
ADMIN_PASSWORD="jvm-footprint-pass-123"

cleanup_app
START_TS=$(timestamp)
EXTRA_OPTS="-XX:NativeMemoryTracking=summary -XX:+UnlockDiagnosticVMOptions -XX:+PrintNMTStatistics -Dmanagement.endpoints.web.exposure.include=health,metrics"
if [ -n "$ARCHIVE" ]; then
  EXTRA_OPTS="$EXTRA_OPTS -XX:SharedArchiveFile=/app/application.jsa -Xshare:on"
fi
if [ "$IN_IMAGE_ARCHIVE" = 1 ]; then
  EXTRA_OPTS="$EXTRA_OPTS -XX:SharedArchiveFile=/app/application.jsa -Xshare:on"
fi
echo "[$LABEL] starting app (cpus=0.1, memory=512m)"
ARCHIVE_ARGS=()
if [ -n "$ARCHIVE" ]; then
  ARCHIVE_ARGS=(-v "$(winpath "$(cd "$(dirname "$ARCHIVE")" && pwd)")/$(basename "$ARCHIVE"):/app/application.jsa:ro")
fi
docker run -d --name "$APP" --network "$NET" --cpus 0.1 -m 512m \
  -p "$APP_PORT:8080" \
  "${ARCHIVE_ARGS[@]}" \
  -e "JAVA_OPTS=$JAVA_OPTS $EXTRA_OPTS" \
  -e SPRING_PROFILES_ACTIVE=prod,render,json-logs \
  -e "DATABASE_URL=jdbc:postgresql://$DB:5432/$DB_NAME" \
  -e DATABASE_USER=nexachat -e DATABASE_PASSWORD=nexachat \
  -e "JWT_SECRET=$JWT_SECRET" -e "NEXACHAT_REFRESH_REPLAY_KEY=$REFRESH_KEY" \
  -e STORAGE_ROOT=/app/data/uploads -e NEXACHAT_STORAGE_PROVIDER=local \
  -e FCM_ENABLED=false -e FIREBASE_REALTIME_ENABLED=false \
  -e NEXACHAT_MAIL_PROVIDER= -e NEXACHAT_GOOGLE_SIGNIN_ENABLED=false \
  -e "NEXACHAT_ADMIN_PHONES=$ADMIN_PHONE" \
  -e MANAGEMENT_EXPOSURE=health,metrics \
  "$IMAGE" >/dev/null

LIVE_TS=""
READY_TS=""
for _ in $(seq 1 900); do
  code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 3 "http://localhost:$APP_PORT/actuator/health/liveness" || true)
  if [ "$code" = "200" ]; then LIVE_TS=$(timestamp); break; fi
  if ! docker inspect -f '{{.State.Running}}' "$APP" 2>/dev/null | grep -q true; then
    echo "[$LABEL] app container exited before becoming healthy" >&2
    docker logs "$APP" >"$OUT/app.log" 2>&1 || true
    exit 1
  fi
  sleep 1
done
for _ in $(seq 1 120); do
  body=$(curl -s --max-time 3 "http://localhost:$APP_PORT/actuator/health/readiness" || true)
  if echo "$body" | grep -q '"status":"UP"'; then READY_TS=$(timestamp); break; fi
  sleep 1
done

docker logs "$APP" >"$OUT/app.log" 2>&1 || true
grep -o 'Started NexaChatApplication in [0-9.]* seconds' "$OUT/app.log" | tail -1 >"$OUT/started.txt" || true
STARTED_IN=$(grep -o '[0-9.]*' "$OUT/started.txt" | head -1 || true)

sleep 5
{
  for _ in 1 2 3 4 5; do
    docker stats --no-stream --format '{{.MemUsage}}' "$APP"
    sleep 1
  done
} >"$OUT/docker-stats-boot.txt"
docker exec "$APP" cat /sys/fs/cgroup/memory.current >"$OUT/cgroup-current-boot.txt" 2>/dev/null || true
docker exec "$APP" cat /sys/fs/cgroup/memory.stat >"$OUT/cgroup-stat-boot.txt" 2>/dev/null || true

# Register an operator account so the protected Actuator metrics are readable.
curl -s --max-time 120 -H 'Content-Type: application/json' \
  -d "{\"phone\":\"$ADMIN_PHONE\",\"password\":\"$ADMIN_PASSWORD\",\"displayName\":\"Footprint Measure\"}" \
  "http://localhost:$APP_PORT/api/v1/auth/register" >"$OUT/register.json" 2>/dev/null || true
TOKEN=$(cat "$OUT/register.json" 2>/dev/null | python -c 'import json,sys
try:
    print(json.load(sys.stdin)["accessToken"])
except Exception:
    pass' 2>/dev/null || true)
if [ -z "$TOKEN" ]; then
  curl -s --max-time 120 -H 'Content-Type: application/json' \
    -d "{\"phone\":\"$ADMIN_PHONE\",\"password\":\"$ADMIN_PASSWORD\"}" \
    "http://localhost:$APP_PORT/api/v1/auth/login" >"$OUT/login.json" 2>/dev/null || true
  TOKEN=$(cat "$OUT/login.json" 2>/dev/null | python -c 'import json,sys
try:
    print(json.load(sys.stdin)["accessToken"])
except Exception:
    pass' 2>/dev/null || true)
fi

metric() {
  local name="$1" tag="$2"
  curl -s --max-time 5 -H "Authorization: Bearer $TOKEN" \
    "http://localhost:$APP_PORT/actuator/metrics/$name$tag" \
    | python -c 'import json,sys
try:
    d=json.load(sys.stdin)
    print(d["measurements"][0]["value"])
except Exception:
    print("")' 2>/dev/null || true
}
{
  echo "heap_used=$(metric jvm.memory.used '?tag=area:heap')"
  echo "heap_committed=$(metric jvm.memory.committed '?tag=area:heap')"
  echo "heap_max=$(metric jvm.memory.max '?tag=area:heap')"
  echo "nonheap_used=$(metric jvm.memory.used '?tag=area:nonheap')"
  echo "metaspace_used=$(metric jvm.memory.used '?tag=id:Metaspace')"
  echo "code_cache_used=$(metric jvm.memory.used '?tag=id:Code%20Cache')"
  echo "direct_used=$(metric jvm.buffer.memory.used '?tag=id:direct')"
  echo "threads_live=$(metric jvm.threads.live '')"
  echo "classes_loaded=$(metric jvm.classes.loaded '')"
} >"$OUT/metrics-boot.txt"

UPLOAD_PEAK_BYTES=0
UPLOAD_PEAK_ANON_BYTES=0
UPLOAD_STATUS="skipped"
if [ "$UPLOAD" = 1 ] && [ -n "$TOKEN" ]; then
  TMP_UPLOADS="$(mktemp -d)"
  TMP_UPLOADS_WIN="$(winpath "$TMP_UPLOADS")"
  python - "$TMP_UPLOADS_WIN" <<'PY'
import os, sys
d = sys.argv[1]
head = bytes.fromhex("ffd8ffe000104a4649460001")
for i in range(8):
    with open(os.path.join(d, f"upload-{i}.jpg"), "wb") as f:
        f.write(head)
        f.write(b"\0" * (20 * 1024 * 1024 - len(head)))
PY
  (
    for _ in $(seq 1 1200); do
      docker exec "$APP" sh -c 'c=$(cat /sys/fs/cgroup/memory.current); a=$(awk "/^anon /{print \$2}" /sys/fs/cgroup/memory.stat); echo "$c $a"' 2>/dev/null || true
      sleep 0.2
    done
  ) >"$OUT/cgroup-upload-samples.txt" &
  SAMPLER=$!
  : >"$OUT/upload-status.txt"
  UPLOAD_PIDS=()
  for i in $(seq 0 7); do
    (
      code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 180 \
        -H "Authorization: Bearer $TOKEN" \
        -F "file=@$TMP_UPLOADS_WIN/upload-$i.jpg;type=image/jpeg" \
        "http://localhost:$APP_PORT/api/v1/attachments" || true)
      echo "$i $code" >>"$OUT/upload-status.txt"
    ) &
    UPLOAD_PIDS+=("$!")
  done
  wait "${UPLOAD_PIDS[@]}" 2>/dev/null || true
  sleep 2
  kill "$SAMPLER" 2>/dev/null || true
  wait "$SAMPLER" 2>/dev/null || true
  rm -rf "$TMP_UPLOADS"
  UPLOAD_PEAK_BYTES=$(awk '{print $1}' "$OUT/cgroup-upload-samples.txt" | sort -n | tail -1)
  UPLOAD_PEAK_ANON_BYTES=$(awk '{print $2}' "$OUT/cgroup-upload-samples.txt" | sort -n | tail -1)
  UPLOAD_STATUS=$(sort -n "$OUT/upload-status.txt" | awk '{print $2}' | sort | uniq -c | tr '\n' ' ')
fi

# Graceful stop so the JVM prints the native-memory summary.
docker stop --time 30 "$APP" >/dev/null 2>&1 || true
docker logs "$APP" >"$OUT/app.log" 2>&1 || true
sed -n '/^Native Memory Tracking:/,$p' "$OUT/app.log" >"$OUT/nmt-summary.txt" || true
cleanup_app

RSS_BOOT_MAX=$(sed -E 's#^([0-9.]+)([A-Za-z]+) / .*#\1 \2#' "$OUT/docker-stats-boot.txt" | sort -k1 -n | tail -1)
CGROUP_ANON=$(grep -E '^anon ' "$OUT/cgroup-stat-boot.txt" | awk '{print $2}' || true)
CGROUP_FILE=$(grep -E '^file ' "$OUT/cgroup-stat-boot.txt" | awk '{print $2}' || true)

cat >"$OUT/summary.txt" <<EOF
label=$LABEL
image=$IMAGE
java_opts=$JAVA_OPTS $EXTRA_OPTS
archive=${ARCHIVE:-none}
in_image_archive=$IN_IMAGE_ARCHIVE
time_to_liveness_s=$(if [ -n "$LIVE_TS" ]; then elapsed "$START_TS" "$LIVE_TS"; else echo NOT_RUN; fi)
time_to_readiness_s=$(if [ -n "$READY_TS" ]; then elapsed "$START_TS" "$READY_TS"; else echo NOT_RUN; fi)
spring_started_s=${STARTED_IN:-NOT_RUN}
rss_boot_max=${RSS_BOOT_MAX:-NOT_RUN}
cgroup_anon_boot_bytes=${CGROUP_ANON:-NOT_RUN}
cgroup_file_boot_bytes=${CGROUP_FILE:-NOT_RUN}
upload_peak_bytes=${UPLOAD_PEAK_BYTES:-0}
upload_peak_anon_bytes=${UPLOAD_PEAK_ANON_BYTES:-0}
upload_status=${UPLOAD_STATUS}
EOF
cat "$OUT/metrics-boot.txt" >>"$OUT/summary.txt"
echo "[$LABEL] ---- summary ----"
cat "$OUT/summary.txt"
echo "[$LABEL] results in $OUT"

if [ "$TEARDOWN" = 1 ]; then
  docker rm -fv "$DB" >/dev/null 2>&1 || true
  docker network rm "$NET" >/dev/null 2>&1 || true
fi
